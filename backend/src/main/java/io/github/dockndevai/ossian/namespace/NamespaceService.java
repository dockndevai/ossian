package io.github.dockndevai.ossian.namespace;

import java.util.List;
import java.util.Optional;

import io.github.dockndevai.ossian.caller.CallerContext;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Creates, lists and resolves namespaces, always within the caller's tenant. */
@Service
public class NamespaceService {

	private final NamespaceRepository repository;

	private final CallerContext tenant;

	public NamespaceService(NamespaceRepository repository, CallerContext tenant) {
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
		if (!this.repository.existsByName(NamespaceEntity.DEFAULT)) {
			create(NamespaceEntity.DEFAULT, "Everything not filed elsewhere");
		}
		return this.repository.findAllByOrderByName();
	}

	@Transactional
	public NamespaceEntity create(String rawName, String description) {
		String name = NamespaceEntity.slug(rawName);
		return this.repository.findByName(name).orElseGet(() -> {
			NamespaceEntity entity = new NamespaceEntity();
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
		Optional<String> confined = this.tenant.confinedNamespace();
		if (confined.isPresent()) {
			String allowed = NamespaceEntity.slug(confined.get());
			// Asking for nothing gets the confinement. Asking for something else is refused
			// rather than quietly redirected: a pipeline writing into the wrong namespace and
			// being told nothing would look like it worked.
			if (requested != null && !requested.isBlank() && !NamespaceEntity.slug(requested).equals(allowed)) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN,
						"This credential is confined to the '" + allowed + "' namespace");
			}
			return allowed;
		}
		if (requested == null || requested.isBlank()) {
			return NamespaceEntity.DEFAULT;
		}
		String name = NamespaceEntity.slug(requested);
		return this.repository.existsByName(name) ? name
				: NamespaceEntity.DEFAULT;
	}

	/**
	 * The namespace filter to apply when none was requested.
	 *
	 * <p>Empty means "every namespace in this tenant", which is the right default for a person.
	 * For a confined credential it is never empty — that is the whole point of the confinement,
	 * and it is why reads have to consult this rather than treating a missing parameter as
	 * permission to see everything.
	 */
	public Optional<String> effectiveFilter(String requested) {
		Optional<String> confined = this.tenant.confinedNamespace();
		if (confined.isPresent()) {
			String allowed = NamespaceEntity.slug(confined.get());
			if (requested != null && !requested.isBlank() && !NamespaceEntity.slug(requested).equals(allowed)) {
				throw new ResponseStatusException(HttpStatus.FORBIDDEN,
						"This credential is confined to the '" + allowed + "' namespace");
			}
			return Optional.of(allowed);
		}
		return (requested == null || requested.isBlank()) ? Optional.empty()
				: Optional.of(NamespaceEntity.slug(requested));
	}

}
