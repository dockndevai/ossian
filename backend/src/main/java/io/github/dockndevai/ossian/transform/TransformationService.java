package io.github.dockndevai.ossian.transform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.document.DocumentContentRepository;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;
import io.github.dockndevai.ossian.ingest.IngestionService;
import io.github.dockndevai.ossian.observability.PipelineMetrics;
import io.github.dockndevai.ossian.settings.SettingsService;
import io.github.dockndevai.ossian.caller.CallerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a transformation over a whole source.
 *
 * <p>The important difference from asking a question: nothing here goes through the retriever.
 * Retrieval finds the passages most similar to a query, which is exactly wrong for "summarise
 * this" — the chunks nearest to the word "summarise" are not the chunks a summary needs. The
 * document is read in full instead.
 *
 * <p>Documents longer than one model call are read in windows and the outputs combined, rather
 * than truncated. Truncation is the tempting shortcut and the worst option: a summary of the
 * first few pages is indistinguishable from a summary of the whole document, so the failure is
 * invisible to the person reading it.
 */
@Service
public class TransformationService {

	private static final Logger log = LoggerFactory.getLogger(TransformationService.class);

	/**
	 * The starter set, created the first time a tenant looks at its transformations.
	 *
	 * <p>Seeded rather than hard-coded so they can be edited: a prompt that cannot be changed is
	 * a prompt that will be wrong for somebody. Each is written to say what to do when the source
	 * does not support the task, because that is the case a generic prompt handles worst.
	 */
	private static final List<String[]> DEFAULTS = List.of(
			new String[] { "summary", "Summary", "A faithful precis of what the source actually says.", """
					Summarise the source below in at most 200 words.

					Report only what the source says. Do not add background, context or conclusions of
					your own, and do not soften or sharpen its claims. If the source is too short or
					too fragmentary to summarise, say exactly that instead of padding.

					SOURCE:
					{{content}}""" },
			new String[] { "key-points", "Key points", "The claims a reader must not miss, as a list.", """
					List the key points of the source below as bullet points, at most eight.

					Each bullet must be a claim the source actually makes, not an inference from it.
					Prefer the specific over the general: a bullet that would be true of any document
					on this subject is not a key point. If there are fewer than eight, give fewer.

					SOURCE:
					{{content}}""" },
			new String[] { "questions", "Open questions",
					"What the source leaves unanswered — the gaps worth filling.", """
					Read the source below and list the questions it raises but does not answer.

					These are gaps in the source, not questions you happen to have. A good entry names
					something the source depends on, refers to, or implies without establishing. If the
					source is self-contained, say so rather than inventing questions.

					SOURCE:
					{{content}}""" },
			new String[] { "actions", "Action items", "Anything the source asks somebody to do.", """
					Extract every action item from the source below.

					For each, give the action, who is responsible if the source names them, and any
					deadline it states. Do not invent owners or dates. If the source contains no action
					items, say so plainly — an empty result is a valid one.

					SOURCE:
					{{content}}""" },
			new String[] { "terms", "Terms and definitions",
					"The vocabulary a newcomer would need to read this.", """
					List the terms, acronyms and named concepts in the source below that a newcomer
					would not already know, with the definition the source itself gives.

					Where the source uses a term without defining it, say so rather than supplying a
					definition from your own knowledge — an undefined term is useful to know about.

					SOURCE:
					{{content}}""" });

	private static final String CONTENT_TOKEN = "{{content}}";

	/** Redis cache holding transformation output, keyed by tenant and input digest. */
	static final String INSIGHT_CACHE = "insights";

	private final TransformationRepository transformations;

	private final InsightRepository insights;

	private final DocumentRepository documents;

	private final DocumentContentRepository contents;

	private final ChatClient chatClient;

	private final SettingsService settings;

	private final OssianProperties properties;

	private final CallerContext caller;

	private final CacheManager caches;

	private final PipelineMetrics metrics;

