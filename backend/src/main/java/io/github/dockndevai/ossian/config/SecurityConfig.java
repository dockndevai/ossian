package io.github.dockndevai.ossian.config;

import java.util.List;

import io.github.dockndevai.ossian.apikey.ApiKeyAuthenticationFilter;
import io.github.dockndevai.ossian.apikey.ApiKeyRepository;
import io.github.dockndevai.ossian.apikey.ApiKeyService;
import io.github.dockndevai.ossian.caller.CallerContext;
import io.github.dockndevai.ossian.ratelimit.RateLimitFilter;
import io.github.dockndevai.ossian.ratelimit.RateLimiter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless resource-server security against Keycloak.
 * <p>
 * The React app is a public OIDC client that obtains a token from Keycloak directly and sends it
 * as a bearer; this service never issues or stores a session, so there is no CSRF surface to
 * protect and no cookie to steal.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	/**
	 * The {@code @Qualifier} is required, not decorative: Spring MVC's
	 * {@code mvcHandlerMappingIntrospector} also implements {@link CorsConfigurationSource}, so
	 * injecting by type alone is ambiguous and the context fails to start.
	 */
	@Bean
	SecurityFilterChain filterChain(HttpSecurity http,
			@Qualifier("corsConfigurationSource") CorsConfigurationSource cors, ApiKeyService apiKeys,
			RateLimiter limiter, CallerContext caller, ApiKeyRepository keyRepository,
			OssianProperties properties) throws Exception {
		return http
			.cors(c -> c.configurationSource(cors))
			// No cookies, no sessions: nothing for a forged request to ride on.
			.csrf(csrf -> csrf.disable())
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/info").permitAll()
				.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				// Everything that touches documents or the retrieval layer needs a token.
				.requestMatchers("/api/admin/**").hasRole("ossian-admin")
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth
				// Without this the resource server still resolves an "Authorization: Bearer osk_..."
				// header, fails to decode it as a JWT, and returns 401 over the top of the
				// authentication the key filter already established.
				.bearerTokenResolver(jwtOnlyResolver())
				.jwt(jwt -> jwt.jwtAuthenticationConverter(converter())))
			// Before the bearer-token filter, and only acts on credentials starting with osk_.
			// A Keycloak token passes straight through to the resource server, so nothing about
			// how people sign in changes.
			.addFilterBefore(new ApiKeyAuthenticationFilter(apiKeys), BearerTokenAuthenticationFilter.class)
			// After authorisation, so the caller is known and an anonymous request cannot spend
			// somebody else's allowance.
			.addFilterAfter(new RateLimitFilter(limiter, caller, keyRepository, properties),
					org.springframework.security.web.access.intercept.AuthorizationFilter.class)
			.build();
	}

	/**
	 * Resolves bearer tokens for the OAuth2 resource server, except ours.
	 *
	 * <p>API keys are accepted in the Authorization header because most HTTP clients expect
	 * credentials there. Returning null for them tells the resource server there is no token to
	 * decode, leaving the authentication the key filter already set in place.
	 */
	private BearerTokenResolver jwtOnlyResolver() {
		DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
		return request -> {
			String token = delegate.resolve(request);
			return (token != null && token.startsWith(ApiKeyService.PREFIX)) ? null : token;
		};
	}

	private JwtAuthenticationConverter converter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter());
		return converter;
	}

	/** The frontend is a separate origin in every environment, so CORS is load-bearing. */
	@Bean
	CorsConfigurationSource corsConfigurationSource(OssianProperties properties) {
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(properties.getCors().getAllowedOrigins());
		config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		config.setAllowedHeaders(List.of("*"));
		config.setExposedHeaders(List.of("Location"));
		config.setAllowCredentials(true);
		config.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", config);
		return source;
	}

}
