package io.github.dockndevai.ossian.ingest;

import java.util.List;
import java.util.stream.IntStream;

import io.github.dockndevai.ossian.config.OssianProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Batching on tokens rather than on a count of things.
 *
 * <p>The failure this prevents is asymmetric. Too many tokens in one call is rejected by the
 * endpoint and is at least loud; too few is silent, and simply means paying for several round
 * trips where one would have done. A fixed item count produces both, on different documents,
 * from the same configuration.
 */
class EmbeddingBatcherTests {

	private static EmbeddingBatcher batcher(int maxTokens, int maxItems) {
		OssianProperties properties = new OssianProperties();
		properties.getIngest().setEmbeddingBatchTokens(maxTokens);
		properties.getIngest().setEmbeddingBatchSize(maxItems);
		return new EmbeddingBatcher(properties);
	}

	private static Document chunk(String text) {
		return new Document(text);
	}

	/** Roughly n tokens of ordinary prose. */
	private static Document sized(int words) {
		return chunk(String.join(" ", java.util.Collections.nCopies(words, "sentence")));
	}

	@Test
	@DisplayName("small chunks are packed together rather than sent one at a time")
	void packsSmallChunks() {
		List<Document> chunks = IntStream.range(0, 20).mapToObj(i -> sized(5)).toList();

		List<List<Document>> batches = batcher(8000, 100).batch(chunks);

		// Twenty short notes are one request, not twenty.
		assertThat(batches).hasSize(1);
		assertThat(batches.get(0)).hasSize(20);
	}

	@Test
	@DisplayName("large chunks are split so no call exceeds the token budget")
	void splitsOnTokens() {
		List<Document> chunks = IntStream.range(0, 10).mapToObj(i -> sized(200)).toList();

		EmbeddingBatcher batcher = batcher(500, 100);
		List<List<Document>> batches = batcher.batch(chunks);

		assertThat(batches).hasSizeGreaterThan(1);
		// Every batch bar an unavoidable oversized single fits the budget.
		for (List<Document> batch : batches) {
			assertThat(batch).isNotEmpty();
			if (batch.size() > 1) {
				assertThat(batcher.totalTokens(batch)).isLessThanOrEqualTo(500);
			}
		}
	}

	@Test
	@DisplayName("the item cap applies even when the token budget is nowhere near spent")
	void itemCapAlsoApplies() {
		List<Document> chunks = IntStream.range(0, 30).mapToObj(i -> sized(2)).toList();

		List<List<Document>> batches = batcher(100_000, 10).batch(chunks);

		// Some endpoints limit inputs per request regardless of size.
		assertThat(batches).hasSize(3);
		assertThat(batches).allSatisfy(b -> assertThat(b).hasSizeLessThanOrEqualTo(10));
	}

	@Test
	@DisplayName("a chunk larger than the whole budget is sent alone, not dropped")
	void oversizedChunkSurvives() {
		List<Document> chunks = List.of(sized(5), sized(2000), sized(5));

		List<List<Document>> batches = batcher(200, 100).batch(chunks);

		// Silently discarding it would lose text from the middle of a document with nothing to
		// show for it; how large a chunk is remains the splitter's business.
		List<Document> all = batches.stream().flatMap(List::stream).toList();
		assertThat(all).hasSize(3);
		assertThat(batches).anySatisfy(b -> assertThat(b).hasSize(1));
	}

	@Test
	@DisplayName("every chunk appears exactly once, in order")
	void nothingIsLostOrDuplicated() {
		List<Document> chunks = IntStream.range(0, 47).mapToObj(i -> chunk("chunk number " + i)).toList();

		List<Document> flattened = batcher(60, 7).batch(chunks).stream().flatMap(List::stream).toList();

		assertThat(flattened).hasSize(47);
		assertThat(flattened.stream().map(Document::getText).toList())
			.isEqualTo(chunks.stream().map(Document::getText).toList());
	}

	@Test
	@DisplayName("no chunks means no calls, not one empty call")
	void emptyInput() {
		assertThat(batcher(8000, 25).batch(List.of())).isEmpty();
	}

	@Test
	@DisplayName("dense text costs more tokens than prose of the same length")
	void countingIsRealNotCharactersOverFour() {
		EmbeddingBatcher batcher = batcher(8000, 25);
		String prose = "the quick brown fox jumps over the lazy dog and keeps on running";
		String dense = "{\"a\":1,\"b\":[2,3],\"c\":{\"d\":\"e\"},\"f\":null,\"g\":true,\"h\":0.5}";

		int proseTokens = batcher.estimate(chunk(prose));
		int denseTokens = batcher.estimate(chunk(dense));

        // The four-characters-per-token heuristic would call these near-identical. They are not,
		// and a budget set from that heuristic is exceeded exactly on the documents most likely
		// to be large — code, tables and JSON.
		assertThat(prose.length()).isCloseTo(dense.length(), org.assertj.core.data.Offset.offset(12));
		assertThat(denseTokens).isGreaterThan(proseTokens);
	}

}
