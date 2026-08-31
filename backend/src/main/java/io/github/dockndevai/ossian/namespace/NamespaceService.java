package io.github.dockndevai.ossian.namespace;

import java.util.List;

import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, lists and resolves namespaces, always within the caller's tenant. */
@Service
public class NamespaceService {

	private final NamespaceRepository repository;

	private final TenantContext tenant;

	public NamespaceService(NamespaceRepository repository, TenantContext tenant) {
		this.repository = repository;
		this.tenant = tenant;
	}

	/**
	 * All of this tenant's namespaces, creating the default one on first use.
	 *
	 * <p>Created lazily rather than at sign-up because there is no sign-up: a tenant appears the
	 * first time a token carrying it arrives.
	 */
	@Transactional
	public List<NamespaceEntity> list() {
		String t = this.tenant.tenantId();
		if (!this.repository.existsByTenantIdAndName(t, NamespaceEntity.DEFAULT)) {
			create(NamespaceEntity.DEFAULT, "Everything not filed elsewhere");
		}
		return this.repository.findByTenantIdOrderByName(t);
	}

	@Transactional
	public NamespaceEntity create(String rawName, String description) {
		String name = NamespaceEntity.slug(rawName);
		String t = this.tenant.tenantId();
		return this.repository.findByTenantIdAndName(t, name).orElseGet(() -> {
			NamespaceEntity entity = new NamespaceEntity();
			entity.setTenantId(t);
			entity.setName(name);
			entity.setDescription(description);
			return this.repository.save(entity);
		});
	}

	/**
	 * Resolves a requested namespace to one that exists, falling back to the default.
	 *
	 * <p>An unknown namespace resolves to the default rather than erroring, because the
	 * alternative — a typo silently returning an empty corpus — reads as "the documents are
	 * gone" and sends people looking in the wrong place.
	 */
	public String resolve(String requested) {
		if (requested == null || requested.isBlank()) {
			return NamespaceEntity.DEFAULT;
		}
		String name = NamespaceEntity.slug(requested);
		return this.repository.existsByTenantIdAndName(this.tenant.tenantId(), name) ? name
				: NamespaceEntity.DEFAULT;
	}

}
