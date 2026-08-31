package io.github.dockndevai.ossian.ingest;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One unit of work against the retrieval layer, kept as a row so the admin side can answer
 * "what happened, when, and how long did it take" without scraping logs.
 */
@Entity
@Table(name = "ingestion_jobs")
public class IngestionJob {

	public enum Type {

		/** First-time ingestion of an uploaded file. */
		INGEST,
		/** Re-chunk and re-embed an existing document, e.g. after changing chunk size. */
		REINDEX,
		/** Remove a document's chunks from the vector store. */
		DELETE

	}

	public enum Status {

		QUEUED, RUNNING, SUCCEEDED, FAILED

	}

	@Id
	@GeneratedValue
	private UUID id;


	@Column(name = "document_id")
	private UUID documentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Type type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.QUEUED;

	@Column(name = "chunks_written", nullable = false)
	private int chunksWritten;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "duration_ms")
	private Long durationMs;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public void start() {
		this.status = Status.RUNNING;
		this.startedAt = Instant.now();
	}

	public void succeed(int chunks) {
		this.status = Status.SUCCEEDED;
		this.chunksWritten = chunks;
		finish();
	}

	public void fail(String message) {
		this.status = Status.FAILED;
		// Truncated: a stack trace in a list view is unreadable, and the full cause is logged.
		this.errorMessage = (message == null) ? "unknown error"
				: message.substring(0, Math.min(message.length(), 1000));
		finish();
	}

	private void finish() {
		this.finishedAt = Instant.now();
		if (this.startedAt != null) {
			this.durationMs = this.finishedAt.toEpochMilli() - this.startedAt.toEpochMilli();
		}
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}



	public UUID getDocumentId() {
		return this.documentId;
	}

	public void setDocumentId(UUID documentId) {
		this.documentId = documentId;
	}

	public Type getType() {
		return this.type;
	}

	public void setType(Type type) {
		this.type = type;
	}

	public Status getStatus() {
		return this.status;
	}

	public void setStatus(Status status) {
		this.status = status;
	}

	public int getChunksWritten() {
		return this.chunksWritten;
	}

	public void setChunksWritten(int chunksWritten) {
		this.chunksWritten = chunksWritten;
	}

	public Instant getStartedAt() {
		return this.startedAt;
	}

	public void setStartedAt(Instant startedAt) {
		this.startedAt = startedAt;
	}

	public Instant getFinishedAt() {
		return this.finishedAt;
	}

	public void setFinishedAt(Instant finishedAt) {
		this.finishedAt = finishedAt;
	}

	public Long getDurationMs() {
		return this.durationMs;
	}

	public void setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
	}

	public String getErrorMessage() {
		return this.errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

}