	public TransformationService(TransformationRepository transformations, InsightRepository insights,
			DocumentRepository documents, DocumentContentRepository contents, ChatClient.Builder chatClientBuilder,
			SettingsService settings, OssianProperties properties, CallerContext caller, CacheManager caches, PipelineMetrics metrics) {
		this.transformations = transformations;
		this.insights = insights;
		this.documents = documents;
		this.contents = contents;
		this.chatClient = chatClientBuilder.build();
		this.settings = settings;
		this.properties = properties;
		this.caller = caller;
		this.caches = caches;
		this.metrics = metrics;
	}

	/** Every transformation, seeding the starter set on first use. */
	@Transactional
	public List<Transformation> list() {
		if (!this.transformations.existsByIdNotNull()) {
			seed();
		}
		return this.transformations.findAllByOrderByPositionAscNameAsc();
	}

	private void seed() {
		int position = 0;
		for (String[] d : DEFAULTS) {
			Transformation entity = new Transformation();
			entity.setSlug(d[0]);
			entity.setName(d[1]);
			entity.setDescription(d[2]);
			entity.setPrompt(d[3]);
			entity.setPosition(position++);
			this.transformations.save(entity);
		}
		log.info("Seeded {} default transformations for tenant {}", DEFAULTS.size());
	}

	@Transactional
	public Transformation save(UUID id, String name, String description, String prompt, boolean applyOnIngest,
			Integer position) {
		if (prompt == null || prompt.isBlank()) {
			throw new IllegalArgumentException("A transformation needs a prompt");
		}
		if (!prompt.contains(CONTENT_TOKEN)) {
			// Without the placeholder the model would be handed an instruction and no document,
			// and would cheerfully answer from nothing. Better to refuse at edit time.
			throw new IllegalArgumentException("The prompt must include " + CONTENT_TOKEN
					+ " where the source text should go");
		}
		Transformation entity = (id == null) ? new Transformation()
				: this.transformations.findById(id)
					.orElseThrow(() -> new IllegalArgumentException("Transformation not found"));

		entity.setName((name == null || name.isBlank()) ? "Untitled" : name.trim());
		if (entity.getSlug() == null) {
			entity.setSlug(uniqueSlug(Transformation.slugify(entity.getName())));
		}
		entity.setDescription(description);
		entity.setPrompt(prompt);
		entity.setApplyOnIngest(applyOnIngest);
		if (position != null) {
			entity.setPosition(position);
		}
		entity.setUpdatedAt(Instant.now());
		return this.transformations.save(entity);
	}

	private String uniqueSlug(String base) {
		String candidate = base;
		int suffix = 2;
		while (this.transformations.findBySlug(candidate).isPresent()) {
			candidate = base + "-" + suffix++;
		}
		return candidate;
	}

	@Transactional
	public void delete(UUID id) {
		this.transformations.findById(id).ifPresent(this.transformations::delete);
	}

	public List<Insight> insightsFor(UUID documentId) {
		return this.insights.findByDocumentIdOrderByCreatedAtDesc(documentId);
	}

	@Transactional
	public void deleteInsight(UUID id) {
		this.insights.findById(id).ifPresent(this.insights::delete);
	}

	/** Runs one transformation over one document and stores the result. */
	@Transactional
	public Insight run(UUID documentId, String slug) {
		DocumentEntity document = this.documents.findById(documentId)
			.orElseThrow(() -> new IllegalArgumentException("Document not found"));
		Transformation transformation = this.transformations.findBySlug(slug)
			.orElseThrow(() -> new IllegalArgumentException("Transformation not found: " + slug));

		return execute(document, transformation, this.caller.username());
	}

	/**
	 * Runs every transformation marked to apply on ingest.
	 *
	 * <p>Runs on the ingestion thread, where there is no security context — hence the fixed
	 * actor below rather than a lookup of who is calling.
	 */
	@Transactional
	public void runOnIngest(DocumentEntity document) {
		for (Transformation transformation : this.transformations
			.findByApplyOnIngestTrueOrderByPositionAsc()) {
			try {
				execute(document, transformation, "ingest");
			}
			catch (RuntimeException ex) {
				// One failing transformation must not fail the ingest or the ones after it.
				log.warn("Transformation {} failed for document {}: {}", transformation.getSlug(), document.getId(),
						ex.getMessage());
			}
		}
	}

