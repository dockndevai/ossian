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
 * Access control, end to end against a real database.
 *
 * <p>This installation serves one organisation, so the boundary is the deployment rather than a
 * tenant column. What still has to hold is that nothing is readable without authenticating, that
 * the admin surface needs the admin role, and that a namespace filter actually narrows rather
 * than merely appearing to.
 *
 * <p>Uses a real Postgres with pgvector, because the scoping lives in SQL predicates and a mocked
 * repository would prove nothing about them.
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
class AccessControlTests {

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
		this.documents.save(doc("default", "runbook.pdf"));
		this.documents.save(doc("hr", "handbook.pdf"));
	}

	private DocumentEntity doc(String namespace, String filename) {
		DocumentEntity d = new DocumentEntity();
		d.setNamespace(namespace);
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
	void anAuthenticatedCallerSeesTheWholeCorpus() throws Exception {
		// One organisation, one corpus: namespaces organise it, they do not hide it from its
		// owner. An absent filter widens to everything the installation holds.
		this.mvc.perform(get("/api/documents").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2));
	}

	@Test
	void aNamespaceFilterActuallyNarrows() throws Exception {
		this.mvc.perform(get("/api/documents").param("namespace", "hr").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].filename").value("handbook.pdf"));
	}

	@Test
	void anUnknownNamespaceFallsBackRatherThanErroring() throws Exception {
		// A typo returning an empty corpus reads as "my documents are gone"; the default is the
		// less alarming and more recoverable answer.
		this.mvc.perform(get("/api/documents").param("namespace", "nope").with(jwt()))
			.andExpect(status().isOk());
	}

	@Test
	void adminEndpointsRequireTheAdminRole() throws Exception {
		this.mvc.perform(get("/api/admin/stats/corpus")
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ossian-user"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void anAdminRoleUnlocksTheAdminEndpoints() throws Exception {
		this.mvc.perform(get("/api/admin/stats/corpus")
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ossian-admin"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documents").value(2));
	}

	@Test
	void statsFollowTheNamespaceFilter() throws Exception {
		this.mvc.perform(get("/api/admin/stats/corpus").param("namespace", "hr")
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ossian-admin"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.documents").value(1));
	}

	@Test
	void aDocumentThatDoesNotExistIs404() throws Exception {
		this.mvc.perform(get("/api/documents/" + UUID.randomUUID()).with(jwt()))
			.andExpect(status().isNotFound());
	}

	@Test
	void memoryRequiresAuthentication() throws Exception {
		// Agent memory is as sensitive as the corpus and newer, so it is worth asserting rather
		// than assuming it inherited the rule.
		this.mvc.perform(get("/api/memory").param("agentId", "support"))
			.andExpect(status().isUnauthorized());
	}

}
