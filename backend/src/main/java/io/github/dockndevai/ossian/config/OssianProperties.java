package io.github.dockndevai.ossian.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything tunable about ingestion, retrieval and answering, under {@code ossian}. */
@ConfigurationProperties(OssianProperties.PREFIX)
public class OssianProperties {

	public static final String PREFIX = "ossian";

	private Ingest ingest = new Ingest();

	private Retrieval retrieval = new Retrieval();

	private Chat chat = new Chat();

	private Cors cors = new Cors();

	private Transform transform = new Transform();

	private Fetch fetch = new Fetch();

	private RateLimit rateLimit = new RateLimit();

	public Ingest getIngest() {
		return this.ingest;
	}

	public void setIngest(Ingest ingest) {
		this.ingest = ingest;
	}

	public Retrieval getRetrieval() {
		return this.retrieval;
	}

	public void setRetrieval(Retrieval retrieval) {
		this.retrieval = retrieval;
	}

	public Chat getChat() {
		return this.chat;
	}

	public void setChat(Chat chat) {
		this.chat = chat;
	}

	public Transform getTransform() {
		return this.transform;
	}

	public void setTransform(Transform transform) {
		this.transform = transform;
	}

	public Fetch getFetch() {
		return this.fetch;
	}

	public void setFetch(Fetch fetch) {
		this.fetch = fetch;
	}

	public RateLimit getRateLimit() {
		return this.rateLimit;
	}

	public void setRateLimit(RateLimit rateLimit) {
		this.rateLimit = rateLimit;
	}

	public Cors getCors() {
		return this.cors;
	}

	public void setCors(Cors cors) {
		this.cors = cors;
	}

	public static class Ingest {

		/**
		 * Characters per chunk. Too small and a chunk loses the context that makes it
		 * answerable; too large and the embedding blurs across several ideas and retrieval
		 * gets vague.
		 */
		private int chunkSize = 1200;

		/** Characters repeated between adjacent chunks so a fact split across a boundary is still findable. */
		private int chunkOverlap = 200;

		/** Reject uploads above this size, in bytes. */
		private long maxFileSize = 25L * 1024 * 1024;

		/** How many chunks to embed per call to the model. */
		/**
		 * Hard cap on items per embedding call, alongside the token budget below. Some endpoints
		 * limit inputs per request regardless of size, and a thousand one-token chunks would
		 * satisfy any token budget while breaking that.
		 */
		private int embeddingBatchSize = 25;

		/**
		 * Token budget per embedding call.
		 *
		 * <p>The bound that actually matters: embedding endpoints reject on total tokens, so a
		 * fixed item count is the wrong unit — twenty-five short notes and twenty-five long
		 * passages are the same number and wildly different requests.
		 *
		 * <p>8,000 sits under the common 8,192 limit with room for the request envelope.
		 */
		private int embeddingBatchTokens = 8000;

		/**
		 * Ceiling on embedding tokens per minute across all ingestion.
		 *
		 * <p>The HTTP rate limiter counts requests, which does not describe this at all: one
		 * upload is one request and can be a million tokens of embedding. Zero disables it.
		 */
		private int embeddingTokensPerMinute = 200_000;

		/** How many documents may be ingested at once. Bounded so a bulk import cannot starve
		 * the request threads or the connection pool. */
		private int concurrency = 3;

		/** How long a job waits for embedding budget before failing with a reason rather than
		 * holding a thread indefinitely. */
		private int maxThrottleWaitSeconds = 300;

		public int getChunkSize() {
			return this.chunkSize;
		}

		public void setChunkSize(int chunkSize) {
			this.chunkSize = chunkSize;
		}

		public int getChunkOverlap() {
			return this.chunkOverlap;
		}

		public void setChunkOverlap(int chunkOverlap) {
			this.chunkOverlap = chunkOverlap;
		}

		public long getMaxFileSize() {
			return this.maxFileSize;
		}

		public void setMaxFileSize(long maxFileSize) {
			this.maxFileSize = maxFileSize;
		}

		public int getEmbeddingBatchSize() {
			return this.embeddingBatchSize;
		}

		public void setEmbeddingBatchSize(int embeddingBatchSize) {
			this.embeddingBatchSize = embeddingBatchSize;
		}

		public int getEmbeddingBatchTokens() {
			return this.embeddingBatchTokens;
		}

		public void setEmbeddingBatchTokens(int embeddingBatchTokens) {
			this.embeddingBatchTokens = embeddingBatchTokens;
		}

		public int getEmbeddingTokensPerMinute() {
			return this.embeddingTokensPerMinute;
		}

		public void setEmbeddingTokensPerMinute(int embeddingTokensPerMinute) {
			this.embeddingTokensPerMinute = embeddingTokensPerMinute;
		}

		public int getConcurrency() {
			return this.concurrency;
		}

		public void setConcurrency(int concurrency) {
			this.concurrency = concurrency;
		}

		public int getMaxThrottleWaitSeconds() {
			return this.maxThrottleWaitSeconds;
		}

		public void setMaxThrottleWaitSeconds(int maxThrottleWaitSeconds) {
			this.maxThrottleWaitSeconds = maxThrottleWaitSeconds;
		}

	}

	public static class Retrieval {

		/** Chunks pulled from the vector store per question. */
		private int topK = 6;

		/**
		 * Minimum cosine similarity for a chunk to be used. Below this, a chunk is more likely
		 * to mislead the model than help it, and answering "not in the documents" is better.
		 */
		private double similarityThreshold = 0.5;

		/** Cache retrieval results for identical question + tenant for this many seconds. */
		private long cacheSeconds = 300;

		public int getTopK() {
			return this.topK;
		}

