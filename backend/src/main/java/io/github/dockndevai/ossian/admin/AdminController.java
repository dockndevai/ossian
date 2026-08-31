package io.github.dockndevai.ossian.admin;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import io.github.dockndevai.ossian.chat.QueryLog;
import io.github.dockndevai.ossian.chat.QueryLogRepository;
import io.github.dockndevai.ossian.document.DocumentContentRepository;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;
import io.github.dockndevai.ossian.ingest.IngestionJob;
import io.github.dockndevai.ossian.ingest.IngestionJobRepository;
import io.github.dockndevai.ossian.ingest.IngestionService;
import io.github.dockndevai.ossian.namespace.NamespaceService;
import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The maintenance side: what is in the corpus, what ingestion did, and whether retrieval is
 * actually working. Restricted to the {@code ossian-admin} realm role by SecurityConfig.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	public record CorpusStats(long documents, long ready, long failed, long chunks, long bytes) {
	}

	public record RetrievalStats(long questionsLast7d, long unansweredLast7d, Double answerRate, Double avgLatencyMs,
			Double avgTopScore) {
	}

	public record JobView(UUID id, UUID documentId, String type, String status, int chunksWritten, Long durationMs,
			String errorMessage, Instant createdAt) {

		static JobView of(IngestionJob j) {
			return new JobView(j.getId(), j.getDocumentId(), j.getType().name(), j.getStatus().name(),
					j.getChunksWritten(), j.getDurationMs(), j.getErrorMessage(), j.getCreatedAt());
		}
	}

	public record GapView(String question, int chunksRetrieved, Double topScore, Instant createdAt) {

		static GapView of(QueryLog q) {
			return new GapView(q.getQuestion(), q.getChunksRetrieved(), q.getTopScore(), q.getCreatedAt());
		}
	}

	private final DocumentRepository documents;

	private final DocumentContentRepository contents;

	private final IngestionJobRepository jobs;

	private final QueryLogRepository queryLog;

	private final IngestionService ingestion;

	private final TenantContext tenant;

	private final NamespaceService namespaces;

	public AdminController(DocumentRepository documents, DocumentContentRepository contents,
			IngestionJobRepository jobs, QueryLogRepository queryLog, IngestionService ingestion,
			TenantContext tenant, NamespaceService namespaces) {
		this.documents = documents;
		this.contents = contents;
		this.jobs = jobs;
		this.queryLog = queryLog;
		this.ingestion = ingestion;
		this.tenant = tenant;
		this.namespaces = namespaces;
	}

	/**
	 * What the corpus actually contains right now, optionally for one namespace.
	 *
	 * <p>No namespace means every namespace this tenant has. An absent filter widens within the
	 * tenant and never past it.
	 */
	@GetMapping("/stats/corpus")
	public CorpusStats corpus(@RequestParam(required = false) String namespace) {
		String t = this.tenant.tenantId();
		if (namespace == null || namespace.isBlank()) {
			return new CorpusStats(this.documents.countByTenantId(t),
					this.documents.countByTenantIdAndStatus(t, DocumentEntity.Status.READY),
					this.documents.countByTenantIdAndStatus(t, DocumentEntity.Status.FAILED),
					this.documents.sumChunksByTenantId(t), this.documents.sumBytesByTenantId(t));
		}
		String ns = this.namespaces.resolve(namespace);
		return new CorpusStats(this.documents.countByTenantIdAndNamespace(t, ns),
				this.documents.countByTenantIdAndNamespaceAndStatus(t, ns, DocumentEntity.Status.READY),
				this.documents.countByTenantIdAndNamespaceAndStatus(t, ns, DocumentEntity.Status.FAILED),
				this.documents.sumChunksByTenantIdAndNamespace(t, ns),
				this.documents.sumBytesByTenantIdAndNamespace(t, ns));
	}

	/**
	 * Whether retrieval is doing its job. The number that matters is the answer rate: a corpus
	 * that cannot answer the questions people actually ask is failing regardless of its size.
	 */
	@GetMapping("/stats/retrieval")
	public RetrievalStats retrieval(@RequestParam(required = false) String namespace) {
		String t = this.tenant.tenantId();
		Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
		if (namespace == null || namespace.isBlank()) {
			long asked = this.queryLog.countByTenantIdAndCreatedAtAfter(t, since);
			long unanswered = this.queryLog.countByTenantIdAndAnsweredFalseAndCreatedAtAfter(t, since);
			Double rate = (asked == 0) ? null : (double) (asked - unanswered) / asked;
			return new RetrievalStats(asked, unanswered, rate, this.queryLog.avgLatency(t, since),
					this.queryLog.avgTopScore(t, since));
		}
		String ns = this.namespaces.resolve(namespace);
		long asked = this.queryLog.countByTenantIdAndNamespaceAndCreatedAtAfter(t, ns, since);
		long unanswered = this.queryLog.countByTenantIdAndNamespaceAndAnsweredFalseAndCreatedAtAfter(t, ns, since);
		Double rate = (asked == 0) ? null : (double) (asked - unanswered) / asked;
		return new RetrievalStats(asked, unanswered, rate, this.queryLog.avgLatency(t, ns, since),
				this.queryLog.avgTopScore(t, ns, since));
	}

	/** Questions the corpus could not answer — the input to deciding what to ingest next. */
	@GetMapping("/gaps")
	public List<GapView> gaps(@RequestParam(required = false) String namespace) {
		String t = this.tenant.tenantId();
		List<QueryLog> rows = (namespace == null || namespace.isBlank())
				? this.queryLog.findTop50ByTenantIdAndAnsweredFalseOrderByCreatedAtDesc(t)
				: this.queryLog.findTop50ByTenantIdAndNamespaceAndAnsweredFalseOrderByCreatedAtDesc(t,
						this.namespaces.resolve(namespace));
		return rows.stream().map(GapView::of).toList();
	}

	@GetMapping("/jobs")
	public Page<JobView> jobs(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return this.jobs
			.findByTenantIdOrderByCreatedAtDesc(this.tenant.tenantId(), PageRequest.of(page, Math.min(size, 100)))
			.map(JobView::of);
	}

	/**
	 * Rebuild one document's chunks from the stored original. This is what makes a chunking or
	 * embedding-model change safe to roll out: without it the old vectors would linger and the
	 * corpus would silently mix two strategies.
	 */
	@PostMapping("/documents/{id}/reindex")
	public JobView reindex(@PathVariable UUID id) {
		DocumentEntity doc = this.documents.findByIdAndTenantId(id, this.tenant.tenantId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
		var content = this.contents.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
					"Original content is not retained for this document; re-upload it instead"));

		IngestionJob job = new IngestionJob();
		job.setTenantId(this.tenant.tenantId());
		job.setDocumentId(id);
		job.setType(IngestionJob.Type.REINDEX);
		job = this.jobs.save(job);
		job.start();
		try {
			int written = this.ingestion.reindex(doc, content.getContent());
			job.succeed(written);
		}
		catch (RuntimeException ex) {
			job.fail(ex.getMessage());
		}
		return JobView.of(this.jobs.save(job));
	}

}
