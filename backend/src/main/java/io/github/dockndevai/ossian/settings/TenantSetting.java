package io.github.dockndevai.ossian.settings;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One overridden setting for one tenant. The file supplies the default; this overrides it. */
@Entity
@Table(name = "tenant_settings")
@IdClass(TenantSetting.Key.class)
public class TenantSetting {

	/** Composite key: settings are per tenant, and a tenant never sees another's. */
	public record Key(String tenantId, String key) implements java.io.Serializable {
		public Key() {
			this(null, null);
		}
	}

	@Id
	@Column(name = "tenant_id", nullable = false)
	private String tenantId;

	@Id
	@Column(name = "key", nullable = false)
	private String key;

	@Column(nullable = false)
	private String value;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	@Column(name = "updated_by")
	private String updatedBy;

	public String getTenantId() {
		return this.tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getKey() {
		return this.key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getValue() {
		return this.value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

	public String getUpdatedBy() {
		return this.updatedBy;
	}

	public void setUpdatedBy(String updatedBy) {
		this.updatedBy = updatedBy;
	}

}
