package io.github.dockndevai.ossian.config;

import java.util.List;

import io.github.dockndevai.ossian.tenant.TenantContext;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
			@Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
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
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter())))
			.build();
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

	@Bean
	TenantContext tenantContext() {
		return new TenantContext();
	}

}