	private Insight execute(DocumentEntity document, Transformation transformation, String actor) {
		byte[] content = this.contents.findById(document.getId())
			.orElseThrow(() -> new IllegalArgumentException(
					"The original file is not retained for this document, so it cannot be transformed"))
			.getContent();

		String text = IngestionService.parseToText(content, document.getFilename());
		if (text.isBlank()) {
			throw new IllegalArgumentException("No readable text could be extracted from this document");
		}

		long started = System.nanoTime();
		OssianProperties.Transform cfg = this.properties.getTransform();
		String model = this.settings.effective(SettingsService.CHAT_MODEL);

		// Everything that can change the output goes into the key: the source text, the prompt
		// and the model. Not the document id — the same file uploaded twice under two names
		// should not be summarised twice. Not the transformation id either, because editing a
		// prompt must miss and renaming one must not.
		String cacheKey = cacheKey(text, transformation.getPrompt(), model);
		Optional<Insight> reusable = reuse(cacheKey);
		if (reusable.isPresent()) {
			Insight previous = reusable.get();
			Insight hit = new Insight();
			hit.setDocumentId(document.getId());
			hit.setTransformationId(transformation.getId());
			hit.setTransformationName(transformation.getName());
			hit.setPromptUsed(transformation.getPrompt());
			hit.setOutput(previous.getOutput());
			hit.setModel(previous.getModel());
			hit.setPasses(previous.getPasses());
			hit.setDurationMs((System.nanoTime() - started) / 1_000_000);
			hit.setCreatedBy(actor);
			hit.setCacheKey(cacheKey);
			hit.setFromCache(true);
			log.debug("transformation '{}' served from cache in {}ms", transformation.getSlug(),
					hit.getDurationMs());
			this.metrics.transformation(transformation.getSlug(), true,
					java.time.Duration.ofMillis(hit.getDurationMs()));
			return this.insights.save(hit);
		}

		List<String> windows = windows(text, Math.max(1000, cfg.getInputBudget()), Math.max(1, cfg.getMaxPasses()));

		String output;
		if (windows.size() == 1) {
			output = call(transformation.getPrompt().replace(CONTENT_TOKEN, windows.get(0)), model);
		}
		else {
			// Map: apply the prompt to each window. Reduce: apply it once more to the collected
			// results, so the final output has the shape the prompt asked for rather than being a
			// concatenation of partial answers.
			List<String> parts = new ArrayList<>(windows.size());
			for (int i = 0; i < windows.size(); i++) {
				parts.add("--- part %d of %d ---%n%s".formatted(i + 1, windows.size(),
						call(transformation.getPrompt().replace(CONTENT_TOKEN, windows.get(i)), model)));
			}
			String combined = String.join("\n\n", parts);
			String reducePrompt = transformation.getPrompt()
				.replace(CONTENT_TOKEN, "The source was too long to read at once. Below are the results of "
						+ "applying this same instruction to each part in order. Merge them into one "
						+ "result that follows the instruction, removing repetition and keeping the "
						+ "order of the original.\n\n" + combined);
			output = call(reducePrompt, model);
		}

		Insight insight = new Insight();
		insight.setDocumentId(document.getId());
		insight.setTransformationId(transformation.getId());
		insight.setTransformationName(transformation.getName());
		insight.setPromptUsed(transformation.getPrompt());
		insight.setOutput(output == null ? "" : output.strip());
		insight.setModel(model);
		insight.setPasses(windows.size());
		insight.setDurationMs((System.nanoTime() - started) / 1_000_000);
		insight.setCreatedBy(actor);
		insight.setCacheKey(cacheKey);
		insight.setFromCache(false);
		Insight saved = this.insights.save(insight);
		this.metrics.transformation(transformation.getSlug(), false,
				java.time.Duration.ofMillis(saved.getDurationMs()));
		remember(cacheKey, saved.getOutput());
		return saved;
	}

