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
import io.github.dockndevai.ossian.caller.CallerContext;

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

	private final CallerContext tenant;

	private final NamespaceService namespaces;

	public AdminController(DocumentRepository documents, DocumentContentRepository contents,
			IngestionJobRepository jobs, QueryLogRepository queryLog, IngestionService ingestion,
			CallerContext tenant, NamespaceService namespaces) {
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
		var scope = this.namespaces.effectiveFilter(namespace);
		if (scope.isEmpty()) {
			return new CorpusStats(this.documents.count(),
					this.documents.countByStatus(DocumentEntity.Status.READY),
					this.documents.countByStatus(DocumentEntity.Status.FAILED),
					this.documents.sumChunks(), this.documents.sumBytes());
		}
		String ns = scope.get();
		return new CorpusStats(this.documents.countByNamespace(ns),
				this.documents.countByNamespaceAndStatus(ns, DocumentEntity.Status.READY),
				this.documents.countByNamespaceAndStatus(ns, DocumentEntity.Status.FAILED),
				this.documents.sumChunksByNamespace(ns),
				this.documents.sumBytesByNamespace(ns));
	}

	/**
	 * Whether retrieval is doing its job. The number that matters is the answer rate: a corpus
	 * that cannot answer the questions people actually ask is failing regardless of its size.
	 */
	@GetMapping("/stats/retrieval")
	public RetrievalStats retrieval(@RequestParam(required = false) String namespace) {
		Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
		var scope = this.namespaces.effectiveFilter(namespace);
		if (scope.isEmpty()) {
			long asked = this.queryLog.countByCreatedAtAfter(since);
			long unanswered = this.queryLog.countByAnsweredFalseAndCreatedAtAfter(since);
			Double rate = (asked == 0) ? null : (double) (asked - unanswered) / asked;
			return new RetrievalStats(asked, unanswered, rate, this.queryLog.avgLatency(since),
					this.queryLog.avgTopScore(since));
		}
		String ns = scope.get();
		long asked = this.queryLog.countByNamespaceAndCreatedAtAfter(ns, since);
		long unanswered = this.queryLog.countByNamespaceAndAnsweredFalseAndCreatedAtAfter(ns, since);
		Double rate = (asked == 0) ? null : (double) (asked - unanswered) / asked;
		return new RetrievalStats(asked, unanswered, rate, this.queryLog.avgLatency(ns, since),
				this.queryLog.avgTopScore(ns, since));
	}

	/** Questions the corpus could not answer — the input to deciding what to ingest next. */
	@GetMapping("/gaps")
	public List<GapView> gaps(@RequestParam(required = false) String namespace) {
		List<QueryLog> rows = this.namespaces.effectiveFilter(namespace)
			.map(ns -> this.queryLog.findTop50ByNamespaceAndAnsweredFalseOrderByCreatedAtDesc(ns))
			.orElseGet(() -> this.queryLog.findTop50ByAnsweredFalseOrderByCreatedAtDesc());
		return rows.stream().map(GapView::of).toList();
	}

	@GetMapping("/jobs")
	public Page<JobView> jobs(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return this.jobs
			.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size, 100)))
			.map(JobView::of);
	}

	/**
	 * Rebuild one document's chunks from the stored original. This is what makes a chunking or
	 * embedding-model change safe to roll out: without it the old vectors would linger and the
	 * corpus would silently mix two strategies.
	 */
	@PostMapping("/documents/{id}/reindex")
	public JobView reindex(@PathVariable UUID id) {
		DocumentEntity doc = this.documents.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
		var content = this.contents.findById(id)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
					"Original content is not retained for this document; re-upload it instead"));

		IngestionJob job = new IngestionJob();
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
