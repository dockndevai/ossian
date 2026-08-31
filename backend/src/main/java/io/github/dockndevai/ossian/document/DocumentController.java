package io.github.dockndevai.ossian.document;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.ingest.IngestionJob;
import io.github.dockndevai.ossian.ingest.IngestionJobRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.github.dockndevai.ossian.fetch.UrlFetcher;
import io.github.dockndevai.ossian.ingest.IngestionService;
import io.github.dockndevai.ossian.namespace.NamespaceService;
import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
			String status, int chunkCount, String errorMessage, String uploadedBy, String namespace,
			String externalId, String source, String sourceUrl, Instant createdAt) {

		static DocumentView of(DocumentEntity d) {
			return new DocumentView(d.getId(), d.getFilename(), d.getTitle(), d.getContentType(), d.getSizeBytes(),
					d.getStatus().name(), d.getChunkCount(), d.getErrorMessage(), d.getUploadedBy(),
					d.getNamespace(), d.getExternalId(), d.getSource(), d.getSourceUrl(), d.getCreatedAt());
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

	private final NamespaceService namespaces;

	private final UrlFetcher fetcher;

	public DocumentController(DocumentRepository documents, DocumentContentRepository contents,
			IngestionJobRepository jobs, IngestionService ingestion, TenantContext tenant,
			OssianProperties properties, NamespaceService namespaces, UrlFetcher fetcher) {
		this.documents = documents;
		this.contents = contents;
		this.jobs = jobs;
		this.ingestion = ingestion;
		this.tenant = tenant;
		this.properties = properties;
		this.namespaces = namespaces;
		this.fetcher = fetcher;
	}

	@GetMapping
	public Page<DocumentView> list(@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(required = false) String namespace) {
		PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
		// No namespace means every namespace this tenant has — an absent filter widens within the
		// tenant, never past it.
		return ((namespace == null || namespace.isBlank())
				? this.documents.findByTenantIdOrderByCreatedAtDesc(this.tenant.tenantId(), pageable)
				: this.documents.findByTenantIdAndNamespaceOrderByCreatedAtDesc(this.tenant.tenantId(),
						this.namespaces.resolve(namespace), pageable))
			.map(DocumentView::of);
	}

	@GetMapping("/{id}")
	public DocumentView get(@PathVariable UUID id) {
		return DocumentView.of(load(id));
	}

	@PostMapping
	public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file,
			@RequestParam(required = false) String namespace) throws IOException {
		if (file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
		}
		if (file.getSize() > this.properties.getIngest().getMaxFileSize()) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
					"File exceeds " + this.properties.getIngest().getMaxFileSize() + " bytes");
		}

		byte[] content = file.getBytes();
		String hash = IngestionService.hash(content);
		// An unknown namespace resolves to the default rather than erroring: a typo that silently
		// returned an empty corpus would read as "my documents are gone".
		String ns = this.namespaces.resolve(namespace);

		// Same bytes, same namespace: return the existing document rather than embedding it twice.
		var existing = this.documents.findByTenantIdAndNamespaceAndContentHash(this.tenant.tenantId(), ns, hash);
		if (existing.isPresent()) {
			return ResponseEntity.ok(new UploadResponse(existing.get().getId(), null,
					existing.get().getStatus().name(), true));
		}

		DocumentEntity doc = new DocumentEntity();
		doc.setNamespace(ns);
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

	/** A source given as a URL rather than a file. */
	public record UrlRequest(@NotBlank @Size(max = 2000) String url, String namespace, String title) {
	}

	/**
	 * Adds a page as a source.
	 *
	 * <p>The fetch happens as this server, from this network, so the URL is checked before a
	 * socket is opened and again after every redirect. Once the bytes are here the path is
	 * identical to an upload — same parsing, same hashing, same de-duplication — because a source
	 * should not behave differently for having arrived over HTTP.
	 */
	@PostMapping("/url")
	public ResponseEntity<UploadResponse> addUrl(@Valid @RequestBody UrlRequest request) {
		UrlFetcher.Fetched fetched;
		try {
			fetched = this.fetcher.fetch(request.url());
		}
		catch (UrlFetcher.NotAllowed ex) {
			// The caller's URL was the problem, so this is theirs to fix, not a server fault.
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
		}

		byte[] content = fetched.content();
		if (content.length == 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That URL returned an empty document");
		}

		String hash = IngestionService.hash(content);
		String ns = this.namespaces.resolve(request.namespace());
		var existing = this.documents.findByTenantIdAndNamespaceAndContentHash(this.tenant.tenantId(), ns, hash);
		if (existing.isPresent()) {
			return ResponseEntity.ok(new UploadResponse(existing.get().getId(), null,
					existing.get().getStatus().name(), true));
		}

		String name = (request.title() != null && !request.title().isBlank()) ? request.title().strip()
				: fetched.suggestedName();

		DocumentEntity doc = new DocumentEntity();
		doc.setNamespace(ns);
		doc.setTenantId(this.tenant.tenantId());
		doc.setFilename(name);
		doc.setContentType(fetched.contentType());
		doc.setSizeBytes((long) content.length);
		doc.setContentHash(hash);
		doc.setSourceUrl(fetched.finalUrl());
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
