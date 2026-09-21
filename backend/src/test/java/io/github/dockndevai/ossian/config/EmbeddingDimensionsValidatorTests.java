package io.github.dockndevai.ossian.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingDimensionsValidatorTests {

	@Test
	void rejectsModelDimensionMismatch() {
		EmbeddingDimensionsValidator validator = validator(768, "vector(1536)", "vector(1536)");

		assertThatThrownBy(validator::validateDimensions).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("configured=1536, model=768, agent_memories=1536, vector_store=1536");
	}

	@Test
	void rejectsMemoryColumnDimensionMismatch() {
		EmbeddingDimensionsValidator validator = validator(1536, "vector(768)", "vector(1536)");

		assertThatThrownBy(validator::validateDimensions).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("agent_memories=768");
	}

	@Test
	void rejectsDocumentVectorStoreDimensionMismatch() {
		EmbeddingDimensionsValidator validator = validator(1536, "vector(1536)", "vector(768)");

		assertThatThrownBy(validator::validateDimensions).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("vector_store=768");
	}

	@Test
	void allowsAnUninitializedDocumentVectorStore() {
		EmbeddingDimensionsValidator validator = validator(1536, "vector(1536)", null);

		validator.validateDimensions();
	}

	private EmbeddingDimensionsValidator validator(int modelDimensions, String memoryType, String vectorType) {
		EmbeddingModel model = mock(EmbeddingModel.class);
		when(model.dimensions()).thenReturn(modelDimensions);
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		List<Map<String, Object>> columns = vectorType == null
				? List.of(Map.of("table_name", "agent_memories", "column_type", memoryType))
				: List.of(Map.of("table_name", "agent_memories", "column_type", memoryType),
						Map.of("table_name", "vector_store", "column_type", vectorType));
		when(jdbc.queryForList(anyString())).thenReturn(columns);
		return new EmbeddingDimensionsValidator(jdbc, model, 1536);
	}

}