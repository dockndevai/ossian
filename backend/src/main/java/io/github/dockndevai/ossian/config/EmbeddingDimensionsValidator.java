package io.github.dockndevai.ossian.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Verifies that the configured model and pgvector schemas use the same dimension. */
@Component
public class EmbeddingDimensionsValidator implements ApplicationRunner {

	private static final Pattern VECTOR_TYPE = Pattern.compile("^vector\\((\\d+)\\)$");

	private static final String EMBEDDING_COLUMNS_SQL = """
			select c.relname as table_name, format_type(a.atttypid, a.atttypmod) as column_type
			from pg_attribute a
			join pg_class c on c.oid = a.attrelid
			where c.oid in (to_regclass('agent_memories'), to_regclass('vector_store'))
			  and a.attname = 'embedding'
			  and a.attnum > 0
			  and not a.attisdropped
			""";

	private final JdbcTemplate jdbc;

	private final EmbeddingModel embeddings;

	private final int configuredDimensions;

	public EmbeddingDimensionsValidator(JdbcTemplate jdbc, EmbeddingModel embeddings,
			@Value("${spring.ai.vectorstore.pgvector.dimensions}") int configuredDimensions) {
		this.jdbc = jdbc;
		this.embeddings = embeddings;
		this.configuredDimensions = configuredDimensions;
	}

	@Override
	public void run(ApplicationArguments args) {
		validateDimensions();
	}

	void validateDimensions() {
		int modelDimensions = this.embeddings.dimensions();
		List<Map<String, Object>> columns = this.jdbc.queryForList(EMBEDDING_COLUMNS_SQL);
		Map<String, String> columnTypes = new HashMap<>();
		for (Map<String, Object> column : columns) {
			columnTypes.put((String) column.get("table_name"), (String) column.get("column_type"));
		}

		String memoryColumnType = columnTypes.get("agent_memories");
		if (memoryColumnType == null) {
			throw new IllegalStateException("agent_memories.embedding is missing; expected a dimensioned pgvector column");
		}
		int memoryDimensions = parseDimensions("agent_memories", memoryColumnType);
		String vectorColumnType = columnTypes.get("vector_store");
		Integer vectorDimensions = vectorColumnType == null ? null : parseDimensions("vector_store", vectorColumnType);

		if (modelDimensions != this.configuredDimensions || memoryDimensions != this.configuredDimensions
				|| (vectorDimensions != null && vectorDimensions != this.configuredDimensions)) {
			String vectorStore = vectorDimensions == null ? "not initialized" : vectorDimensions.toString();
			throw new IllegalStateException("Embedding dimension mismatch: configured=" + this.configuredDimensions
					+ ", model=" + modelDimensions + ", agent_memories=" + memoryDimensions + ", vector_store="
					+ vectorStore + ". Set LLM_EMBED_DIMENSIONS to the model output and re-embed existing memories.");
		}
	}

	private int parseDimensions(String tableName, String columnType) {
		Matcher match = VECTOR_TYPE.matcher(columnType);
		if (!match.matches()) {
			throw new IllegalStateException(tableName + ".embedding has unsupported type " + columnType
					+ "; expected vector(<dimension>)");
		}
		return Integer.parseInt(match.group(1));
	}

}