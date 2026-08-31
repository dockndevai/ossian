package io.github.dockndevai.openbook.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything tunable about ingestion, retrieval and answering, under {@code openbook}. */
@ConfigurationProperties(OpenbookProperties.PREFIX)
public class OpenbookProperties {

	public static final String PREFIX = "openbook";

	private Ingest ingest = new Ingest();

	private Retrieval retrieval = new Retrieval();

	private Chat chat = new Chat();

	private Cors cors = new Cors();

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
		private int embeddingBatchSize = 25;

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

	public static class Cors {

		private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

		public List<String> getAllowedOrigins() {
			return this.allowedOrigins;
		}

		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}

	}

}
