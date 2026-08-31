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
 * A record of one inbound change event.
 *
 * <p>Its reason for existing is idempotency. A change-data-capture pipeline redelivers after a
 * crash, a rebalance, or an at-least-once broker doing exactly what it promised — that is the
 * normal case, not an error. The caller's event id is stored with a unique constraint per
 * tenant, so the second delivery is recognised and does not produce a second document.
 */
@Entity
@Table(name = "ingest_events")
public class IngestEvent {

	public enum Operation {

		/** Create the document, or replace it if the external id is already known. */
		UPSERT,

		/** Remove the document and its chunks. */
		DELETE

	}

	public enum Status {

		ACCEPTED, DUPLICATE, FAILED

	}

	@Id
	@GeneratedValue
	private UUID id;


	@Column(name = "event_id", nullable = false)
	private String eventId;

	@Column(nullable = false)
	private String namespace = "default";

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Operation operation;

	@Column(name = "external_id", nullable = false)
	private String externalId;

	private String source;

	@Column(name = "document_id")
	private UUID documentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACCEPTED;

	@Column(name = "error_message")
	private String errorMessage;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}



	public String getEventId() {
		return this.eventId;
	}

	public void setEventId(String eventId) {
		this.eventId = eventId;
	}

	public String getNamespace() {
		return this.namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public Operation getOperation() {
		return this.operation;
	}

	public void setOperation(Operation operation) {
		this.operation = operation;
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

	public UUID getDocumentId() {
		return this.documentId;
	}

	public void setDocumentId(UUID documentId) {
		this.documentId = documentId;
	}

	public Status getStatus() {
		return this.status;
	}

	public void setStatus(Status status) {
		this.status = status;
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
