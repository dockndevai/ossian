package io.github.dockndevai.ossian.ratelimit;

import java.io.IOException;

import io.github.dockndevai.ossian.apikey.ApiKeyPrincipal;
import io.github.dockndevai.ossian.apikey.ApiKeyRepository;
import io.github.dockndevai.ossian.caller.CallerContext;
import io.github.dockndevai.ossian.config.OssianProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds each caller to their own request rate.
 *
 * <p>Per caller, not per endpoint. One agent in a retry loop is the realistic failure — it will
 * exhaust the embedding budget or the connection pool for everyone else — and it does that
 * through whichever endpoint it happens to be calling.
 *
 * <p>Runs after authentication, because the identity is the whole point: limiting by IP address
 * puts every agent behind one NAT in the same bucket and gives an attacker a way to exhaust
 * somebody else's allowance.
 */
public class RateLimitFilter extends OncePerRequestFilter {

	private final RateLimiter limiter;

	private final CallerContext caller;

	private final ApiKeyRepository keys;

	private final OssianProperties properties;

	public RateLimitFilter(RateLimiter limiter, CallerContext caller, ApiKeyRepository keys,
			OssianProperties properties) {
		this.limiter = limiter;
		this.caller = caller;
		this.keys = keys;
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		// Health and the metrics scrape are exempt. Rate-limiting the thing that reports whether
		// the service is up means losing visibility exactly when load is highest.
		return !path.startsWith("/api/") || path.startsWith("/api/admin/pipeline");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) {
			// Unauthenticated requests are rejected by security anyway; counting them here would
			// let anonymous traffic spend a real caller's allowance.
			chain.doFilter(request, response);
			return;
		}

		int perMinute = limitFor(auth);
		RateLimiter.Decision decision = this.limiter.check(this.caller.subject(), perMinute);

		response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
		response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, decision.remaining())));

		if (!decision.allowed()) {
			long seconds = Math.max(1, decision.retryAfterMillis() / 1000);
			// Retry-After in seconds, per RFC 9110. A client that is told when to come back
			// backs off correctly; one that is only told "no" retries immediately and makes it
			// worse.
			response.setHeader("Retry-After", String.valueOf(seconds));
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setContentType("application/json");
			response.getWriter()
				.write("{\"message\":\"Rate limit exceeded. Retry after " + seconds + "s.\","
						+ "\"limitPerMinute\":" + decision.limit() + "}");
			return;
		}
		chain.doFilter(request, response);
	}

	/** A key's own limit if it has one, otherwise the installation default for its kind. */
	private int limitFor(Authentication auth) {
		if (auth.getPrincipal() instanceof ApiKeyPrincipal principal) {
			return this.keys.findById(principal.keyId())
				.map(key -> key.getRequestsPerMinute() == null
						? this.properties.getRateLimit().getKeyRequestsPerMinute()
						: key.getRequestsPerMinute())
				.orElse(this.properties.getRateLimit().getKeyRequestsPerMinute());
		}
		// People get a higher allowance than machines by default: a console page fans out to
		// several endpoints at once, and a person cannot loop.
		return this.properties.getRateLimit().getUserRequestsPerMinute();
	}

}
