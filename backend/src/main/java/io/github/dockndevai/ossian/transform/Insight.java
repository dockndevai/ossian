package io.github.dockndevai.ossian.transform;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The output of running one transformation over one document.
 *
 * <p>It keeps the name and the exact prompt it came from, not just a foreign key. Prompts get
 * edited and transformations get deleted; an insight whose prompt has since changed is otherwise
 * unexplainable — you cannot tell whether it is stale or whether the model simply said something
 * strange that day.
 */
@Entity
@Table(name = "insights")
public class Insight {

	@Id
	@GeneratedValue
	private UUID id;


	@Column(name = "document_id", nullable = false)
	private UUID documentId;

	@Column(name = "transformation_id")
	private UUID transformationId;

	@Column(name = "transformation_name", nullable = false)
	private String transformationName;

	@Column(name = "prompt_used", nullable = false, columnDefinition = "text")
	private String promptUsed;

	@Column(nullable = false, columnDefinition = "text")
	private String output;

	private String model;

	@Column(nullable = false)
	private int passes = 1;

	@Column(name = "duration_ms")
	private Long durationMs;

	@Column(name = "created_by")
	private String createdBy;

	/**
	 * Identifies the inputs this output came from: the source text, the prompt and the model.
	 * Two runs sharing a key must produce the same output, which is what makes reuse safe.
	 */
	@Column(name = "cache_key", length = 64)
	private String cacheKey;

	/** True when this row was returned from an earlier identical run rather than recomputed. */
	@Column(name = "from_cache", nullable = false)
	private boolean fromCache;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

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

	public UUID getTransformationId() {
		return this.transformationId;
	}

	public void setTransformationId(UUID transformationId) {
		this.transformationId = transformationId;
	}

	public String getTransformationName() {
		return this.transformationName;
	}

	public void setTransformationName(String transformationName) {
		this.transformationName = transformationName;
	}

	public String getPromptUsed() {
		return this.promptUsed;
	}

	public void setPromptUsed(String promptUsed) {
		this.promptUsed = promptUsed;
	}

	public String getOutput() {
		return this.output;
	}

	public void setOutput(String output) {
		this.output = output;
	}

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public int getPasses() {
		return this.passes;
	}

	public void setPasses(int passes) {
		this.passes = passes;
	}

	public Long getDurationMs() {
		return this.durationMs;
	}

	public void setDurationMs(Long durationMs) {
		this.durationMs = durationMs;
	}

	public String getCreatedBy() {
		return this.createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}


	public String getCacheKey() {
		return this.cacheKey;
	}

	public void setCacheKey(String cacheKey) {
		this.cacheKey = cacheKey;
	}

	public boolean isFromCache() {
		return this.fromCache;
	}

	public void setFromCache(boolean fromCache) {
		this.fromCache = fromCache;
	}

}
