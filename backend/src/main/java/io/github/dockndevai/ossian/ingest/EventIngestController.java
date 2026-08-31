package io.github.dockndevai.ossian.ingest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import io.github.dockndevai.ossian.document.DocumentContent;
import io.github.dockndevai.ossian.document.DocumentContentRepository;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;
import io.github.dockndevai.ossian.namespace.NamespaceService;
import io.github.dockndevai.ossian.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Event-driven ingestion, for keeping a corpus in step with a system of record.
 *
 * <p>The upload endpoint models a person choosing a file. This models a pipeline: a CDC stream
 * off a database, a webhook from a CMS, a queue consumer. Three things follow from that, and
 * they are the whole design:
 *
 * <ul>
 * <li><b>Idempotency.</b> Every event carries a caller-assigned {@code eventId}, unique per
 * tenant. Redelivery is normal — brokers promise at-least-once and pipelines crash mid-batch —
 * so a repeat returns the original outcome rather than creating a second document.</li>
 * <li><b>External identity.</b> Documents are addressed by the id they have in the source
 * system, not by ours. An update to row 4172 upstream has to find and replace the chunks made
 * from row 4172, and the caller cannot be expected to remember our UUID.</li>
 * <li><b>Batches.</b> A backfill sends thousands of events. Each is judged on its own and the
 * response reports per event, because one bad record must not fail the batch around it.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/events")
public class EventIngestController {

	/**
	 * One change from an upstream system.
	 *
	 * @param eventId caller-assigned, unique per tenant; the idempotency key
	 * @param operation UPSERT or DELETE
	 * @param externalId the document's identity in the source system
	 * @param namespace which slice of the corpus it belongs to
	 * @param source which pipeline produced it, for tracing
	 * @param filename a display name; falls back to the external id
	 * @param contentType MIME type, when the source knows it
	 * @param text the document body as plain text — use this or contentBase64, not both
	 * @param contentBase64 the raw bytes, for PDF/DOCX and anything else Tika must parse
	 */
	public record IngestEventRequest(@NotBlank @Size(max = 256) String eventId, @NotBlank String operation,
			@NotBlank @Size(max = 512) String externalId, @Size(max = 128) String namespace,
			@Size(max = 128) String source, @Size(max = 512) String filename, @Size(max = 128) String contentType,
			String text, String contentBase64) {
	}

	public record BatchRequest(@Valid @Size(max = 500) List<IngestEventRequest> events) {
	}

	public record EventResult(String eventId, String status, UUID documentId, String message) {
	}

	public record EventView(String eventId, String operation, String externalId, String namespace, String source,
			UUID documentId, String status, String errorMessage, Instant createdAt) {

		static EventView of(IngestEvent e) {
			return new EventView(e.getEventId(), e.getOperation().name(), e.getExternalId(), e.getNamespace(),
					e.getSource(), e.getDocumentId(), e.getStatus().name(), e.getErrorMessage(), e.getCreatedAt());
		}
	}

	private final IngestEventRepository events;

	private final DocumentRepository documents;

	private final DocumentContentRepository contents;

	private final IngestionJobRepository jobs;

	private final IngestionService ingestion;

	private final NamespaceService namespaces;

	private final TenantContext tenant;

	public EventIngestController(IngestEventRepository events, DocumentRepository documents,
			DocumentContentRepository contents, IngestionJobRepository jobs, IngestionService ingestion,
			NamespaceService namespaces, TenantContext tenant) {
		this.events = events;
		this.documents = documents;
		this.contents = contents;
		this.jobs = jobs;
		this.ingestion = ingestion;
		this.namespaces = namespaces;
		this.tenant = tenant;
	}

	/** A single change. Convenience over the batch form for webhook senders. */
	@PostMapping("/documents")
	public ResponseEntity<EventResult> single(@Valid @RequestBody IngestEventRequest request) {
		EventResult result = handle(request);
		HttpStatus status = switch (result.status()) {
			case "DUPLICATE" -> HttpStatus.OK;
			case "FAILED" -> HttpStatus.UNPROCESSABLE_ENTITY;
			default -> HttpStatus.ACCEPTED;
		};
		return ResponseEntity.status(status).body(result);
	}

	/**
	 * A batch of changes.
	 *
	 * <p>Always 200, with the outcome reported per event. A batch is not a transaction: one
	 * malformed record among five hundred should not send the other four hundred and ninety-nine
	 * back to be redelivered.
	 */
	@PostMapping("/documents/batch")
	public List<EventResult> batch(@Valid @RequestBody BatchRequest request) {
		List<EventResult> results = new ArrayList<>();
		if (request.events() == null) {
			return results;
		}
		for (IngestEventRequest event : request.events()) {
			results.add(handle(event));
		}
		return results;
	}

