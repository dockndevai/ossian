package io.github.dockndevai.ossian.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.ingest.IngestionService;
import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.ai.chat.client.ChatClient;
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

	private final TenantContext tenant;

	private final QueryLogRepository queryLog;

	public RagService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder, OssianProperties properties,
			TenantContext tenant, QueryLogRepository queryLog) {
		this.vectorStore = vectorStore;
		this.chatClient = chatClientBuilder.build();
		this.properties = properties;
		this.tenant = tenant;
		this.queryLog = queryLog;
	}

	/** Retrieves the chunks that should ground an answer, scoped to the tenant. */
	public List<Document> retrieve(String question, List<String> documentIds) {
		OssianProperties.Retrieval cfg = this.properties.getRetrieval();
		StringBuilder filter = new StringBuilder(
				"%s == '%s'".formatted(IngestionService.META_TENANT, this.tenant.tenantId()));
		if (documentIds != null && !documentIds.isEmpty()) {
			String ids = documentIds.stream().filter(Objects::nonNull).map(id -> "'" + id + "'")
				.reduce((a, b) -> a + "," + b).orElse("");
			if (!ids.isBlank()) {
				filter.append(" && %s in [%s]".formatted(IngestionService.META_DOCUMENT, ids));
			}
		}
		SearchRequest request = SearchRequest.builder()
			.query(question)
			.topK(cfg.getTopK())
			.similarityThreshold(cfg.getSimilarityThreshold())
			.filterExpression(filter.toString())
			.build();
		List<Document> hits = this.vectorStore.similaritySearch(request);
		return (hits == null) ? List.of() : hits;
	}

    /** Answers a question, returning the answer with the citations it was grounded in. */
	public Dtos.AskResponse ask(Dtos.AskRequest request) {
		long started = System.nanoTime();
		List<Document> hits = retrieve(request.question(), request.documentIds());

		if (hits.isEmpty()) {
			// Refuse rather than let the model answer from its own weights. That is the whole
			// point of open-book: an unsupported answer is worse than no answer.
			long ms = (System.nanoTime() - started) / 1_000_000;
			record(request.question(), 0, null, false, ms, null, null);
			return new Dtos.AskResponse(
					"I could not find anything about that in your documents. "
							+ "Try rephrasing, or upload a document that covers it.",
					List.of(), false, ms, null, null);
		}

		List<Dtos.Citation> citations = toCitations(hits);
		String answer = this.chatClient.prompt()
			.system(this.properties.getChat().getSystemPrompt())
			.user(buildUserMessage(request.question(), hits))
			.call()
			.content();

		long ms = (System.nanoTime() - started) / 1_000_000;
		Double top = citations.isEmpty() ? null : citations.get(0).score();
		record(request.question(), hits.size(), top, true, ms, null, null);
		return new Dtos.AskResponse(answer, citations, true, ms, null, null);
	}

	/** Token-by-token answer for the chat UI. */
	public Flux<String> askStreaming(Dtos.AskRequest request) {
		List<Document> hits = retrieve(request.question(), request.documentIds());
		if (hits.isEmpty()) {
			record(request.question(), 0, null, false, 0L, null, null);
			return Flux.just("I could not find anything about that in your documents.");
		}
		record(request.question(), hits.size(), scoreOf(hits.get(0)), true, 0L, null, null);
		return this.chatClient.prompt()
			.system(this.properties.getChat().getSystemPrompt())
			.user(buildUserMessage(request.question(), hits))
			.stream()
			.content();
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
		try {
			QueryLog entry = new QueryLog();
			entry.setTenantId(this.tenant.tenantId());
			entry.setSubject(this.tenant.subject());
			entry.setQuestion(question.substring(0, Math.min(question.length(), 2000)));
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

}
