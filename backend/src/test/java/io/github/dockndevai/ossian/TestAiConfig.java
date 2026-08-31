package io.github.dockndevai.ossian;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the model and vector-store beans so tests never reach a real LLM.
 * <p>
 * The vector store here records what it was asked for, which is what lets the tenant-isolation
 * test assert on the filter expression rather than on an answer's wording.
 */
@TestConfiguration
public class TestAiConfig {

	/** Captures the last search so tests can assert the tenant filter was applied. */
	public static class RecordingVectorStore implements VectorStore {

		public volatile SearchRequest lastRequest;

		public volatile List<Document> lastAdded;

		public volatile String lastDeleteFilter;

		private volatile List<Document> nextResults = List.of();

		public void returnOnSearch(List<Document> docs) {
			this.nextResults = docs;
		}

		@Override
		public void add(List<Document> documents) {
			this.lastAdded = documents;
		}

		@Override
		public void delete(List<String> idList) {
			// not used by these tests
		}

		@Override
		public void delete(String filterExpression) {
			this.lastDeleteFilter = filterExpression;
		}

		@Override
		public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
			this.lastDeleteFilter = String.valueOf(filterExpression);
		}

		@Override
		public List<Document> similaritySearch(SearchRequest request) {
			this.lastRequest = request;
			return this.nextResults;
		}

	}

	@Bean
	@Primary
	public RecordingVectorStore vectorStore() {
		return new RecordingVectorStore();
	}

	/** Excluding OpenAiChatAutoConfiguration removes ChatModel, which AiConfig needs. */
	@Bean
	@Primary
	public org.springframework.ai.chat.model.ChatModel chatModel() {
		return new org.springframework.ai.chat.model.ChatModel() {
			@Override
			public org.springframework.ai.chat.model.ChatResponse call(
					org.springframework.ai.chat.prompt.Prompt prompt) {
				return new org.springframework.ai.chat.model.ChatResponse(List.of(
						new org.springframework.ai.chat.model.Generation(
								new org.springframework.ai.chat.messages.AssistantMessage("stubbed answer"))));
			}
		};
	}

	@Bean
	@Primary
	public EmbeddingModel embeddingModel() {
		return new EmbeddingModel() {
			@Override
			public EmbeddingResponse call(EmbeddingRequest request) {
				return new EmbeddingResponse(List.of());
			}

			@Override
			public float[] embed(Document document) {
				return new float[] { 0.1f, 0.2f, 0.3f };
			}
		};
	}

}
