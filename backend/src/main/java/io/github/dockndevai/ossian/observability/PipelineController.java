package io.github.dockndevai.ossian.observability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.dockndevai.ossian.document.DocumentContentRepository;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;
import io.github.dockndevai.ossian.ingest.IngestionJob;
import io.github.dockndevai.ossian.ingest.IngestionJobRepository;
import io.github.dockndevai.ossian.ingest.IngestionService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Whether ingestion is working, and what to do when it is not.
 *
 * <p>Prometheus has the rates; this answers the questions someone asks while a document is stuck.
 * Which ones failed, why, whether the failures share a cause, and can it be run again. A failure
 * rate on a graph tells you something is wrong — the grouped reasons below tell you what, which
 * is usually one bad file type or one oversized PDF rather than a general problem.
 */
@RestController
@RequestMapping("/api/admin/pipeline")
public class PipelineController {

	/** Ingestion over a window, coarse enough to read at a glance. */
	public record Throughput(long documents, long succeeded, long failed, long chunks, Double successRate,
			Double avgDurationMs, Double p95DurationMs) {
	}

	/** Failures sharing a cause, which is how they almost always arrive. */
	public record FailureGroup(String reason, long count, Instant mostRecent, List<String> examples) {
	}

	public record Stuck(UUID documentId, String filename, String namespace, String status, String errorMessage,
			Instant since) {
	}

	private final JdbcTemplate jdbc;

	private final DocumentRepository documents;

	private final DocumentContentRepository contents;

	private final IngestionJobRepository jobs;

	private final IngestionService ingestion;

	public PipelineController(JdbcTemplate jdbc, DocumentRepository documents, DocumentContentRepository contents,
			IngestionJobRepository jobs, IngestionService ingestion) {
		this.jdbc = jdbc;
		this.documents = documents;
		this.contents = contents;
		this.jobs = jobs;
		this.ingestion = ingestion;
	}

	@GetMapping("/throughput")
	public Throughput throughput(@RequestParam(defaultValue = "24") int hours) {
		int window = Math.min(Math.max(hours, 1), 24 * 30);
		return this.jdbc.queryForObject("""
				select count(*)                                                        as documents,
				       count(*) filter (where status = 'SUCCEEDED')                    as succeeded,
				       count(*) filter (where status = 'FAILED')                       as failed,
				       coalesce(sum(chunks_written), 0)                                as chunks,
				       -- Cast in SQL: avg() over a bigint is numeric, which arrives as a
				       -- BigDecimal and casts to Double at runtime, not at compile time.
				       avg(duration_ms)::float8                                        as avg_ms,
				       (percentile_cont(0.95) within group (order by duration_ms))::float8 as p95_ms
				from ingestion_jobs
				where created_at > now() - make_interval(hours => ?)
				""", (rs, i) -> {
			long total = rs.getLong("documents");
			long succeeded = rs.getLong("succeeded");
			// Null rather than 1.0 when nothing ran: a success rate of 100% over no work reads
			// as healthy, and the honest answer is that there is nothing to report.
			Double rate = (total == 0) ? null : (double) succeeded / total;
			return new Throughput(total, succeeded, rs.getLong("failed"), rs.getLong("chunks"), rate,
					(Double) rs.getObject("avg_ms"), (Double) rs.getObject("p95_ms"));
		}, window);
	}

	/**
	 * Failures grouped by reason.
	 *
	 * <p>Grouped on the first line of the message: stack traces and ids make every failure look
	 * unique, and twenty distinct failures that are really one cause is the view that wastes the
	 * most time.
	 */
	@GetMapping("/failures")
	public List<FailureGroup> failures(@RequestParam(defaultValue = "168") int hours) {
		int window = Math.min(Math.max(hours, 1), 24 * 90);
		return this.jdbc.query("""
				select split_part(coalesce(error_message, 'unknown'), E'\\n', 1) as reason,
				       count(*)        as occurrences,
				       max(created_at) as most_recent,
				       (array_agg(document_id::text order by created_at desc))[1:3] as examples
				from ingestion_jobs
				where status = 'FAILED'
				  and created_at > now() - make_interval(hours => ?)
				group by 1
				order by count(*) desc, max(created_at) desc
				limit 20
				""", (rs, i) -> new FailureGroup(rs.getString("reason"), rs.getLong("occurrences"),
				rs.getTimestamp("most_recent").toInstant(),
				List.of((String[]) rs.getArray("examples").getArray())), window);
	}

	/**
	 * Documents that are not READY and are not going to become READY on their own.
	 *
	 * <p>Includes anything left PROCESSING for more than an hour. Nothing marks those failed —
	 * the process that would have done it is the one that died — so without this they sit
	 * forever, showing a spinner that will never resolve.
	 */
	@GetMapping("/stuck")
	public List<Stuck> stuck() {
		return this.jdbc.query("""
				select id, filename, namespace, status, error_message, created_at
				from documents
				where status = 'FAILED'
				   or (status in ('PENDING', 'PROCESSING') and created_at < now() - interval '1 hour')
				order by created_at desc
				limit 100
				""", (rs, i) -> new Stuck(rs.getObject("id", UUID.class), rs.getString("filename"),
				rs.getString("namespace"), rs.getString("status"), rs.getString("error_message"),
				rs.getTimestamp("created_at").toInstant()));
	}

	/**
	 * Runs a failed document through ingestion again, from the bytes that were stored.
	 *
	 * <p>Manual rather than automatic. Most ingestion failures are deterministic — an unreadable
	 * file, a document larger than the model's context — and retrying those on a timer burns the
	 * embedding budget rediscovering the same fact. A retry is worth doing after something was
	 * fixed, and only a person knows when that happened.
	 */
	@PostMapping("/retry")
	public ResponseEntity<Stuck> retry(@RequestParam UUID documentId) {
		DocumentEntity doc = this.documents.findById(documentId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
		byte[] content = this.contents.findById(documentId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
					"The original bytes are not retained for this document; upload it again instead"))
			.getContent();

		doc.setStatus(DocumentEntity.Status.PENDING);
		doc.setErrorMessage(null);
		this.documents.save(doc);

		IngestionJob job = new IngestionJob();
		job.setDocumentId(documentId);
		job.setType(IngestionJob.Type.INGEST);
		job = this.jobs.save(job);

		this.ingestion.ingestAsync(documentId, content, doc.getFilename(), job.getId());
		return ResponseEntity.accepted()
			.body(new Stuck(doc.getId(), doc.getFilename(), doc.getNamespace(), doc.getStatus().name(), null,
					doc.getCreatedAt()));
	}

}
