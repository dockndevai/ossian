package io.github.dockndevai.ossian.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.ingest.IngestionService;
import io.github.dockndevai.ossian.namespace.NamespaceService;
import io.github.dockndevai.ossian.observability.PipelineMetrics;
import io.github.dockndevai.ossian.settings.SettingsService;
import io.github.dockndevai.ossian.caller.CallerContext;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * Retrieval and answering.
 * <p>
 * Retrieval is always filtered to the caller's tenant, taken from the validated JWT. That filter
 * is not optional and not caller-supplied: it is the whole of the isolation guarantee, because
 * the vector store itself has no notion of ownership.
 */
@Service
public class RagService {

	private static final Logger log = LoggerFactory.getLogger(RagService.class);

	private final VectorStore vectorStore;

	private final ChatClient chatClient;

	private final OssianProperties properties;

	private final CallerContext tenant;

	private final QueryLogRepository queryLog;

	private final SettingsService settings;

	private final NamespaceService namespaces;

	private final PipelineMetrics metrics;

	public RagService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder, OssianProperties properties,
			CallerContext tenant, QueryLogRepository queryLog, SettingsService settings, NamespaceService namespaces, PipelineMetrics metrics) {
		this.vectorStore = vectorStore;
		this.chatClient = chatClientBuilder.build();
		this.properties = properties;
		this.tenant = tenant;
		this.queryLog = queryLog;
		this.settings = settings;
		this.namespaces = namespaces;
		this.metrics = metrics;
	}

	/** Retrieves the chunks that should ground an answer. */
	public List<Document> retrieve(String question, List<String> documentIds, String namespace) {
		// Namespace and document filters are joined with && as they are added; the first one
		// therefore has to omit the leading operator, which is what this tracks.
		StringBuilder filter = new StringBuilder();
		// Asked of the namespace service rather than taken from the parameter, because a confined
		// credential has a namespace even when the request names none. Reading the parameter
		// directly is what let a confined key retrieve the whole corpus.
		this.namespaces.effectiveFilter(namespace)
			.ifPresent(ns -> append(filter, "%s == '%s'".formatted(IngestionService.META_NAMESPACE, ns)));
		if (documentIds != null && !documentIds.isEmpty()) {
			String ids = documentIds.stream().filter(Objects::nonNull).map(id -> "'" + id + "'")
				.reduce((a, b) -> a + "," + b).orElse("");
			if (!ids.isBlank()) {
				append(filter, "%s in [%s]".formatted(IngestionService.META_DOCUMENT, ids));
			}
		}
		int topK = this.settings.effectiveInt(SettingsService.RETRIEVAL_TOP_K);
		int cap = this.settings.effectiveInt(SettingsService.RETRIEVAL_MAX_PASSAGES_PER_DOCUMENT);
		SearchRequest.Builder request = SearchRequest.builder()
			.query(question)
			// Wider than top-k when a cap applies: the documents that fill the slots a capped one
			// gives up have to be among the candidates, or the cap just returns fewer passages.
			.topK((cap > 0) ? Math.min(topK * CANDIDATE_FACTOR, MAX_CANDIDATES) : topK)
			.similarityThreshold(this.settings.effectiveDouble(SettingsService.RETRIEVAL_THRESHOLD));
		// Only when there is something to filter on. An empty expression is not "match
		// everything" to the parser — it is a parse error.
		if (!filter.isEmpty()) {
			request.filterExpression(filter.toString());
		}
		long started = System.nanoTime();
		List<Document> hits = this.vectorStore.similaritySearch(request.build());
		List<Document> found = select((hits == null) ? List.of() : hits, topK, cap,
				this.settings.effectiveDouble(SettingsService.RETRIEVAL_DOMINANCE_MARGIN));
		this.metrics.retrieval(namespace, found.size(), !found.isEmpty(),
				java.time.Duration.ofNanos(System.nanoTime() - started));
		return found;
	}

	/** How many candidates to fetch per slot when a per-document cap may push some out. */
	static final int CANDIDATE_FACTOR = 3;

	/** Upper bound on candidates, however large top-k is set. */
	static final int MAX_CANDIDATES = 100;

	/**
	 * Cuts the candidates to top-k, capping each document's passages unless one document clearly
	 * holds the answer.
	 *
	 * <p>The cap is a diversity constraint, and diversity is only right when relevance is spread.
	 * When scores are flat across several documents the question draws on several, and a long
	 * document taking most of the slots hides the short one that also answers it. When one document
	 * leads the next by a wide margin, the answer is usually a long procedure in that one document,
	 * and capping it drops the half that mattered. So the shape of the candidates decides, per
	 * query, rather than one constant for every question.
	 */
	static List<Document> select(List<Document> candidates, int topK, int cap, double dominanceMargin) {
		List<Document> ranked = candidates.stream()
			.sorted(java.util.Comparator.comparing(Document::getScore,
					java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
			.toList();
		if (cap <= 0 || isDominated(ranked, dominanceMargin)) {
			return ranked.subList(0, Math.min(topK, ranked.size()));
		}
		java.util.Map<String, Integer> taken = new java.util.HashMap<>();
		List<Document> out = new ArrayList<>(topK);
		List<Document> held = new ArrayList<>();
		for (Document d : ranked) {
			if (out.size() == topK) {
				break;
			}
			if (taken.merge(documentKey(d), 1, Integer::sum) <= cap) {
				out.add(d);
			}
			else {
				held.add(d);
			}
		}
		// The cap makes room for other documents; it is not a reason to send the model less. When
		// there are not enough other documents to fill the slots, the held-back passages do.
		for (Document d : held) {
			if (out.size() == topK) {
				break;
			}
			out.add(d);
		}
		out.sort(java.util.Comparator.comparing(Document::getScore,
				java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
		return out;
	}

	/** Whether the best document leads the runner-up by at least the margin. One document leads by default. */
	private static boolean isDominated(List<Document> ranked, double margin) {
		java.util.Map<String, Double> best = new java.util.LinkedHashMap<>();
		for (Document d : ranked) {
			best.putIfAbsent(documentKey(d), (d.getScore() == null) ? 0.0 : d.getScore());
		}
		if (best.size() < 2) {
			return true;
		}
		java.util.Iterator<Double> scores = best.values().iterator();
		return scores.next() - scores.next() >= margin;
	}

	/** The document a chunk belongs to; a chunk without one cannot share and stands alone. */
	private static String documentKey(Document d) {
		Object id = d.getMetadata().get(IngestionService.META_DOCUMENT);
		return (id == null) ? "chunk:" + d.getId() : id.toString();
	}

	/** Retrieval across every namespace, for callers that do not care which one a fact is in. */
	public List<Document> retrieve(String question, List<String> documentIds) {
		return retrieve(question, documentIds, null);
	}

    /** Answers a question, returning the answer with the citations it was grounded in. */
	public Dtos.AskResponse ask(Dtos.AskRequest request) {
		long started = System.nanoTime();
		List<Document> hits = retrieve(request.question(), request.documentIds(), request.namespace());

		if (hits.isEmpty()) {
			// Refuse rather than let the model answer from its own weights. That is the whole
			// point of open-book: an unsupported answer is worse than no answer.
			long ms = (System.nanoTime() - started) / 1_000_000;
			record(request.question(), 0, null, false, ms, null, null, request.namespace());
			return new Dtos.AskResponse(
					"I could not find anything about that in your documents. "
							+ "Try rephrasing, or upload a document that covers it.",
					List.of(), false, ms, null, null);
		}

		List<Dtos.Citation> citations = toCitations(hits);
		String answer = this.chatClient.prompt()
			.options(chatOptions())
			.system(systemPrompt())
			.user(buildUserMessage(request.question(), hits))
			.call()
			.content();

		long ms = (System.nanoTime() - started) / 1_000_000;
		Double top = citations.isEmpty() ? null : citations.get(0).score();
		record(request.question(), hits.size(), top, true, ms, null, null, request.namespace());
		return new Dtos.AskResponse(answer, citations, true, ms, null, null);
	}

	/** Token-by-token answer for the chat UI. */
	public Flux<String> askStreaming(Dtos.AskRequest request) {
		List<Document> hits = retrieve(request.question(), request.documentIds(), request.namespace());
		if (hits.isEmpty()) {
			record(request.question(), 0, null, false, 0L, null, null, request.namespace());
			return Flux.just("I could not find anything about that in your documents.");
		}
		record(request.question(), hits.size(), scoreOf(hits.get(0)), true, 0L, null, null, request.namespace());
		return this.chatClient.prompt()
			.options(chatOptions())
			.system(systemPrompt())
			.user(buildUserMessage(request.question(), hits))
			.stream()
			.content();
	}

	/**
	 * Model options for this request, from the tenant's settings rather than the file.
	 *
	 * <p>Applied per call rather than baked into the ChatClient at startup, which is what lets a
	 * model or temperature change take effect without a restart — and lets two tenants sharing
	 * this process use different models.
	 */
	private OpenAiChatOptions chatOptions() {
		return OpenAiChatOptions.builder()
			.model(this.settings.effective(SettingsService.CHAT_MODEL))
			.temperature(this.settings.effectiveDouble(SettingsService.CHAT_TEMPERATURE))
			.maxTokens(this.settings.effectiveInt(SettingsService.CHAT_MAX_TOKENS))
			.build();
	}

	/** The system prompt in force: the tenant's override, or the one from the file. */
	private String systemPrompt() {
		return this.settings.effective(SettingsService.CHAT_SYSTEM_PROMPT);
	}


	/**
	 * Numbered context blocks, one per document. The numbering is what lets the model emit [1]/[2]
	 * and the UI map those back to real documents, so a citation can be clicked and checked.
	 */
	static String buildUserMessage(String question, List<Document> hits) {
		StringBuilder sb = new StringBuilder("CONTEXT:\n\n");
		List<Source> sources = group(hits);
		for (int i = 0; i < sources.size(); i++) {
			Source s = sources.get(i);
			sb.append("[").append(i + 1).append("] ").append(s.filename()).append("\n")
				.append(String.join(PASSAGE_BREAK, s.passages())).append("\n\n");
		}
		return sb.append("QUESTION: ").append(question).toString();
	}

	static List<Dtos.Citation> toCitations(List<Document> hits) {
		List<Source> sources = group(hits);
		List<Dtos.Citation> out = new ArrayList<>(sources.size());
		for (int i = 0; i < sources.size(); i++) {
			Source s = sources.get(i);
			String excerpt = s.passages().stream()
				.map(p -> p.substring(0, Math.min(p.length(), 400)))
				.reduce((a, b) -> a + PASSAGE_BREAK + b)
				.orElse("");
			out.add(new Dtos.Citation(i + 1, s.documentId(), s.filename(), s.score(), excerpt));
		}
		return out;
	}

	/** Separates two passages of one document, so they read as excerpts rather than as prose. */
	private static final String PASSAGE_BREAK = "\n…\n";

	/** One document's retrieved passages, under a single citation number. */
	record Source(String documentId, String filename, Double score, List<String> passages) {
	}

	/**
	 * Collapses chunks of the same document into one source, ordered by its best-scoring chunk.
	 *
	 * <p>Numbered per chunk, three passages of one handbook arrive as [1], [2] and [3] — which the
	 * model, and the person reading the answer, take as three sources agreeing. It is one source
	 * said three times, and an answer "supported by" it is no better supported than by [1] alone.
	 * Grouping keeps every passage and removes the false corroboration.
	 */
	static List<Source> group(List<Document> hits) {
		java.util.Map<String, Source> byDocument = new java.util.LinkedHashMap<>();
		for (Document d : hits) {
			Object documentId = d.getMetadata().get(IngestionService.META_DOCUMENT);
			// A chunk without a document id cannot be shown to share one, so it stands alone.
			String key = (documentId == null) ? "chunk:" + d.getId() : documentId.toString();
			String text = (d.getText() == null) ? "" : d.getText();
			Source existing = byDocument.get(key);
			if (existing == null) {
				List<String> passages = new ArrayList<>();
				passages.add(text);
				byDocument.put(key, new Source(String.valueOf(documentId),
						String.valueOf(d.getMetadata().getOrDefault(IngestionService.META_FILENAME, "document")),
						d.getScore(), passages));
			}
			else {
				existing.passages().add(text);
				if (d.getScore() != null && (existing.score() == null || d.getScore() > existing.score())) {
					byDocument.put(key, new Source(existing.documentId(), existing.filename(), d.getScore(),
							existing.passages()));
				}
			}
		}
		// A document ranks by its best chunk. The store returns hits best first, but that is its
		// contract rather than ours, so sort rather than assume it.
		return byDocument.values().stream()
			.sorted(java.util.Comparator.comparing(Source::score,
					java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
			.toList();
	}

	private Double scoreOf(Document d) {
		return d.getScore();
	}

	private void record(String question, int chunks, Double topScore, boolean answered, long latencyMs,
			Integer promptTokens, Integer completionTokens) {
		record(question, chunks, topScore, answered, latencyMs, promptTokens, completionTokens, null);
	}

	private void record(String question, int chunks, Double topScore, boolean answered, long latencyMs,
			Integer promptTokens, Integer completionTokens, String namespace) {
		try {
			QueryLog entry = new QueryLog();
			entry.setSubject(this.tenant.subject());
			entry.setQuestion(question.substring(0, Math.min(question.length(), 2000)));
			entry.setNamespace(namespace);
			entry.setChunksRetrieved(chunks);
			entry.setTopScore(topScore);
			entry.setAnswered(answered);
			entry.setLatencyMs(latencyMs);
			entry.setPromptTokens(promptTokens);
			entry.setCompletionTokens(completionTokens);
			this.queryLog.save(entry);
		}
		catch (RuntimeException ex) {
			// Telemetry must never break the answer path.
			log.warn("Could not record query log", ex);
		}
	}


	/** Joins filter clauses with &&, without a leading operator on the first. */
	private static void append(StringBuilder filter, String clause) {
		if (!filter.isEmpty()) {
			filter.append(" && ");
		}
		filter.append(clause);
	}

}