		public void setTopK(int topK) {
			this.topK = topK;
		}

		public double getSimilarityThreshold() {
			return this.similarityThreshold;
		}

		public void setSimilarityThreshold(double similarityThreshold) {
			this.similarityThreshold = similarityThreshold;
		}

		public long getCacheSeconds() {
			return this.cacheSeconds;
		}

		public void setCacheSeconds(long cacheSeconds) {
			this.cacheSeconds = cacheSeconds;
		}

	}

	public static class Chat {

		/** Model id sent upstream. Must match a model your gateway routes. */
		private String model = "qwen2.5:0.5b";

		private Double temperature = 0.1;

		private Integer maxTokens = 800;

		/**
		 * The instruction wrapped around retrieved context. Deliberately tells the model to
		 * refuse rather than guess: an open-book system that invents citations is worse than
		 * one that says it does not know.
		 */
		private String systemPrompt = """
				You answer strictly from the CONTEXT below, which comes from the user's own documents.

				Rules:
				- If the context does not contain the answer, say so plainly. Do not use outside knowledge.
				- Cite the sources you used as [1], [2] matching the numbered context blocks.
				- Quote exact figures, versions and identifiers rather than paraphrasing them.
				- If the context conflicts with itself, say which sources disagree.
				""";

		public String getModel() {
			return this.model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public Double getTemperature() {
			return this.temperature;
		}

		public void setTemperature(Double temperature) {
			this.temperature = temperature;
		}

		public Integer getMaxTokens() {
			return this.maxTokens;
		}

		public void setMaxTokens(Integer maxTokens) {
			this.maxTokens = maxTokens;
		}

		public String getSystemPrompt() {
			return this.systemPrompt;
		}

		public void setSystemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
		}

	}

	/** Running a prompt over a whole source, rather than over retrieved passages. */
	public static class Transform {

		/**
		 * How much document text goes into one model call, in characters.
		 *
		 * <p>Beyond this the document is read in windows and the results combined, because the
		 * alternative — silently truncating — produces a summary of the first few pages that
		 * looks exactly like a summary of the whole thing.
		 */
		private int inputBudget = 12000;

		/** Ceiling on how many windows one run will make, so a huge file cannot run away. */
		private int maxPasses = 12;

		public int getInputBudget() {
			return this.inputBudget;
		}

		public void setInputBudget(int inputBudget) {
			this.inputBudget = inputBudget;
		}

		public int getMaxPasses() {
			return this.maxPasses;
		}

		public void setMaxPasses(int maxPasses) {
			this.maxPasses = maxPasses;
		}

	}

	public static class Cors {

		private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

		public List<String> getAllowedOrigins() {
			return this.allowedOrigins;
		}

		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}

	}


	/** Fetching a source the user gave as a URL. */
	public static class Fetch {

		/**
		 * Whether the server may fetch private, loopback and link-local addresses.
		 *
		 * <p>False everywhere it matters. Turning it on makes the service an HTTP client that
		 * anyone signed in can point at the network it sits inside — including the cloud metadata
		 * endpoint, which hands out credentials to whatever asks from within the instance. The
		 * option exists because reaching an internal wiki from a laptop is a real need, and a
		 * switch someone turns on deliberately is safer than one they work around.
		 */
		private boolean allowPrivateAddresses = false;

		/** Hard ceiling on the response body. Read, not trusted from Content-Length. */
		private long maxBytes = 10_485_760;

		private int connectTimeoutSeconds = 10;

		private int readTimeoutSeconds = 20;

		/** Identifies this service to the sites it fetches, so operators can see who called. */
		private String userAgent = "Ossian/0.1 (+https://github.com/dockndevai/ossian)";

		public boolean isAllowPrivateAddresses() {
			return this.allowPrivateAddresses;
		}

		public void setAllowPrivateAddresses(boolean allowPrivateAddresses) {
			this.allowPrivateAddresses = allowPrivateAddresses;
		}

		public long getMaxBytes() {
			return this.maxBytes;
		}

		public void setMaxBytes(long maxBytes) {
			this.maxBytes = maxBytes;
		}

		public int getConnectTimeoutSeconds() {
			return this.connectTimeoutSeconds;
		}

		public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
			this.connectTimeoutSeconds = connectTimeoutSeconds;
		}

		public int getReadTimeoutSeconds() {
			return this.readTimeoutSeconds;
		}

		public void setReadTimeoutSeconds(int readTimeoutSeconds) {
			this.readTimeoutSeconds = readTimeoutSeconds;
		}

		public String getUserAgent() {
			return this.userAgent;
		}

		public void setUserAgent(String userAgent) {
			this.userAgent = userAgent;
		}

	}


	/** How many requests a caller may make. Zero or less disables the limit for that kind. */
	public static class RateLimit {

		/**
		 * Default for API keys. Deliberately below the human allowance: a key belongs to a
		 * process, and a process in a retry loop is the realistic way this service falls over.
		 * A key that genuinely needs more gets its own value on its row.
		 */
		private int keyRequestsPerMinute = 120;

		/**
		 * Default for signed-in people. Higher, because a console page fans out to several
		 * endpoints at once and a person cannot loop.
		 */
		private int userRequestsPerMinute = 600;

		public int getKeyRequestsPerMinute() {
			return this.keyRequestsPerMinute;
		}

		public void setKeyRequestsPerMinute(int keyRequestsPerMinute) {
			this.keyRequestsPerMinute = keyRequestsPerMinute;
		}

		public int getUserRequestsPerMinute() {
			return this.userRequestsPerMinute;
		}

		public void setUserRequestsPerMinute(int userRequestsPerMinute) {
			this.userRequestsPerMinute = userRequestsPerMinute;
		}

	}

}
