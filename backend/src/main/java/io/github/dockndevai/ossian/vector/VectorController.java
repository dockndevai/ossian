package io.github.dockndevai.ossian.vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dockndevai.ossian.tenant.TenantContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only window onto the vector store.
 *
 * <p>Retrieval is the part of a RAG system that fails quietly. A wrong answer looks the same
 * whether the model reasoned badly or the retriever handed it the wrong passage, and the two
 * have opposite fixes. These endpoints make the retriever's side visible: what was actually
 * embedded, what a query is nearest to, and how the corpus is spread out.
 *
 * <p>Everything here reads the store directly rather than going through VectorStore, because
 * the questions are about the storage itself — dimensions, norms, neighbours — which the
 * VectorStore abstraction deliberately hides. Every query is scoped by the tenant claim; the
 * table is shared, so a missing filter here would leak another tenant's documents.
 */
@RestController
@RequestMapping("/api/admin/vectors")
public class VectorController {

	/** One stored chunk, with enough of its embedding to see that it is real. */
	public record ChunkView(String id, String documentId, String filename, Integer chunkIndex, int characters,
			int dimensions, double norm, List<Float> head, String excerpt) {
	}

	public record ChunkPage(List<ChunkView> content, long totalElements, int number, int size) {
	}

	public record NeighbourView(String id, String documentId, String filename, Integer chunkIndex, double similarity,
			String excerpt) {
	}

	public record SearchRequest(@NotBlank @Size(max = 1000) String query, Integer topK) {
	}

	public record SearchResult(String query, int dimensions, double queryNorm, List<NeighbourView> neighbours,
			long latencyMs) {
	}

	public record PointView(String id, String filename, double x, double y, String excerpt) {
	}

	public record ProjectionResult(List<PointView> points, double explainedVariance, int dimensions) {
	}

	private static final int EXCERPT_CHARS = 320;

	private static final int HEAD_VALUES = 8;

	private final JdbcTemplate jdbc;

	private final EmbeddingModel embeddings;

	private final TenantContext tenant;

	private final ObjectMapper json = new ObjectMapper();

	public VectorController(JdbcTemplate jdbc, EmbeddingModel embeddings, TenantContext tenant) {
		this.jdbc = jdbc;
		this.embeddings = embeddings;
		this.tenant = tenant;
	}

