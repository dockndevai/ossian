package io.github.dockndevai.ossian.document;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.ingest.IngestionJob;
import io.github.dockndevai.ossian.ingest.IngestionJobRepository;
import io.github.dockndevai.ossian.ingest.IngestionService;
import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Upload, list and delete the documents that back retrieval. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	public record DocumentView(UUID id, String filename, String title, String contentType, long sizeBytes,
			String status, int chunkCount, String errorMessage, String uploadedBy, Instant createdAt) {

		static DocumentView of(DocumentEntity d) {
			return new DocumentView(d.getId(), d.getFilename(), d.getTitle(), d.getContentType(), d.getSizeBytes(),
					d.getStatus().name(), d.getChunkCount(), d.getErrorMessage(), d.getUploadedBy(), d.getCreatedAt());
		}
	}

	public record UploadResponse(UUID documentId, UUID jobId, String status, boolean duplicate) {
	}

	private final DocumentRepository documents;

	private final DocumentContentRepository contents;

	private final IngestionJobRepository jobs;

	private final IngestionService ingestion;

	private final TenantContext tenant;

	private final OssianProperties properties;

	public DocumentController(DocumentRepository documents, DocumentContentRepository contents,
			IngestionJobRepository jobs, IngestionService ingestion, TenantContext tenant,
			OssianProperties properties) {
		this.documents = documents;
		this.contents = contents;
		this.jobs = jobs;
		this.ingestion = ingestion;
		this.tenant = tenant;
		this.properties = properties;
	}

	@GetMapping
	public Page<DocumentView> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return this.documents
			.findByTenantIdOrderByCreatedAtDesc(this.tenant.tenantId(), PageRequest.of(page, Math.min(size, 100)))
			.map(DocumentView::of);
	}

	@GetMapping("/{id}")
	public DocumentView get(@PathVariable UUID id) {
		return DocumentView.of(load(id));
	}

	@PostMapping
	public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
		}
		if (file.getSize() > this.properties.getIngest().getMaxFileSize()) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
					"File exceeds " + this.properties.getIngest().getMaxFileSize() + " bytes");
		}

		byte[] content = file.getBytes();
		String hash = IngestionService.hash(content);

		// Same bytes, same tenant: return the existing document rather than embedding it twice.
		var existing = this.documents.findByTenantIdAndContentHash(this.tenant.tenantId(), hash);
		if (existing.isPresent()) {
			return ResponseEntity.ok(new UploadResponse(existing.get().getId(), null,
					existing.get().getStatus().name(), true));
		}

		DocumentEntity doc = new DocumentEntity();
		doc.setTenantId(this.tenant.tenantId());
		doc.setFilename(file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename());
		doc.setContentType(file.getContentType());
		doc.setSizeBytes(file.getSize());
		doc.setContentHash(hash);
		doc.setUploadedBy(this.tenant.preferredUsername());
		doc = this.documents.save(doc);
		this.contents.save(new DocumentContent(doc.getId(), content));

		IngestionJob job = new IngestionJob();
		job.setTenantId(this.tenant.tenantId());
		job.setDocumentId(doc.getId());
		job.setType(IngestionJob.Type.INGEST);
		job = this.jobs.save(job);

		this.ingestion.ingestAsync(doc.getId(), content, doc.getFilename(), job.getId());
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.body(new UploadResponse(doc.getId(), job.getId(), doc.getStatus().name(), false));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		this.ingestion.deleteDocument(load(id));
		return ResponseEntity.noContent().build();
	}

	/** Look up by id AND tenant: an id alone must never reach another tenant's document. */
	private DocumentEntity load(UUID id) {
		return this.documents.findByIdAndTenantId(id, this.tenant.tenantId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
	}

	@GetMapping("/statuses")
	public List<String> statuses() {
		return List.of("PENDING", "PROCESSING", "READY", "FAILED");
	}

}