	/**
	 * Looks for an identical earlier run, in Redis first and the insights table second.
	 *
	 * <p>Two tiers because they fail differently. Redis is fast and forgettable; the table is
	 * slower and permanent. Checking only Redis would make every restart re-run every
	 * transformation, and checking only the table would give up the millisecond path that makes
	 * repeated requests worth serving at all.
	 */
	private Optional<Insight> reuse(String cacheKey) {
		Cache cache = this.caches.getCache(INSIGHT_CACHE);
		if (cache != null) {
			try {
				String cached = cache.get(cacheKey, String.class);
				if (cached != null && !cached.isBlank()) {
					// Enough of an Insight to copy the answer from. The row that gets saved is
					// the caller's, not this one.
					Insight shell = new Insight();
					shell.setOutput(cached);
					shell.setPasses(1);
					return Optional.of(shell);
				}
			}
			catch (RuntimeException ex) {
				// A cache that is down must slow this down, not break it.
				log.debug("insight cache read failed: {}", ex.toString());
			}
		}
		return this.insights.findFirstByCacheKeyOrderByCreatedAtDesc(cacheKey)
			.map(previous -> {
				remember(cacheKey, previous.getOutput());
				return previous;
			});
	}

	private void remember(String cacheKey, String output) {
		Cache cache = this.caches.getCache(INSIGHT_CACHE);
		if (cache == null || output == null || output.isBlank()) {
			return;
		}
		try {
			cache.put(cacheKey, output);
		}
		catch (RuntimeException ex) {
			log.debug("insight cache write failed: {}", ex.toString());
		}
	}

	/**
	 * The identity of a transformation run: source text, prompt and model.
	 *
	 * <p>Hashed rather than concatenated because the source is a whole document — a key holding
	 * one would be enormous, and holding document text in Redis keys is an awkward thing to have
	 * done on purpose.
	 */
	static String cacheKey(String text, String prompt, String model) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : List.of(text, prompt, model == null ? "" : model)) {
				digest.update(part.getBytes(StandardCharsets.UTF_8));
				// A separator, so that moving a character across the boundary between two parts
				// changes the digest instead of producing the same concatenation.
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}

	private String call(String prompt, String model) {
		return this.chatClient.prompt()
			.options(OpenAiChatOptions.builder()
				.model(model)
				.temperature(this.settings.effectiveDouble(SettingsService.CHAT_TEMPERATURE))
				.maxTokens(this.settings.effectiveInt(SettingsService.CHAT_MAX_TOKENS))
				.build())
			// No system prompt here. The chat one instructs the model to answer only from
			// retrieved context and refuse otherwise, which would make it refuse to summarise.
			.user(prompt)
			.call()
			.content();
	}

	/**
	 * Splits text into windows that fit one call, breaking at paragraphs where possible.
	 *
	 * <p>A window that ends mid-sentence is read as a fragment by the model and produces a
	 * summary that trails off, so the break is moved back to a paragraph boundary when there is
	 * one within reach.
	 */
	static List<String> windows(String text, int budget, int maxPasses) {
		String body = text.strip();
		List<String> out = new ArrayList<>();
		if (body.length() <= budget) {
			out.add(body);
			return out;
		}

		int start = 0;
		while (start < body.length() && out.size() < maxPasses) {
			int hardEnd = Math.min(start + budget, body.length());
			int end = hardEnd;
			if (hardEnd < body.length()) {
				int floor = start + (int) (budget * 0.6);
				int paragraph = body.lastIndexOf("\n\n", hardEnd);
				int sentence = body.lastIndexOf(". ", hardEnd);
				if (paragraph >= floor) {
					end = paragraph;
				}
				else if (sentence >= floor) {
					end = sentence + 1;
				}
			}
			String piece = body.substring(start, end).strip();
			if (!piece.isEmpty()) {
				out.add(piece);
			}
			start = Math.max(end, start + 1);
		}
		return out;
	}

}
