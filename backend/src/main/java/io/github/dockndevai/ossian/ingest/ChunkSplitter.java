package io.github.dockndevai.ossian.ingest;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;

/**
 * Splits documents into overlapping character windows.
 *
 * <p>Spring AI ships {@code TokenTextSplitter}, which counts tokens and has no overlap at all.
 * Both matter here. Without overlap a fact that straddles a boundary is in neither chunk well
 * enough to retrieve — the classic symptom is a document that visibly contains the answer while
 * the retriever never returns it. And a token budget is the wrong unit to expose in config,
 * because the number of characters it corresponds to changes with the tokenizer.
 *
 * <p>Boundaries are chosen by preference: paragraph break, then sentence end, then word break,
 * then a hard cut. Cutting mid-sentence produces chunks that embed badly, since the embedding
 * of half a sentence is not close to the embedding of the question it answers.
 */
final class ChunkSplitter {

	/** How far back from the target the splitter will look for a natural boundary. */
	private static final double LOOKBACK = 0.35;

	private final int chunkSize;

	private final int overlap;

	ChunkSplitter(int chunkSize, int overlap) {
		if (chunkSize <= 0) {
			throw new IllegalArgumentException("chunkSize must be positive");
		}
		// Overlap at or above the chunk size cannot make progress, so clamp rather than hang.
		this.chunkSize = chunkSize;
		this.overlap = Math.max(0, Math.min(overlap, chunkSize / 2));
	}

	List<Document> apply(List<Document> documents) {
		List<Document> out = new ArrayList<>();
		for (Document doc : documents) {
			for (String piece : split(doc.getText())) {
				// Carry the reader's metadata (page numbers, source) onto every chunk; ingestion
				// adds the tenant and document ids afterwards.
				out.add(new Document(piece, doc.getMetadata()));
			}
		}
		return out;
	}

	List<String> split(String text) {
		List<String> chunks = new ArrayList<>();
		if (text == null) {
			return chunks;
		}
		String body = text.strip();
		if (body.isEmpty()) {
			return chunks;
		}
		if (body.length() <= this.chunkSize) {
			chunks.add(body);
			return chunks;
		}

		int start = 0;
		while (start < body.length()) {
			int hardEnd = Math.min(start + this.chunkSize, body.length());
			int end = (hardEnd == body.length()) ? hardEnd : boundary(body, start, hardEnd);

			String piece = body.substring(start, end).strip();
			if (!piece.isEmpty()) {
				chunks.add(piece);
			}
			if (end >= body.length()) {
				break;
			}
			int next = end - this.overlap;
			// Always move forward, whatever the boundary search returned.
			start = Math.max(next, start + 1);
		}
		return chunks;
	}

	/** The best place to break at or before {@code hardEnd}, never before {@code floor}. */
	private int boundary(String text, int start, int hardEnd) {
		int floor = start + (int) (this.chunkSize * (1 - LOOKBACK));
		if (floor <= start) {
			floor = start + 1;
		}

		int paragraph = text.lastIndexOf("\n\n", hardEnd);
		if (paragraph >= floor) {
			return paragraph;
		}
		int sentence = lastSentenceEnd(text, floor, hardEnd);
		if (sentence >= floor) {
			return sentence;
		}
		int space = text.lastIndexOf(' ', hardEnd);
		if (space >= floor) {
			return space;
		}
		return hardEnd;
	}

	private int lastSentenceEnd(String text, int floor, int hardEnd) {
		for (int i = hardEnd - 1; i >= floor; i--) {
			char c = text.charAt(i);
			if ((c == '.' || c == '!' || c == '?' || c == '\n')
					&& (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
				return i + 1;
			}
		}
		return -1;
	}

}
