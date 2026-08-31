package io.github.dockndevai.ossian.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ask questions of your own documents. */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

	private final RagService rag;

	private final ObjectMapper json = new ObjectMapper();

	public ChatController(RagService rag) {
		this.rag = rag;
	}

	/** Single-shot answer with citations. */
	@PostMapping
	public Dtos.AskResponse ask(@Valid @RequestBody Dtos.AskRequest request) {
		return this.rag.ask(request);
	}

	/**
	 * Streaming answer. Spring MVC adapts a returned {@link Flux} to SSE, so the UI can render
	 * tokens as they arrive rather than waiting for a slow model to finish.
	 *
	 * <p>Each token is emitted as a JSON string rather than raw text. SSE writes {@code data:}
	 * followed by the payload, and a conforming reader strips one leading space from that line —
	 * so a token that legitimately begins with a space arrives without it and the words in the
	 * answer run together. Quoting the token puts the whitespace out of reach of that rule.
	 */
	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> stream(@Valid @RequestBody Dtos.AskRequest request) {
		return this.rag.askStreaming(request).map(this::quote);
	}

	private String quote(String token) {
		try {
			return this.json.writeValueAsString(token);
		}
		catch (JsonProcessingException ex) {
			// A String always serialises; if it somehow does not, dropping the token is better
			// than failing the whole stream mid-answer.
			return "\"\"";
		}
	}

}
