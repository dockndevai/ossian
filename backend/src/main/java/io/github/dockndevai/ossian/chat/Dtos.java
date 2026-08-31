package io.github.dockndevai.ossian.chat;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the chat API. */
public final class Dtos {

	private Dtos() {
	}

	public record AskRequest(@NotBlank @Size(max = 4000) String question,
			/** Optional: restrict retrieval to these document ids. */
			List<String> documentIds) {
	}

	/**
	 * A retrieved chunk, surfaced to the UI so an answer can be checked against its source.
	 * An open-book answer without a visible source is indistinguishable from a guess.
	 */
	public record Citation(int index, String documentId, String filename, Double score, String excerpt) {
	}

	public record AskResponse(String answer, List<Citation> citations, boolean answeredFromContext, long latencyMs,
			Integer promptTokens, Integer completionTokens) {
	}

}
