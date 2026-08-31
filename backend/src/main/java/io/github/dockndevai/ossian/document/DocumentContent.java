package io.github.dockndevai.ossian.document;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The original uploaded bytes, in their own table.
 * <p>
 * Separated from {@link DocumentEntity} on purpose: the document list is read constantly and a
 * blob column on that entity would be dragged into memory on every page, even with lazy
 * loading, the moment anything touched the entity outside a session.
 */
@Entity
@Table(name = "document_content")
public class DocumentContent {

	@Id
	@Column(name = "document_id")
	private UUID documentId;

	@Column(nullable = false)
	private byte[] content;

	protected DocumentContent() {
	}

	public DocumentContent(UUID documentId, byte[] content) {
		this.documentId = documentId;
		this.content = content;
	}

	public UUID getDocumentId() {
		return this.documentId;
	}

	public void setDocumentId(UUID documentId) {
		this.documentId = documentId;
	}

	public byte[] getContent() {
		return this.content;
	}

	public void setContent(byte[] content) {
		this.content = content;
	}

}
