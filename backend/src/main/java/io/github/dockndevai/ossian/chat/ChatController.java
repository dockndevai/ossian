package io.github.dockndevai.ossian.chat;

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
	 */
	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<String> stream(@Valid @RequestBody Dtos.AskRequest request) {
		return this.rag.askStreaming(request);
	}

}
