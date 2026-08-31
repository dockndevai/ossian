package io.github.dockndevai.ossian.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps Keycloak realm roles onto Spring Security authorities.
 * <p>
 * Keycloak puts realm roles under {@code realm_access.roles}, which Spring Security does not
 * read by default — without this every {@code hasRole(...)} check silently fails and every
 * admin endpoint returns 403 for a genuinely-privileged user.
 * <p>
 * Extracted from {@code SecurityConfig} so the mapping can be unit-tested: MockMvc's
 * {@code jwt()} post-processor supplies its own authorities and bypasses the converter
 * entirely, so an MVC test can never exercise this code path.
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		Collection<GrantedAuthority> authorities = new ArrayList<>(this.scopes.convert(jwt));
		Object realmAccess = jwt.getClaim("realm_access");
		if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
			for (Object role : roles) {
				if (role != null) {
					// ROLE_ prefix is what hasRole(...) looks for; hasAuthority would not match.
					authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
				}
			}
		}
		return authorities;
	}

}
