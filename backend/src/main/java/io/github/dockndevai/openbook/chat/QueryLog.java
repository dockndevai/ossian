package io.github.dockndevai.openbook.chat;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One answered (or unanswerable) question.
 * <p>
 * The {@code answered=false} rows are the useful ones: they are the questions your corpus could
 * not support, which is the clearest signal of what to ingest next.
 */
@Entity
@Table(name = "query_log")
public class QueryLog {

	@Id
	@GeneratedValue
	private UUID id;

	@Column(name = "tenant_id", nullable = false)
	private String tenantId;

	private String subject;

	@Column(nullable = false)
	private String question;

	@Column(name = "chunks_retrieved", nullable = false)
	private int chunksRetrieved;

	@Column(name = "top_score")
	private Double topScore;

	@Column(nullable = false)
	private boolean answered = true;

	@Column(name = "latency_ms")
	private Long latencyMs;

	@Column(name = "prompt_tokens")
	private Integer promptTokens;

	@Column(name = "completion_tokens")
	private Integer completionTokens;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public String getTenantId() {
		return this.tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getSubject() {
		return this.subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getQuestion() {
		return this.question;
	}

	public void setQuestion(String question) {
		this.question = question;
	}

	public int getChunksRetrieved() {
		return this.chunksRetrieved;
	}

	public void setChunksRetrieved(int chunksRetrieved) {
		this.chunksRetrieved = chunksRetrieved;
	}

	public Double getTopScore() {
		return this.topScore;
	}

	public void setTopScore(Double topScore) {
		this.topScore = topScore;
	}

	public boolean isAnswered() {
		return this.answered;
	}

	public void setAnswered(boolean answered) {
		this.answered = answered;
	}

	public Long getLatencyMs() {
		return this.latencyMs;
	}

	public void setLatencyMs(Long latencyMs) {
		this.latencyMs = latencyMs;
	}

	public Integer getPromptTokens() {
		return this.promptTokens;
	}

	public void setPromptTokens(Integer promptTokens) {
		this.promptTokens = promptTokens;
	}

	public Integer getCompletionTokens() {
		return this.completionTokens;
	}

	public void setCompletionTokens(Integer completionTokens) {
		this.completionTokens = completionTokens;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

}