	/** The chunks themselves, optionally narrowed to one document. */
	@GetMapping("/chunks")
	public ChunkPage chunks(@RequestParam(required = false) String documentId,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		int limit = Math.min(Math.max(size, 1), 200);
		int offset = Math.max(page, 0) * limit;
		String t = this.tenant.tenantId();

		StringBuilder where = new StringBuilder("where metadata->>'tenant_id' = ?");
		List<Object> args = new ArrayList<>();
		args.add(t);
		if (documentId != null && !documentId.isBlank()) {
			where.append(" and metadata->>'document_id' = ?");
			args.add(documentId);
		}

		Long total = this.jdbc.queryForObject("select count(*) from vector_store " + where, Long.class,
				args.toArray());

		List<Object> paged = new ArrayList<>(args);
		paged.add(limit);
		paged.add(offset);
		List<ChunkView> rows = this.jdbc.query(
				// Ordering by document then chunk index keeps a document's chunks together and in
				// reading order, which is how anyone checking the chunking wants to see them.
				"select id::text, content, metadata::text, embedding::text from vector_store " + where
						+ " order by metadata->>'filename', (metadata->>'chunk_index')::int limit ? offset ?",
				(rs, i) -> toChunk(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
				paged.toArray());

		return new ChunkPage(rows, total == null ? 0 : total, page, limit);
	}

	/**
	 * The retrieval playground: embed a query and show its nearest chunks with raw similarity,
	 * without a model in the loop.
	 *
	 * <p>This is the fastest way to tell a retrieval failure from a generation failure. If the
	 * right passage is at the top here, retrieval is fine and the model is the problem.
	 */
	@PostMapping("/search")
	public SearchResult search(@RequestBody SearchRequest request) {
		long started = System.nanoTime();
		int topK = (request.topK() == null) ? 10 : Math.min(Math.max(request.topK(), 1), 50);
		float[] vector = this.embeddings.embed(request.query());
		String literal = toLiteral(vector);

		List<NeighbourView> neighbours = this.jdbc.query("""
				select id::text, content, metadata::text, 1 - (embedding <=> ?::vector) as similarity
				from vector_store
				where metadata->>'tenant_id' = ?
				order by embedding <=> ?::vector
				limit ?
				""", (rs, i) -> {
			Map<String, Object> meta = readMetadata(rs.getString(3));
			return new NeighbourView(rs.getString(1), str(meta.get("document_id")), str(meta.get("filename")),
					intOrNull(meta.get("chunk_index")), rs.getDouble(4), excerpt(rs.getString(2)));
		}, literal, this.tenant.tenantId(), literal, topK);

		return new SearchResult(request.query(), vector.length, VectorInspection.norm(vector), neighbours,
				(System.nanoTime() - started) / 1_000_000);
	}

	/**
	 * A 2-D projection of the corpus, for a scatter plot.
	 *
	 * <p>Chunks of one document normally cluster; a document whose chunks scatter across the
	 * plot is usually one that covers unrelated topics and would retrieve better split up.
	 */
	@GetMapping("/projection")
	public ProjectionResult projection(@RequestParam(defaultValue = "500") int limit) {
		int cap = Math.min(Math.max(limit, 1), 2000);
		record Row(String id, String filename, String excerpt, float[] embedding) {
		}

		List<Row> rows = this.jdbc.query("""
				select id::text, content, metadata::text, embedding::text
				from vector_store
				where metadata->>'tenant_id' = ?
				order by metadata->>'filename', (metadata->>'chunk_index')::int
				limit ?
				""", (rs, i) -> {
			Map<String, Object> meta = readMetadata(rs.getString(3));
			return new Row(rs.getString(1), str(meta.get("filename")), excerpt(rs.getString(2)),
					VectorInspection.parse(rs.getString(4)));
		}, this.tenant.tenantId(), cap);

		if (rows.isEmpty()) {
			return new ProjectionResult(List.of(), 0, 0);
		}

		VectorInspection.Projection projected = VectorInspection
			.project(rows.stream().map(Row::embedding).toList());

		List<PointView> points = new ArrayList<>(rows.size());
		for (int i = 0; i < rows.size(); i++) {
			double[] xy = projected.points().get(i);
			Row row = rows.get(i);
			points.add(new PointView(row.id(), row.filename(), xy[0], xy[1], row.excerpt()));
		}
		return new ProjectionResult(points, projected.explainedVariance(), rows.get(0).embedding().length);
	}

	private ChunkView toChunk(String id, String content, String metadata, String embedding) {
		Map<String, Object> meta = readMetadata(metadata);
		float[] vector = VectorInspection.parse(embedding);
		List<Float> head = new ArrayList<>(HEAD_VALUES);
		for (int i = 0; i < Math.min(HEAD_VALUES, vector.length); i++) {
			head.add(vector[i]);
		}
		return new ChunkView(id, str(meta.get("document_id")), str(meta.get("filename")),
				intOrNull(meta.get("chunk_index")), content == null ? 0 : content.length(), vector.length,
				VectorInspection.norm(vector), head, excerpt(content));
	}

	private Map<String, Object> readMetadata(String raw) {
		if (raw == null || raw.isBlank()) {
			return Map.of();
		}
		try {
			JsonNode node = this.json.readTree(raw);
			Map<String, Object> out = new HashMap<>();
			node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().isNumber()
					? e.getValue().numberValue() : e.getValue().asText()));
			return out;
		}
		catch (Exception ex) {
			return Map.of();
		}
	}

	private static String excerpt(String content) {
		if (content == null) {
			return "";
		}
		return (content.length() <= EXCERPT_CHARS) ? content : content.substring(0, EXCERPT_CHARS) + "…";
	}

	private static String str(Object value) {
		return (value == null) ? null : value.toString();
	}

	private static Integer intOrNull(Object value) {
		if (value instanceof Number n) {
			return n.intValue();
		}
		try {
			return (value == null) ? null : Integer.valueOf(value.toString());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String toLiteral(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}

}
