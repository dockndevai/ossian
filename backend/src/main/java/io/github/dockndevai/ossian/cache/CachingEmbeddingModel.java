package io.github.dockndevai.ossian.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * An {@link EmbeddingModel} that remembers what it has already embedded.
 *
 * <p>Embedding is deterministic: the same text through the same model always produces the same
 * vector. That makes it the one part of the pipeline that is safe to cache indefinitely, and the
 * one where caching pays best — re-ingesting an unchanged document, or asking a question someone
 * already asked, costs a Redis round trip instead of a model call.
 *
 * <p>It decorates {@code call(EmbeddingRequest)}, the interface's only real entry point; every
 * other method on {@link EmbeddingModel} is a default that routes through it. Wrapping there
 * rather than at {@code embed(String)} is what makes the cache apply to the vector store's own
 * internal batching, which the application never calls directly.
 *
 * <p>Caching is per text, not per request. A batch of twenty chunks where nineteen are unchanged
 * sends one chunk upstream, not twenty — which is the common case when a document is re-indexed
 * after a small edit.
 *
 * <p>The model name is part of every key. Vectors from two different embedding models are not
 * interchangeable, and serving one where the other was asked for produces retrieval that is
 * subtly, silently wrong rather than broken.
 *
 * <p>Vectors are stored Base64-encoded rather than as JSON arrays. The cache's serializer has
 * default typing enabled, and a bare JSON array of numbers has nowhere to carry a type — it
 * writes cleanly and then fails to read back, so every lookup misses while the cache appears to
 * be full. An opaque string has no such ambiguity, and is a third the size.
 */
public class CachingEmbeddingModel implements EmbeddingModel {

	public static final String CACHE = "embeddings";

	private static final Logger log = LoggerFactory.getLogger(CachingEmbeddingModel.class);

	private final EmbeddingModel delegate;

	/**
	 * Resolved on use rather than injected. This is constructed from a bean post-processor, which
	 * runs before the cache manager is necessarily available; asking for it eagerly would force
	 * it into existence too early and drag half the context with it.
	 */
	private final ObjectProvider<CacheManager> caches;

	private final String defaultModel;

	/** So a broken cache is reported once, not on every embedding. */
	private final AtomicBoolean warned = new AtomicBoolean();

	public CachingEmbeddingModel(EmbeddingModel delegate, ObjectProvider<CacheManager> caches, String defaultModel) {
		this.delegate = delegate;
		this.caches = caches;
		this.defaultModel = (defaultModel == null || defaultModel.isBlank()) ? "unknown" : defaultModel;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		CacheManager manager = this.caches.getIfAvailable();
		Cache cache = (manager == null) ? null : manager.getCache(CACHE);
		List<String> texts = request.getInstructions();
		if (cache == null || texts == null || texts.isEmpty()) {
			return this.delegate.call(request);
		}

		String model = modelOf(request);
		float[][] found = new float[texts.size()][];
		List<String> missingTexts = new ArrayList<>();
		List<Integer> missingAt = new ArrayList<>();

		for (int i = 0; i < texts.size(); i++) {
			float[] hit = read(cache, key(model, texts.get(i)));
			if (hit != null) {
				found[i] = hit;
			}
			else {
				missingTexts.add(texts.get(i));
				missingAt.add(i);
			}
		}

		if (!missingTexts.isEmpty()) {
			EmbeddingResponse fresh = this.delegate
				.call(new EmbeddingRequest(missingTexts, request.getOptions()));
			List<org.springframework.ai.embedding.Embedding> results = fresh.getResults();
			for (int i = 0; i < missingAt.size() && i < results.size(); i++) {
				float[] vector = results.get(i).getOutput();
				found[missingAt.get(i)] = vector;
				write(cache, key(model, missingTexts.get(i)), vector);
			}
			// Every text was a miss, so nothing was assembled from cache: hand back the upstream
			// response untouched rather than rebuilding an equivalent one and losing its metadata.
			if (missingAt.size() == texts.size()) {
				return fresh;
			}
		}
		else if (log.isTraceEnabled()) {
			log.trace("embedding cache: {} of {} served from cache", texts.size(), texts.size());
		}

		List<org.springframework.ai.embedding.Embedding> merged = new ArrayList<>(texts.size());
		for (int i = 0; i < texts.size(); i++) {
			// A miss whose upstream response was short leaves a null; fall back rather than NPE.
			if (found[i] == null) {
				return this.delegate.call(request);
			}
			merged.add(new org.springframework.ai.embedding.Embedding(found[i], i));
		}
		return new EmbeddingResponse(merged, new EmbeddingResponseMetadata());
	}

	@Override
	public float[] embed(Document document) {
		return embed(document.getText());
	}

	@Override
	public int dimensions() {
		return this.delegate.dimensions();
	}

	private String modelOf(EmbeddingRequest request) {
		if (request.getOptions() != null && request.getOptions().getModel() != null
				&& !request.getOptions().getModel().isBlank()) {
			return request.getOptions().getModel();
		}
		return this.defaultModel;
	}

	/**
	 * Hash rather than store the text: passage text can be long, and a Redis key holding a
	 * document's contents is both wasteful and an awkward thing to have lying around.
	 */
	private static String key(String model, String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(model.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}

	private float[] read(Cache cache, String key) {
		try {
			String encoded = cache.get(key, String.class);
			return (encoded == null) ? null : decode(encoded);
		}
		catch (RuntimeException ex) {
			// A cache that is down, or holding a value in an older format, must not stop the
			// request: embedding again is slower, never wrong. But it is reported, because a
			// cache that silently never hits looks exactly like one that is working.
			if (this.warned.compareAndSet(false, true)) {
				log.warn("embedding cache is not returning values; every embedding will be recomputed", ex);
			}
			return null;
		}
	}

	private void write(Cache cache, String key, float[] vector) {
		try {
			cache.put(key, encode(vector));
		}
		catch (RuntimeException ex) {
			if (this.warned.compareAndSet(false, true)) {
				log.warn("embedding cache is not accepting values; embeddings will not be reused", ex);
			}
		}
	}

	static String encode(float[] vector) {
		ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
		for (float f : vector) {
			buffer.putFloat(f);
		}
		return Base64.getEncoder().encodeToString(buffer.array());
	}

	static float[] decode(String encoded) {
		byte[] bytes = Base64.getDecoder().decode(encoded);
		// A truncated value is corrupt, not a short vector; treat it as a miss.
		if (bytes.length == 0 || bytes.length % Float.BYTES != 0) {
			return null;
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		float[] out = new float[bytes.length / Float.BYTES];
		for (int i = 0; i < out.length; i++) {
			out[i] = buffer.getFloat();
		}
		return out;
	}

}
