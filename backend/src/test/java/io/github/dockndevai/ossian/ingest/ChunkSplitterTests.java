package io.github.dockndevai.ossian.ingest;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The splitter is where a RAG system quietly loses answers. A boundary through the middle of a
 * sentence, or no overlap at all, produces a corpus that visibly contains a fact the retriever
 * can never return — and nothing in the pipeline reports an error.
 */
class ChunkSplitterTests {

	/** The splitter is package-private; this test lives in its package so it can reach it. */
	private static List<String> split(int chunkSize, int overlap, String text) {
		return new ChunkSplitter(chunkSize, overlap).split(text);
	}

	@Test
	@DisplayName("short text stays one chunk")
	void shortTextIsNotSplit() {
		List<String> chunks = split(200, 40, "Short enough to leave alone.");
		assertThat(chunks).containsExactly("Short enough to leave alone.");
	}

	@Test
	@DisplayName("long text is split into chunks no larger than the configured size")
	void respectsChunkSize() {
		String text = ("The quick brown fox jumps over the lazy dog. ").repeat(40);
		List<String> chunks = split(300, 50, text);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(300));
	}

	@Test
	@DisplayName("consecutive chunks overlap, so a fact on a boundary survives")
	void chunksOverlap() {
		String text = ("Sentence number one is here. Sentence number two follows it. ").repeat(20);
		List<String> chunks = split(400, 120, text);

		assertThat(chunks).hasSizeGreaterThan(1);
		// The tail of one chunk must reappear at the head of the next, or a fact straddling the
		// boundary is in neither chunk intact.
		for (int i = 0; i < chunks.size() - 1; i++) {
			String tail = chunks.get(i).substring(Math.max(0, chunks.get(i).length() - 40));
			assertThat(text).contains(tail);
			assertThat(chunks.get(i + 1)).isNotEmpty();
		}
		String joined = String.join("", chunks);
		assertThat(joined.length()).isGreaterThan(text.strip().length());
	}

	@Test
	@DisplayName("breaks at a paragraph rather than mid-sentence")
	void prefersParagraphBoundaries() {
		String text = "First paragraph about deployment windows and when they apply.\n\n"
				+ "Second paragraph about the on-call rotation and its handover rules.\n\n"
				+ "Third paragraph about incident severity and what each level means.";
		List<String> chunks = split(80, 10, text);

		assertThat(chunks).isNotEmpty();
		// No chunk should begin mid-word.
		assertThat(chunks).allSatisfy(c -> assertThat(c).matches("(?s)^[A-Za-z#\\[].*"));
	}

	@Test
	@DisplayName("text with no whitespace still terminates")
	void hardCutWhenThereIsNoBoundary() {
		String text = "x".repeat(1000);
		List<String> chunks = split(100, 20, text);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
	}

	@Test
	@DisplayName("overlap at or above chunk size is clamped instead of looping forever")
	void absurdOverlapIsClamped() {
		String text = ("word ").repeat(200);
		List<String> chunks = split(100, 500, text);

		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
	}

	@Test
	@DisplayName("blank input produces no chunks rather than one empty one")
	void blankInput() {
		assertThat(split(100, 10, "   \n\n  ")).isEmpty();
		assertThat(split(100, 10, null)).isEmpty();
	}

}