	/** What the pipeline has sent, for reconciling against the source system. */
	@GetMapping("/documents")
	public Page<EventView> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return this.events
			.findByTenantIdOrderByCreatedAtDesc(this.tenant.tenantId(), PageRequest.of(page, Math.min(size, 200)))
			.map(EventView::of);
	}

	@Transactional
	EventResult handle(IngestEventRequest request) {
		String tenantId = this.tenant.tenantId();

		// Idempotency first, before any work: a redelivery must be cheap as well as harmless.
		var seen = this.events.findByTenantIdAndEventId(tenantId, request.eventId());
		if (seen.isPresent()) {
			return new EventResult(request.eventId(), IngestEvent.Status.DUPLICATE.name(), seen.get().getDocumentId(),
					"Already processed at " + seen.get().getCreatedAt());
		}

		IngestEvent event = new IngestEvent();
		event.setTenantId(tenantId);
		event.setEventId(request.eventId());
		event.setExternalId(request.externalId());
		event.setSource(request.source());
		event.setNamespace(this.namespaces.resolve(request.namespace()));

		try {
			IngestEvent.Operation op = IngestEvent.Operation.valueOf(request.operation().trim().toUpperCase());
			event.setOperation(op);
			UUID documentId = (op == IngestEvent.Operation.DELETE) ? delete(event) : upsert(event, request);
			event.setDocumentId(documentId);
			event.setStatus(IngestEvent.Status.ACCEPTED);
			this.events.save(event);
			return new EventResult(request.eventId(), event.getStatus().name(), documentId, null);
		}
		catch (IllegalArgumentException ex) {
			// Record the failure rather than dropping it. A pipeline needs to see which events
			// were rejected and why, or a silent gap in the corpus is the first anyone hears.
			event.setOperation(event.getOperation() == null ? IngestEvent.Operation.UPSERT : event.getOperation());
			event.setStatus(IngestEvent.Status.FAILED);
			event.setErrorMessage(ex.getMessage());
			this.events.save(event);
			return new EventResult(request.eventId(), IngestEvent.Status.FAILED.name(), null, ex.getMessage());
		}
	}

	private UUID delete(IngestEvent event) {
		var existing = this.documents.findByTenantIdAndNamespaceAndExternalId(event.getTenantId(),
				event.getNamespace(), event.getExternalId());
		// A delete for something that was never here is success, not an error: the desired end
		// state is "absent", and it already holds.
		existing.ifPresent(this.ingestion::deleteDocument);
		return existing.map(DocumentEntity::getId).orElse(null);
	}

	private UUID upsert(IngestEvent event, IngestEventRequest request) {
		byte[] content = body(request);
		if (content.length == 0) {
			throw new IllegalArgumentException("An UPSERT needs either text or contentBase64");
		}

		String filename = (request.filename() == null || request.filename().isBlank()) ? request.externalId()
				: request.filename();

		DocumentEntity doc = this.documents
			.findByTenantIdAndNamespaceAndExternalId(event.getTenantId(), event.getNamespace(), event.getExternalId())
			.orElseGet(DocumentEntity::new);

		String hash = IngestionService.hash(content);
		if (doc.getId() != null && hash.equals(doc.getContentHash())) {
			// The upstream row changed in some way that did not change the text we index.
			// Re-embedding it would cost the same and produce the same vectors.
			return doc.getId();
		}

		doc.setTenantId(event.getTenantId());
		doc.setNamespace(event.getNamespace());
		doc.setExternalId(event.getExternalId());
		doc.setSource(request.source());
		doc.setFilename(filename);
		doc.setContentType(request.contentType());
		doc.setSizeBytes(content.length);
		doc.setContentHash(hash);
		doc.setStatus(DocumentEntity.Status.PENDING);
		doc.setUploadedBy(request.source() == null ? "event" : request.source());
		doc.setUpdatedAt(Instant.now());
		boolean replacing = doc.getId() != null;
		doc = this.documents.save(doc);
		this.contents.save(new DocumentContent(doc.getId(), content));

		IngestionJob job = new IngestionJob();
		job.setTenantId(event.getTenantId());
		job.setDocumentId(doc.getId());
		job.setType(replacing ? IngestionJob.Type.REINDEX : IngestionJob.Type.INGEST);
		job = this.jobs.save(job);

		if (replacing) {
			// Drop the previous chunks first, or the old and new text both answer questions and
			// the stale one may well score higher.
			this.ingestion.deleteChunks(doc);
		}
		this.ingestion.ingestAsync(doc.getId(), content, doc.getFilename(), job.getId());
		return doc.getId();
	}

	private static byte[] body(IngestEventRequest request) {
		if (request.contentBase64() != null && !request.contentBase64().isBlank()) {
			try {
				return Base64.getDecoder().decode(request.contentBase64().trim());
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException("contentBase64 is not valid base64");
			}
		}
		if (request.text() != null && !request.text().isBlank()) {
			return request.text().getBytes(StandardCharsets.UTF_8);
		}
		return new byte[0];
	}

}
