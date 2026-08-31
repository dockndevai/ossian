package io.github.dockndevai.openbook;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.dockndevai.openbook.config.KeycloakRoleConverter;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the claim-to-authority mapping that MockMvc's {@code jwt()} post-processor bypasses.
 * Without this, a Keycloak token carrying the admin role would 403 and nothing would catch it.
 */
class KeycloakRoleConverterTests {

	private final KeycloakRoleConverter converter = new KeycloakRoleConverter();

	private Jwt jwt(Map<String, Object> claims) {
		Jwt.Builder b = Jwt.withTokenValue("token")
			.header("alg", "RS256")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(300))
			.subject("user");
		claims.forEach(b::claim);
		return b.build();
	}

	@Test
	void mapsRealmRolesToPrefixedAuthorities() {
		var authorities = this.converter
			.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("openbook-admin", "openbook-user")))));

		assertThat(authorities).extracting(GrantedAuthority::getAuthority)
			.contains("ROLE_openbook-admin", "ROLE_openbook-user");
	}

	@Test
	void tokenWithoutRealmAccessYieldsNoRoles() {
		assertThat(this.converter.convert(jwt(Map.of("tenant", "acme")))).isEmpty();
	}

	@Test
	void malformedRealmAccessIsIgnoredRatherThanThrowing() {
		// A token is attacker-influenced input: a surprising shape must not 500 the request.
		assertThat(this.converter.convert(jwt(Map.of("realm_access", "not-an-object")))).isEmpty();
		assertThat(this.converter.convert(jwt(Map.of("realm_access", Map.of("roles", "not-a-list"))))).isEmpty();
	}

	@Test
	void scopesAreStillHonoured() {
		var authorities = this.converter.convert(jwt(Map.of("scope", "openid profile")));

		assertThat(authorities).extracting(GrantedAuthority::getAuthority)
			.contains("SCOPE_openid", "SCOPE_profile");
	}

}
