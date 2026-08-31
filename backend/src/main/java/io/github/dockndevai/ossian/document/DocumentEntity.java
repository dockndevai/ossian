package io.github.dockndevai.ossian.document;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A source file the tenant uploaded, and the state of its journey into the vector store. */
@Entity
@Table(name = "documents")
public class DocumentEntity {

	public enum Status {

		/** Stored, not yet chunked or embedded. */
		PENDING,
		/** Chunking and embedding in flight. */
		PROCESSING,
		/** Chunks are in the vector store and answerable. */
		READY,
		/** Ingestion failed; {@code errorMessage} says why. */
		FAILED

	}

	@Id
	@GeneratedValue
	private UUID id;


	@Column(nullable = false)
	private String filename;

	@Column(name = "content_type")
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "content_hash", nullable = false, length = 64)
	private String contentHash;

	/**
	 * Which slice of the tenant's corpus this belongs to. Not a security boundary — the tenant
	 * is. A user may read across their own namespaces; they can never read another tenant's.
	 */
	@Column(nullable = false)
	private String namespace = "default";

	/** Identity in the system this was imported from, for event-driven ingestion. Null for uploads. */
	@Column(name = "external_id")
	private String externalId;

	/** Which importer produced it, for tracing an unexpected document back to its origin. */
	private String source;

	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.PENDING;

	@Column(name = "chunk_count", nullable = false)
	private int chunkCount;

	@Column(name = "error_message")
	private String errorMessage;

	/** The address this source was fetched from, or null when it was uploaded. */
	@Column(name = "source_url", length = 2000)
	private String sourceUrl;

	@Column(name = "uploaded_by")
	private String uploadedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@PreUpdate
	void touch() {
		this.updatedAt = Instant.now();
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}



	public String getFilename() {
		return this.filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getContentType() {
		return this.contentType;
	}

	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

	public long getSizeBytes() {
		return this.sizeBytes;
	}

	public void setSizeBytes(long sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public String getContentHash() {
		return this.contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public String getTitle() {
		return this.title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public Status getStatus() {
		return this.status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public int getChunkCount() {
		return this.chunkCount;
	}

	public void setChunkCount(int chunkCount) {
		this.chunkCount = chunkCount;
	}

	public String getErrorMessage() {
		return this.errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public String getUploadedBy() {
		return this.uploadedBy;
	}

	public void setUploadedBy(String uploadedBy) {
		this.uploadedBy = uploadedBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public String getNamespace() {
		return this.namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getExternalId() {
		return this.externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getSource() {
		return this.source;
	}

	public void setSource(String source) {
		this.source = source;
	}


	public String getSourceUrl() {
		return this.sourceUrl;
	}

	public void setSourceUrl(String sourceUrl) {
		this.sourceUrl = sourceUrl;
	}

}
