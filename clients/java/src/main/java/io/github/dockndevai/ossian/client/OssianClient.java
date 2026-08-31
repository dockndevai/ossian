package io.github.dockndevai.ossian.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

/**
 * A small client for feeding Ossian from another service.
 *
 * <p>The interesting case is not the one-off upload; it is a pipeline keeping a corpus in step
 * with a system of record. That is what {@link #upsert} and {@link #delete} are for: they carry
 * a caller-assigned event id, so a redelivery after a crash is recognised rather than producing
 * a second copy of the same document.
 *
 * <p>Deliberately built on {@code java.net.http} with no framework dependency, because the
 * services that need to feed a corpus are rarely the ones that share your Spring version.
 *
 * <p>Tokens are supplied by a {@link Supplier} rather than a string. A long-running importer
 * outlives any access token it started with, and a client that captured one at construction
 * would work in testing and start failing in production some minutes after deployment.
 */
public final class OssianClient implements AutoCloseable {

	/** The outcome of one event. */
	public record EventResult(String eventId, String status, String documentId, String message) {

		/** True when the server had already seen this event id and did nothing. */
		public boolean duplicate() {
			return "DUPLICATE".equals(this.status);
		}

		public boolean failed() {
			return "FAILED".equals(this.status);
		}
	}

	/** An answer, with the passages it was grounded in. */
	public record Answer(String answer, List<Citation> citations, boolean answeredFromContext, long latencyMs) {
	}

	public record Citation(int index, String documentId, String filename, Double score, String excerpt) {
	}

	/** Raised for any non-success response, carrying the status so callers can retry sensibly. */
	public static class OssianException extends RuntimeException {

		private final int status;

		public OssianException(int status, String message) {
			super(message);
			this.status = status;
		}

		public int status() {
			return this.status;
		}

		/** Whether retrying the same request could plausibly succeed. */
		public boolean retryable() {
			return this.status == 429 || this.status >= 500;
		}
	}

	private final URI baseUri;

	private final Supplier<String> tokens;

	private final HttpClient http;

	private final ObjectMapper json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

	public OssianClient(String baseUrl, Supplier<String> tokens) {
		this(baseUrl, tokens, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
	}

	public OssianClient(String baseUrl, Supplier<String> tokens, HttpClient http) {
		this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
		this.tokens = tokens;
		this.http = http;
	}

	/**
	 * Creates or replaces a document, addressed by its identity in your system.
	 *
	 * @param eventId unique per tenant; the same id twice is a no-op, so use the source
	 * system's change id rather than a fresh UUID per attempt
	 */
	public EventResult upsert(String eventId, String externalId, String text, String namespace, String source,
			String filename) {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("eventId", eventId);
		body.put("operation", "UPSERT");
		body.put("externalId", externalId);
		body.put("namespace", namespace);
		body.put("source", source);
		body.put("filename", filename);
		body.put("text", text);
		return post("/api/events/documents", body, EventResult.class);
	}

	/** The same, for a document Ossian has to parse: PDF, DOCX, HTML. */
	public EventResult upsertBinary(String eventId, String externalId, byte[] content, String contentType,
			String namespace, String source, String filename) {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("eventId", eventId);
		body.put("operation", "UPSERT");
		body.put("externalId", externalId);
		body.put("namespace", namespace);
		body.put("source", source);
		body.put("filename", filename);
		body.put("contentType", contentType);
		body.put("contentBase64", Base64.getEncoder().encodeToString(content));
		return post("/api/events/documents", body, EventResult.class);
	}

	/** Removes a document. Deleting something that is not there succeeds: the end state holds. */
	public EventResult delete(String eventId, String externalId, String namespace, String source) {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("eventId", eventId);
		body.put("operation", "DELETE");
		body.put("externalId", externalId);
		body.put("namespace", namespace);
		body.put("source", source);
		return post("/api/events/documents", body, EventResult.class);
	}

	/**
	 * Sends many changes at once.
	 *
	 * <p>A batch is not a transaction. Each event is judged on its own and the returned list
	 * reports per event, so one malformed record does not send the rest back to be redelivered.
	 * Callers should check every result rather than only the response status.
	 */
	public List<EventResult> send(List<java.util.Map<String, Object>> events) {
		String payload = write(java.util.Map.of("events", events));
		HttpResponse<String> response = exchange("/api/events/documents/batch", "POST", payload);
		try {
			CollectionType type = this.json.getTypeFactory().constructCollectionType(List.class, EventResult.class);
			return this.json.readValue(response.body(), type);
		}
		catch (IOException ex) {
			throw new OssianException(response.statusCode(), "Could not read batch response: " + ex.getMessage());
		}
	}

	/** Asks a question, optionally within one namespace. */
	public Answer ask(String question, String namespace) {
		var body = new java.util.LinkedHashMap<String, Object>();
		body.put("question", question);
		body.put("namespace", namespace);
		return post("/api/chat", body, Answer.class);
	}

	private <T> T post(String path, Object body, Class<T> type) {
		HttpResponse<String> response = exchange(path, "POST", write(body));
		try {
			return this.json.readValue(response.body(), type);
		}
		catch (IOException ex) {
			throw new OssianException(response.statusCode(), "Could not read response: " + ex.getMessage());
		}
	}

	private HttpResponse<String> exchange(String path, String method, String payload) {
		HttpRequest request = HttpRequest.newBuilder(this.baseUri.resolve(path))
			// Read afresh on every call: an importer outlives the token it started with.
			.header("Authorization", "Bearer " + this.tokens.get())
			.header("Content-Type", "application/json")
			.timeout(Duration.ofSeconds(60))
			.method(method, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
			.build();
		try {
			HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 300) {
				throw new OssianException(response.statusCode(), summarise(response));
			}
			return response;
		}
		catch (IOException ex) {
			throw new OssianException(0, "Request to " + path + " failed: " + ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new OssianException(0, "Interrupted calling " + path);
		}
	}

	private String summarise(HttpResponse<String> response) {
		String body = response.body();
		if (body == null || body.isBlank()) {
			return "HTTP " + response.statusCode();
		}
		return "HTTP " + response.statusCode() + ": " + (body.length() > 500 ? body.substring(0, 500) + "…" : body);
	}

	private String write(Object value) {
		try {
			return this.json.writeValueAsString(value);
		}
		catch (IOException ex) {
			throw new IllegalArgumentException("Could not serialise request body", ex);
		}
	}

	/** Convenience for assembling a batch without a map literal at every call site. */
	public static java.util.Map<String, Object> event(String eventId, String operation, String externalId,
			String namespace, String text) {
		var map = new java.util.LinkedHashMap<String, Object>();
		map.put("eventId", eventId);
		map.put("operation", operation);
		map.put("externalId", externalId);
		map.put("namespace", namespace);
		if (text != null) {
			map.put("text", text);
		}
		return map;
	}

	/** Splits a long list into batches the server will accept. */
	public static List<List<java.util.Map<String, Object>>> partition(List<java.util.Map<String, Object>> events,
			int size) {
		List<List<java.util.Map<String, Object>>> out = new ArrayList<>();
		int limit = Math.min(Math.max(size, 1), 500);
		for (int i = 0; i < events.size(); i += limit) {
			out.add(events.subList(i, Math.min(events.size(), i + limit)));
		}
		return out;
	}

	@Override
	public void close() {
		// HttpClient holds no resources that need explicit release on this JDK, but implementing
		// AutoCloseable keeps call sites idiomatic and leaves room to change that.
	}

}
