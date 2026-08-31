package io.github.dockndevai.openbook.tenant;

import java.util.List;
import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's tenant and identity from the Keycloak token.
 * <p>
 * Tenancy is read from a JWT claim and never from a request header or query parameter — a
 * client that could name its own tenant could read another tenant's documents. Every document,
 * chunk and retrieval query is scoped by the value this returns.
 */
@Component
public class TenantContext {

	/** Claim carrying the tenant. Add it in Keycloak with a User Attribute protocol mapper. */
	public static final String TENANT_CLAIM = "tenant";

	private static final String FALLBACK_TENANT = "default";

	/** The tenant for the current request. */
	public String tenantId() {
		return jwt().map(j -> {
			Object v = j.getClaim(TENANT_CLAIM);
			return (v == null || v.toString().isBlank()) ? FALLBACK_TENANT : v.toString();
		}).orElse(FALLBACK_TENANT);
	}

	/** Stable identifier for the calling user, used for audit and per-user rate accounting. */
	public String subject() {
		return jwt().map(Jwt::getSubject).orElse("anonymous");
	}

	public String preferredUsername() {
		return jwt().map(j -> j.getClaimAsString("preferred_username")).orElse("anonymous");
	}

	/** Keycloak realm roles, flattened out of {@code realm_access.roles}. */
	@SuppressWarnings("unchecked")
	public List<String> roles() {
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
