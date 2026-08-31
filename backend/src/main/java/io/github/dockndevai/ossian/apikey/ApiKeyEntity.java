package io.github.dockndevai.ossian.apikey;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A machine credential: a tenant, a set of roles, and the hash of a key nobody can read back. */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

	@Id
	@GeneratedValue
	private UUID id;


	@Column(nullable = false)
	private String name;

	@Column(name = "key_hash", nullable = false, length = 64)
	private String keyHash;

	@Column(name = "key_prefix", nullable = false, length = 24)
	private String keyPrefix;

	@Column(nullable = false, length = 500)
	private String roles = "ossian-user";

	@Column(length = 128)
	private String namespace;

	@Column(name = "created_by")
	private String createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "last_used_at")
	private Instant lastUsedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	/**
	 * Whether this key may still be used.
	 *
	 * <p>Revocation and expiry are checked here rather than in the SQL lookup so that a key which
	 * exists but is no longer valid is distinguishable from one that was never issued. They are
	 * the same refusal to the caller and very different things in a log.
	 */
	public boolean isUsable(Instant now) {
		if (this.revokedAt != null) {
			return false;
		}
		return this.expiresAt == null || this.expiresAt.isAfter(now);
	}

	public List<String> roleList() {
		return Arrays.stream(this.roles.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
	}

	public UUID getId() {
		return this.id;
	}



	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getKeyHash() {
		return this.keyHash;
	}

	public void setKeyHash(String keyHash) {
		this.keyHash = keyHash;
	}

	public String getKeyPrefix() {
		return this.keyPrefix;
	}

	public void setKeyPrefix(String keyPrefix) {
		this.keyPrefix = keyPrefix;
	}

	public String getRoles() {
		return this.roles;
	}

	public void setRoles(String roles) {
		this.roles = roles;
	}

	public String getNamespace() {
		return this.namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
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

	public Instant getLastUsedAt() {
		return this.lastUsedAt;
	}

	public void setLastUsedAt(Instant lastUsedAt) {
		this.lastUsedAt = lastUsedAt;
	}

	public Instant getExpiresAt() {
		return this.expiresAt;
	}

	public void setExpiresAt(Instant expiresAt) {
		this.expiresAt = expiresAt;
	}

	public Instant getRevokedAt() {
		return this.revokedAt;
	}

	public void setRevokedAt(Instant revokedAt) {
		this.revokedAt = revokedAt;
	}

}
