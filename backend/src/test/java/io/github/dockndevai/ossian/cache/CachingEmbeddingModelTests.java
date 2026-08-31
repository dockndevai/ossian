package io.github.dockndevai.ossian.cache;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A cache that never hits is indistinguishable from one that works: the answers are identical
 * and only the bill differs. These assert on the number of calls that actually reach the model,
 * which is the only thing that tells the two apart.
 */
class CachingEmbeddingModelTests {

	/** Counts what reaches it, and returns a vector derived from the text so hits are checkable. */
	private static final class CountingModel implements EmbeddingModel {

		private final AtomicInteger texts = new AtomicInteger();

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			this.calls.incrementAndGet();
			this.texts.addAndGet(request.getInstructions().size());
			List<Embedding> out = new java.util.ArrayList<>();
			for (int i = 0; i < request.getInstructions().size(); i++) {
				out.add(new Embedding(vectorFor(request.getInstructions().get(i)), i));
			}
			return new EmbeddingResponse(out, new EmbeddingResponseMetadata());
		}

		@Override
		public float[] embed(Document document) {
			return embed(document.getText());
		}

	}

	private static float[] vectorFor(String text) {
		return new float[] { text.length(), text.hashCode() % 1000, 0.5f };
	}

	private static CachingEmbeddingModel wrap(EmbeddingModel delegate, CacheManager manager) {
		ObjectProvider<CacheManager> provider = new ObjectProvider<>() {
			@Override
			public CacheManager getObject() {
				return manager;
			}

			@Override
			public CacheManager getObject(Object... args) {
				return manager;
			}

			@Override
			public CacheManager getIfAvailable() {
				return manager;
			}

			@Override
			public CacheManager getIfUnique() {
				return manager;
			}
		};
		return new CachingEmbeddingModel(delegate, provider, "test-model");
	}

	@Test
	@DisplayName("the same text is embedded once, however many times it is asked for")
	void repeatedTextHitsTheCache() {
		CountingModel upstream = new CountingModel();
		CachingEmbeddingModel model = wrap(upstream, new ConcurrentMapCacheManager("embeddings"));

		float[] first = model.embed("the on-call rotation starts on Wednesday");
		float[] second = model.embed("the on-call rotation starts on Wednesday");
		float[] third = model.embed("the on-call rotation starts on Wednesday");

		assertThat(upstream.calls.get()).isEqualTo(1);
		assertThat(second).isEqualTo(first);
		assertThat(third).isEqualTo(first);
	}

	@Test
	@DisplayName("a batch sends only the texts it has not seen")
	void partialBatchOnlySendsMisses() {
		CountingModel upstream = new CountingModel();
		CachingEmbeddingModel model = wrap(upstream, new ConcurrentMapCacheManager("embeddings"));

		model.embed(List.of("alpha", "beta", "gamma"));
		assertThat(upstream.texts.get()).isEqualTo(3);

		// Re-indexing a document after a small edit: most chunks are unchanged.
		List<float[]> again = model.embed(List.of("alpha", "beta", "delta"));

		assertThat(upstream.texts.get()).isEqualTo(4);
		assertThat(again).hasSize(3);
		assertThat(again.get(0)).isEqualTo(vectorFor("alpha"));
		assertThat(again.get(1)).isEqualTo(vectorFor("beta"));
		assertThat(again.get(2)).isEqualTo(vectorFor("delta"));
	}

	@Test
	@DisplayName("results keep the order of the request when some are cached and some are not")
	void mixedBatchKeepsOrder() {
		CountingModel upstream = new CountingModel();
		CachingEmbeddingModel model = wrap(upstream, new ConcurrentMapCacheManager("embeddings"));

		model.embed("second");
		List<float[]> mixed = model.embed(List.of("first", "second", "third"));

		// Wrong order here would pair every chunk with a neighbour's vector — retrieval would
		// still return results, all of them subtly wrong.
		assertThat(mixed.get(0)).isEqualTo(vectorFor("first"));
		assertThat(mixed.get(1)).isEqualTo(vectorFor("second"));
		assertThat(mixed.get(2)).isEqualTo(vectorFor("third"));
	}

	@Test
	@DisplayName("a broken cache degrades to calling the model, not to failing")
	void brokenCacheStillAnswers() {
		CountingModel upstream = new CountingModel();
		CacheManager exploding = new ConcurrentMapCacheManager("embeddings") {
			@Override
			public org.springframework.cache.Cache getCache(String name) {
				org.springframework.cache.Cache real = super.getCache(name);
				return new org.springframework.cache.Cache() {
					@Override
					public String getName() {
						return real.getName();
					}

					@Override
					public Object getNativeCache() {
						return real.getNativeCache();
					}

					@Override
					public ValueWrapper get(Object key) {
						throw new IllegalStateException("cache is down");
					}

					@Override
					public <T> T get(Object key, Class<T> type) {
						throw new IllegalStateException("cache is down");
					}

					@Override
					public <T> T get(Object key, java.util.concurrent.Callable<T> loader) {
						throw new IllegalStateException("cache is down");
					}

					@Override
					public void put(Object key, Object value) {
						throw new IllegalStateException("cache is down");
					}

					@Override
					public void evict(Object key) {
					}

					@Override
					public void clear() {
					}
				};
			}
		};
		CachingEmbeddingModel model = wrap(upstream, exploding);

		assertThatCode(() -> model.embed("anything")).doesNotThrowAnyException();
		assertThat(model.embed("anything")).isEqualTo(vectorFor("anything"));
	}

	@Test
	@DisplayName("vectors survive the round trip through the stored form")
	void encodingRoundTrips() {
		float[] original = { 0.0f, -1.5f, 3.14159f, Float.MIN_VALUE, Float.MAX_VALUE };
		assertThat(CachingEmbeddingModel.decode(CachingEmbeddingModel.encode(original))).isEqualTo(original);
	}

	@Test
	@DisplayName("a truncated stored value is treated as a miss rather than a short vector")
	void truncatedValueIsAMiss() {
		String encoded = CachingEmbeddingModel.encode(new float[] { 1f, 2f, 3f });
		String truncated = java.util.Base64.getEncoder()
			.encodeToString(java.util.Arrays.copyOf(java.util.Base64.getDecoder().decode(encoded), 7));
		assertThat(CachingEmbeddingModel.decode(truncated)).isNull();
	}

}
