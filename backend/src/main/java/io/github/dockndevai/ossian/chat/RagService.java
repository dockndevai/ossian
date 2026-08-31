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
		SearchRequest.Builder request = SearchRequest.builder()
			.query(question)
			.topK(this.settings.effectiveInt(SettingsService.RETRIEVAL_TOP_K))
			.similarityThreshold(this.settings.effectiveDouble(SettingsService.RETRIEVAL_THRESHOLD));
		// Only when there is something to filter on. An empty expression is not "match
		// everything" to the parser — it is a parse error.
		if (!filter.isEmpty()) {
			request.filterExpression(filter.toString());
		}
		long started = System.nanoTime();
		List<Document> hits = this.vectorStore.similaritySearch(request.build());
		List<Document> found = (hits == null) ? List.of() : hits;
		this.metrics.retrieval(namespace, found.size(), !found.isEmpty(),
				java.time.Duration.ofNanos(System.nanoTime() - started));
		return found;
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
	 * Numbered context blocks. The numbering is what lets the model emit [1]/[2] and the UI map
	 * those back to real documents, so a citation can be clicked and checked.
	 */
	private String buildUserMessage(String question, List<Document> hits) {
		StringBuilder sb = new StringBuilder("CONTEXT:\n\n");
		for (int i = 0; i < hits.size(); i++) {
			Document d = hits.get(i);
			sb.append("[").append(i + 1).append("] ")
				.append(d.getMetadata().getOrDefault(IngestionService.META_FILENAME, "document"))
				.append("\n").append(d.getText()).append("\n\n");
		}
		return sb.append("QUESTION: ").append(question).toString();
	}

	private List<Dtos.Citation> toCitations(List<Document> hits) {
		List<Dtos.Citation> out = new ArrayList<>(hits.size());
		for (int i = 0; i < hits.size(); i++) {
			Document d = hits.get(i);
			String text = d.getText() == null ? "" : d.getText();
			out.add(new Dtos.Citation(i + 1,
					String.valueOf(d.getMetadata().get(IngestionService.META_DOCUMENT)),
					String.valueOf(d.getMetadata().getOrDefault(IngestionService.META_FILENAME, "document")),
					scoreOf(d),
					text.substring(0, Math.min(text.length(), 400))));
		}
		return out;
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
