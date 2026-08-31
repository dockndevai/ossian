package io.github.dockndevai.ossian.namespace;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A partition of one tenant's corpus.
 *
 * <p>Not a security boundary — that is the tenant, and it comes from the token. A namespace is
 * an organisational one and comes from the request, so a user may read across their own
 * namespaces freely. Conflating the two would be a serious mistake: it would put the choice of
 * what a caller may see into the caller's own hands.
 */
@Entity
@Table(name = "namespaces")
public class NamespaceEntity {

	/** The namespace every tenant has and cannot delete. */
	public static final String DEFAULT = "default";

	@Id
	@GeneratedValue
	private UUID id;


	@Column(nullable = false)
	private String name;

	private String description;

	/**
	 * Chunking for this namespace, or null to use the installation default.
	 *
	 * <p>Changing it affects documents indexed afterwards. Existing chunks keep their shape until
	 * reindexed, which is the same rule as the global setting and for the same reason: silently
	 * mixing two chunking strategies in one corpus is worse than a stale one.
	 */
	@Column(name = "chunk_size")
	private Integer chunkSize;

	@Column(name = "chunk_overlap")
	private Integer chunkOverlap;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	/**
	 * Normalises a name to a slug.
	 *
	 * <p>The value ends up in a filter expression and in URLs, so it is constrained here rather
	 * than trusted: anything outside the allowed set becomes a hyphen.
	 */
	public static String slug(String raw) {
		if (raw == null || raw.isBlank()) {
			return DEFAULT;
		}
		String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
		if (s.isEmpty()) {
			return DEFAULT;
		}
		return (s.length() > 128) ? s.substring(0, 128) : s;
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}



	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}


	public Integer getChunkSize() {
		return this.chunkSize;
	}

	public void setChunkSize(Integer chunkSize) {
		this.chunkSize = chunkSize;
	}

	public Integer getChunkOverlap() {
		return this.chunkOverlap;
	}

	public void setChunkOverlap(Integer chunkOverlap) {
		this.chunkOverlap = chunkOverlap;
	}

}
