package io.github.dockndevai.ossian.caller;

import java.util.List;
import java.util.Optional;

import io.github.dockndevai.ossian.apikey.ApiKeyPrincipal;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Who is making this request.
 *
 * <p>Two ways in, one answer. A person arrives with a Keycloak token; a machine arrives with an
 * API key. Everything downstream is indifferent to which.
 *
 * <p>This deployment serves one organisation. Partitioning within it is by namespace, which is
 * chosen per request and is organisational rather than a security boundary — the boundary is the
 * deployment itself. A key may still be confined to one namespace, which is a limit on that
 * credential rather than a property of the caller's identity.
 */
@Component
public class CallerContext {

	private static final String ANONYMOUS = "anonymous";

	/** Stable identifier for the caller, used for audit and per-caller rate accounting. */
	public String subject() {
		Optional<ApiKeyPrincipal> key = apiKey();
		if (key.isPresent()) {
			return "key:" + key.get().keyId();
		}
		return jwt().map(Jwt::getSubject).orElse(ANONYMOUS);
	}

	/** A readable name for the caller, for anything a person will look at later. */
	public String username() {
		Optional<ApiKeyPrincipal> key = apiKey();
		if (key.isPresent()) {
			return key.get().actor();
		}
		return jwt().map(j -> j.getClaimAsString("preferred_username")).orElse(ANONYMOUS);
	}

	/** Realm roles for a person, or the roles carried by a key. */
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

	/**
	 * The namespace this caller is confined to, if any.
	 *
	 * <p>Only keys can be confined. A person's reach is the whole installation; a key issued to
	 * one pipeline should not be able to read the rest of the corpus if it leaks.
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

	private Optional<Jwt> jwt() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
			return Optional.of(jwt);
		}
		return Optional.empty();
	}

}
