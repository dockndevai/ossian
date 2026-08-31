package io.github.dockndevai.ossian;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security property this whole design rests on: a caller sees only their own tenant's
 * documents, and tenancy comes from the token rather than anything the client can set.
 * <p>
 * Uses a real Postgres with pgvector, because the tenant scoping lives in SQL predicates and a
 * mocked repository would prove nothing about them.
 */
@Testcontainers
@SpringBootTest(properties = {
		// Exclude the real vector store and model autoconfigurations. TestAiConfig supplies
		// stand-ins under the same bean names, and Boot disables bean overriding by default,
		// so leaving these on is a BeanDefinitionOverrideException rather than a silent win.
		"spring.autoconfigure.exclude="
				+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
				+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
		"spring.cache.type=none" })
@AutoConfigureMockMvc
@Import(TestAiConfig.class)
class TenantIsolationTests {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
			DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
		.withDatabaseName("ossian")
		.withUsername("ossian")
		.withPassword("ossian");

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		// Any issuer will do: MockMvc's jwt() post-processor bypasses signature validation,
		// and what is under test is authorisation, not token verification.
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost/realms/test");
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/certs");
		registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> "false");
		registry.add("spring.data.redis.host", () -> "localhost");
		registry.add("spring.cache.type", () -> "none");
	}

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentRepository documents;

	@BeforeEach
	void seed() {
		this.documents.deleteAll();
		this.documents.save(doc("acme", "acme-runbook.pdf"));
		this.documents.save(doc("globex", "globex-secret.pdf"));
	}

	private DocumentEntity doc(String tenant, String filename) {
		DocumentEntity d = new DocumentEntity();
		d.setTenantId(tenant);
		d.setFilename(filename);
		d.setSizeBytes(1234);
		d.setContentHash(UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000");
		d.setStatus(DocumentEntity.Status.READY);
		return d;
	}

	@Test
	void anonymousIsRejected() throws Exception {
		this.mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
	}

	@Test
	void aTenantSeesOnlyItsOwnDocuments() throws Exception {
		this.mvc.perform(get("/api/documents").with(jwt().jwt(j -> j.claim("tenant", "acme"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].filename").value("acme-runbook.pdf"));

		this.mvc.perform(get("/api/documents").with(jwt().jwt(j -> j.claim("tenant", "globex"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].filename").value("globex-secret.pdf"));
	}

	@Test
	void aDocumentIdFromAnotherTenantIs404NotALeak() throws Exception {
		UUID globexId = this.documents.findByTenantIdAndContentHash("globex",
				this.documents.findAll().stream()
					.filter(d -> d.getTenantId().equals("globex")).findFirst().orElseThrow().getContentHash())
			.orElseThrow().getId();

		// Knowing the id must not be enough. 404 rather than 403 so the response does not even
		// confirm that the document exists.
		this.mvc.perform(get("/api/documents/" + globexId).with(jwt().jwt(j -> j.claim("tenant", "acme"))))
			.andExpect(status().isNotFound());
	}

	@Test
	void adminEndpointsRequireTheAdminRole() throws Exception {
		this.mvc.perform(get("/api/admin/stats/corpus").with(jwt().jwt(j -> j.claim("tenant", "acme"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void anAdminRoleUnlocksTheAdminEndpoints() throws Exception {
		// jwt() supplies its own authorities and bypasses KeycloakRoleConverter, so the role is
		// granted explicitly here. The claim-to-authority mapping is covered by
		// KeycloakRoleConverterTests instead.
		this.mvc
			.perform(get("/api/admin/stats/corpus").with(jwt().jwt(j -> j.claim("tenant", "acme"))
				.authorities(new SimpleGrantedAuthority("ROLE_ossian-admin"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documents").value(1));
	}

	@Test
	void aTokenWithNoTenantClaimFallsBackAndSeesNeitherTenant() throws Exception {
		this.mvc.perform(get("/api/documents").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void statsAreScopedToTheCallersTenant() throws Exception {
		var adminJwt = jwt().jwt(j -> j.claim("tenant", "globex"))
			.authorities(new SimpleGrantedAuthority("ROLE_ossian-admin"));

		this.mvc.perform(get("/api/admin/stats/corpus").with(adminJwt))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documents").value(1));

		assertThat(this.documents.countByTenantId("globex")).isEqualTo(1);
		assertThat(this.documents.count()).isEqualTo(2);
	}

}
