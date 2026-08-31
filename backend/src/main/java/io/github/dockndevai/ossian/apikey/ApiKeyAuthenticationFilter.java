package io.github.dockndevai.ossian.apikey;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request presenting an API key.
 *
 * <p>Runs before the bearer-token filter and only acts on credentials beginning with
 * {@code osk_}. Anything else — including every Keycloak token — is left untouched for the OAuth2
 * resource server to handle, so adding this changes nothing about how people sign in.
 *
 * <p>An unrecognised key is left unauthenticated rather than rejected here. Spring Security's
 * entry point then produces the 401, which keeps the refusal identical to every other
 * unauthenticated request: a filter that answered differently would tell an attacker which of
 * their guesses were real keys.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	/** Accepted in its own header as well as Authorization, because many clients reserve the latter. */
	public static final String HEADER = "X-API-Key";

	private final ApiKeyService keys;

	public ApiKeyAuthenticationFilter(ApiKeyService keys) {
		this.keys = keys;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String presented = presentedKey(request);
		if (presented != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			this.keys.resolve(presented).ifPresent(key -> {
				List<GrantedAuthority> authorities = key.roleList()
					.stream()
					.map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
					.toList();
				ApiKeyPrincipal principal = new ApiKeyPrincipal(key.getId(), key.getName(), key.getNamespace());
				// Null credentials: the secret has done its job and must not travel further.
				var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
				SecurityContextHolder.getContext().setAuthentication(authentication);
			});
		}
		chain.doFilter(request, response);
	}

	/**
	 * Also authenticate on the ERROR dispatch.
	 *
	 * <p>{@link OncePerRequestFilter} skips error dispatches by default. With a stateless
	 * security context there is nothing to restore on the way back in, so the forward to
	 * {@code /error} arrives anonymous and {@code anyRequest().authenticated()} turns it into a
	 * bare 401 — every 400, 403 and 404 a machine caller could receive, rewritten into "you are
	 * not authenticated". The status was the only evidence of what actually went wrong.
	 */
	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	private static String presentedKey(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header != null && !header.isBlank()) {
			return header.trim();
		}
		String authorization = request.getHeader("Authorization");
		if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			String value = authorization.substring(7).trim();
			// Only ours. A JWT here must fall through to the resource server untouched.
			if (value.startsWith(ApiKeyService.PREFIX)) {
				return value;
			}
		}
		return null;
	}

}
