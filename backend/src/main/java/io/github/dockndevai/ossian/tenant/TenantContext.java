package io.github.dockndevai.ossian.tenant;

import java.util.List;
import java.util.Optional;

import io.github.dockndevai.ossian.apikey.ApiKeyPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's tenant and identity.
 * <p>
 * Two ways in, one answer. A person arrives with a Keycloak token and the tenant is read from a
 * claim; a machine arrives with an API key and the tenant comes from the key's own row. Neither
 * is ever read from a request header or query parameter — a client that could name its own
 * tenant could read another tenant's documents. Every document, chunk and retrieval query is
 * scoped by the value this returns.
 */
@Component
public class TenantContext {

	/** Claim carrying the tenant. Add it in Keycloak with a User Attribute protocol mapper. */
	public static final String TENANT_CLAIM = "tenant";

	private static final String FALLBACK_TENANT = "default";

	/** The tenant for the current request. */
	public String tenantId() {
		Optional<ApiKeyPrincipal> key = apiKey();
		if (key.isPresent()) {
			return key.get().tenantId();
		}
		return jwt().map(j -> {
			Object v = j.getClaim(TENANT_CLAIM);
			return (v == null || v.toString().isBlank()) ? FALLBACK_TENANT : v.toString();
		}).orElse(FALLBACK_TENANT);
	}

	/** Stable identifier for the caller, used for audit and per-caller rate accounting. */
	public String subject() {
		Optional<ApiKeyPrincipal> key = apiKey();
		if (key.isPresent()) {
			return "key:" + key.get().keyId();
		}
		return jwt().map(Jwt::getSubject).orElse("anonymous");
	}

	public String preferredUsername() {
		Optional<ApiKeyPrincipal> key = apiKey();
		if (key.isPresent()) {
			return key.get().actor();
		}
		return jwt().map(j -> j.getClaimAsString("preferred_username")).orElse("anonymous");
	}

	/**
	 * The namespace this caller is confined to, if any.
	 *
	 * <p>Only keys can be confined. A person's reach is their tenant; a key issued to one
	 * pipeline should not be able to read the rest of the corpus if it leaks.
	 */
	public Optional<String> confinedNamespace() {
		return apiKey().map(ApiKeyPrincipal::namespace).filter(n -> n != null && !n.isBlank());
	}

	/** True when this request authenticated with a key rather than as a person. */
	public boolean isMachine() {
		return apiKey().isPresent();
	}

	private Optional<ApiKeyPrincipal> apiKey() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof ApiKeyPrincipal principal) {
			return Optional.of(principal);
		}
		return Optional.empty();
	}

	/** Keycloak realm roles, flattened out of {@code realm_access.roles}. */
	@SuppressWarnings("unchecked")
	public List<String> roles() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof ApiKeyPrincipal) {
			return auth.getAuthorities()
				.stream()
				.map(a -> a.getAuthority().startsWith("ROLE_") ? a.getAuthority().substring(5) : a.getAuthority())
				.toList();
		}
		return jwt().map(j -> {
			Object realmAccess = j.getClaim("realm_access");
			if (realmAccess instanceof java.util.Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
				return (List<String>) roles;
			}
			return List.<String>of();
		}).orElse(List.of());
	}

	private Optional<Jwt> jwt() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
			return Optional.of(jwt);
		}
		return Optional.empty();
	}

}
