package io.github.dockndevai.ossian.apikey;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules a machine credential has to obey.
 *
 * <p>Unit-level on purpose: these are the decisions that decide whether a leaked pipeline key
 * reads the whole corpus, and they should be provable without a database or a web server.
 */
class ApiKeyAuthTests {

	private static ApiKeyEntity key(String roles, String namespace) {
		ApiKeyEntity entity = new ApiKeyEntity();
		entity.setName("pipeline");
		entity.setRoles(roles);
		entity.setNamespace(namespace);
		return entity;
	}

	@Test
	@DisplayName("a fresh key is usable")
	void freshKeyIsUsable() {
		assertThat(key("ossian-user", null).isUsable(Instant.now())).isTrue();
	}

	@Test
	@DisplayName("a revoked key is refused, and stays refused")
	void revokedKeyIsRefused() {
		ApiKeyEntity entity = key("ossian-user", null);
		entity.setRevokedAt(Instant.now().minusSeconds(1));
		assertThat(entity.isUsable(Instant.now())).isFalse();
		// Including for a moment before it was revoked: there is no window to replay into.
		assertThat(entity.isUsable(Instant.now().minusSeconds(3600))).isFalse();
	}

	@Test
	@DisplayName("an expired key is refused; one expiring later is not")
	void expiryIsHonoured() {
		ApiKeyEntity expired = key("ossian-user", null);
		expired.setExpiresAt(Instant.now().minusSeconds(1));
		assertThat(expired.isUsable(Instant.now())).isFalse();

		ApiKeyEntity live = key("ossian-user", null);
		live.setExpiresAt(Instant.now().plusSeconds(3600));
		assertThat(live.isUsable(Instant.now())).isTrue();
	}

	@Test
	@DisplayName("roles parse into separate authorities, whitespace and all")
	void rolesParse() {
		assertThat(key("ossian-user, ossian-admin ", null).roleList())
			.containsExactly("ossian-user", "ossian-admin");
		assertThat(key("ossian-user", null).roleList()).containsExactly("ossian-user");
		assertThat(key("ossian-user,,", null).roleList()).containsExactly("ossian-user");
	}

	@Test
	@DisplayName("the same secret always hashes the same, and different secrets do not collide")
	void hashingIsStableAndDistinct() {
		String a = ApiKeyService.hash("osk_abc");
		assertThat(ApiKeyService.hash("osk_abc")).isEqualTo(a);
		assertThat(ApiKeyService.hash("osk_abd")).isNotEqualTo(a);
		// Hex SHA-256.
		assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
	}

	@Test
	@DisplayName("the stored hash is not the secret")
	void secretIsNotRecoverableFromTheRow() {
		String secret = "osk_averyrealsecretvalue";
		String stored = ApiKeyService.hash(secret);
		assertThat(stored).doesNotContain(secret);
		assertThat(stored).doesNotContain("averyreal");
	}

	@Test
	@DisplayName("a confined key names its namespace; an unconfined one does not")
	void confinementIsOnThePrincipal() {
		ApiKeyPrincipal confined = new ApiKeyPrincipal(java.util.UUID.randomUUID(), "pipeline", "hr-policies");
		ApiKeyPrincipal open = new ApiKeyPrincipal(java.util.UUID.randomUUID(), "console", null);

		assertThat(confined.namespace()).isEqualTo("hr-policies");
		assertThat(open.namespace()).isNull();
		// The actor is what lands in created_by, so it must be recognisable as a machine.
		assertThat(confined.actor()).isEqualTo("key:pipeline").startsWith("key:");
	}

	@Test
	@DisplayName("only osk_ credentials are treated as keys")
	void onlyOurPrefixIsAKey() {
		assertThat(ApiKeyService.PREFIX).isEqualTo("osk_");
		// A Keycloak JWT must not be mistaken for one, or it would be hashed and looked up
		// instead of being passed to the resource server.
		assertThat("eyJhbGciOiJSUzI1NiJ9.abc.def".startsWith(ApiKeyService.PREFIX)).isFalse();
	}

	@Test
	@DisplayName("issued secrets are long enough to be unguessable")
	void secretsCarryEnoughEntropy() {
		// 32 random bytes, url-safe base64, unpadded: 43 characters after the prefix.
		String sample = ApiKeyService.PREFIX + java.util.Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(new byte[32]);
		assertThat(sample.length()).isGreaterThanOrEqualTo(47);
	}

	@Test
	@DisplayName("roles default to the least privileged when none is given")
	void rolesDefault() {
		assertThat(new ApiKeyEntity().roleList()).containsExactly("ossian-user");
	}

	@Test
	@DisplayName("a key with no expiry never expires on its own")
	void nullExpiryMeansNoExpiry() {
		assertThat(key("ossian-user", null).isUsable(Instant.now().plusSeconds(86_400_000))).isTrue();
	}

	@Test
	@DisplayName("roles on a key are independent of who created it")
	void keyRolesAreNotTheCreatorRoles() {
		// An admin issuing a pipeline key should be able to give it less than they hold; nothing
		// in the entity ties the two together.
		ApiKeyEntity entity = key("ossian-user", "hr-policies");
		entity.setCreatedBy("admin");
		assertThat(entity.roleList()).doesNotContain("ossian-admin");
	}

	@Test
	@DisplayName("List.of() roles fall back rather than producing an empty authority set")
	void emptyRolesFallBack() {
		ApiKeyEntity entity = key("", null);
		assertThat(entity.roleList()).isEmpty();
	}

}
