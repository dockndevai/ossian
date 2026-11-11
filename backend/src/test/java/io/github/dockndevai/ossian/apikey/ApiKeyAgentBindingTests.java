package io.github.dockndevai.ossian.apikey;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.github.dockndevai.ossian.TestAiConfig;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Binding a key to an agent: issuing one, changing it afterwards, and what is refused.
 *
 * <p>Driven through the admin routes with a real database, because the three things that can go
 * wrong are all in the seams: the DTO validation that stands between an operator's typo and a
 * binding that matches nobody, the column that has to actually persist, and the audit row that
 * says who changed what a key may reach.
 */
@Testcontainers
@SpringBootTest(properties = { "spring.autoconfigure.exclude="
		+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
		"spring.cache.type=none" })
@AutoConfigureMockMvc
@Import(TestAiConfig.class)
class ApiKeyAgentBindingTests {

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
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://localhost/realms/test");
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://localhost/certs");
		registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> "false");
		registry.add("spring.data.redis.host", () -> "localhost");
		registry.add("spring.cache.type", () -> "none");
	}

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final RequestPostProcessor ADMIN = jwt()
		.authorities(new SimpleGrantedAuthority("ROLE_ossian-admin"));

	private static final RequestPostProcessor PLAIN_USER = jwt()
		.authorities(new SimpleGrantedAuthority("ROLE_ossian-user"));

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ApiKeyService keys;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clear() {
		this.jdbc.update("delete from api_keys");
		this.jdbc.update("delete from audit_log");
		this.jdbc.update("delete from agent_memories");
	}

	private MvcResult create(String body) throws Exception {
		return this.mvc.perform(post("/api/admin/api-keys").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
			.content(body)).andReturn();
	}

	private static String body(String name, String agentId) throws Exception {
		return JSON.writeValueAsString(agentId == null ? Map.of("name", name) : Map.of("name", name, "agentId", agentId));
	}

	private long keyCount() {
		return this.jdbc.queryForObject("select count(*) from api_keys", Long.class);
	}

	@Test
	@DisplayName("a key issued with an agentId is persisted bound, and says so in the response")
	void issueBound() throws Exception {
		MvcResult result = create(body("support", "support-bot"));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode created = JSON.readTree(result.getResponse().getContentAsString());
		assertThat(created.at("/key/agentId").asText()).isEqualTo("support-bot");
		assertThat(created.get("secret").asText()).startsWith("osk_");
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where name = 'support'", String.class))
			.isEqualTo("support-bot");
	}

	@Test
	@DisplayName("a key issued without an agentId is unbound, as every key was before")
	void issueUnbound() throws Exception {
		MvcResult result = create(body("pipeline", null));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).at("/key/agentId").isNull()).isTrue();
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where name = 'pipeline'", String.class))
			.isNull();
	}

	@Test
	@DisplayName("the key listing shows each key's binding")
	void listingShowsBinding() throws Exception {
		create(body("bound-one", "agent-1"));
		create(body("unbound-one", null));

		MvcResult result = this.mvc.perform(get("/api/admin/api-keys").with(ADMIN)).andReturn();

		JsonNode listing = JSON.readTree(result.getResponse().getContentAsString());
		assertThat(listing).hasSize(2);
		for (JsonNode key : listing) {
			if (key.get("name").asText().equals("bound-one")) {
				assertThat(key.get("agentId").asText()).isEqualTo("agent-1");
			}
			else {
				assertThat(key.get("agentId").isNull()).isTrue();
			}
		}
	}

	@Test
	@DisplayName("issuing a bound key is audited with the agent it is bound to")
	void issuingIsAudited() throws Exception {
		create(body("support", "support-bot"));

		String detail = this.jdbc.queryForObject("select detail from audit_log where action = 'apikey.issued'",
				String.class);
		assertThat(detail).contains("agent=support-bot");
	}

	@ParameterizedTest(name = "[{index}] rejects {0}")
	@ValueSource(strings = { "", " ", "   ", " leading", "trailing ", "new\nline", "tab\tinside", "nul\u0000byte",
			"\u007fdelete" })
	@DisplayName("an agentId that cannot be a real agent id is a 400 and no key is created")
	void invalidAgentIdIsRejected(String agentId) throws Exception {
		MvcResult result = create(body("bad", agentId));

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(keyCount()).isZero();
	}

	@Test
	@DisplayName("an agentId over 128 characters is a 400, and exactly 128 is accepted")
	void lengthBoundary() throws Exception {
		assertThat(create(body("too-long", "a".repeat(129))).getResponse().getStatus()).isEqualTo(400);
		assertThat(keyCount()).isZero();

		assertThat(create(body("longest", "a".repeat(128))).getResponse().getStatus()).isEqualTo(201);
		assertThat(keyCount()).isEqualTo(1);
	}

	@ParameterizedTest(name = "[{index}] accepts {0}")
	@ValueSource(strings = { "support", "Support Bot v2", "billing:eu-west/1", "agent_7.prod", "agénte-ñ", "x" })
	@DisplayName("agent ids are free text, as they are on the memory routes, so existing agents can be bound")
	void ordinaryAgentIdsAreAccepted(String agentId) throws Exception {
		assertThat(create(body("ok", agentId)).getResponse().getStatus()).isEqualTo(201);
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where name = 'ok'", String.class))
			.isEqualTo(agentId);
	}

	@Test
	@DisplayName("the service refuses an invalid agentId too, for callers that never go through HTTP")
	void serviceValidates() {
		for (String bad : new String[] { "", " ", " x", "x ", "a\nb", "a".repeat(129) }) {
			org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> this.keys.issue("k", List.of(), null, bad, null, null))
				.as("issue with '%s'", bad)
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(keyCount()).isZero();
		assertThat(ApiKeyService.isValidAgentId(null)).isFalse();
		assertThat(ApiKeyService.isValidAgentId("fine")).isTrue();
	}

	@Test
	@DisplayName("an existing key can be bound afterwards, and the binding is audited")
	void bindExistingKey() throws Exception {
		UUID id = this.keys.issue("legacy", List.of("ossian-user"), "ops", null, null).key().getId();

		MvcResult result = this.mvc.perform(put("/api/admin/api-keys/" + id + "/agent").with(ADMIN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agentId\":\"ops-bot\"}")).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("agentId").asText())
			.isEqualTo("ops-bot");
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where id = ?", String.class, id))
			.isEqualTo("ops-bot");
		assertThat(this.jdbc.queryForObject("select detail from audit_log where action = 'apikey.updated'",
				String.class)).contains("legacy").contains("agent=ops-bot");
	}

	@Test
	@DisplayName("a binding can be removed, returning the key to the unbound rules")
	void unbindKey() throws Exception {
		UUID id = this.keys.issue("bound", List.of("ossian-user"), null, "ops-bot", null, null).key().getId();

		MvcResult result = this.mvc.perform(delete("/api/admin/api-keys/" + id + "/agent").with(ADMIN)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where id = ?", String.class, id)).isNull();
		assertThat(this.jdbc.queryForObject("select detail from audit_log where action = 'apikey.updated'",
				String.class)).contains("(unbound)");
	}

	@Test
	@DisplayName("binding validates the agentId exactly as issuing does, and leaves the key as it was")
	void bindValidates() throws Exception {
		UUID id = this.keys.issue("k", List.of("ossian-user"), null, "keep-me", null, null).key().getId();

		for (String bad : new String[] { "{\"agentId\":\"\"}", "{\"agentId\":\"  \"}", "{\"agentId\":null}", "{}",
				"{\"agentId\":\" x\"}", "{\"agentId\":\"" + "a".repeat(129) + "\"}" }) {
			MvcResult result = this.mvc.perform(put("/api/admin/api-keys/" + id + "/agent").with(ADMIN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(bad)).andReturn();
			assertThat(result.getResponse().getStatus()).as(bad).isEqualTo(400);
		}
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where id = ?", String.class, id))
			.isEqualTo("keep-me");
	}

	@Test
	@DisplayName("binding or unbinding a key that does not exist is a 404")
	void unknownKey() throws Exception {
		UUID nobody = UUID.randomUUID();

		assertThat(this.mvc.perform(put("/api/admin/api-keys/" + nobody + "/agent").with(ADMIN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agentId\":\"x\"}")).andReturn().getResponse().getStatus()).isEqualTo(404);
		assertThat(this.mvc.perform(delete("/api/admin/api-keys/" + nobody + "/agent").with(ADMIN)).andReturn()
			.getResponse()
			.getStatus()).isEqualTo(404);
	}

	@Test
	@DisplayName("only an admin can issue, bind or unbind")
	void adminOnly() throws Exception {
		UUID id = this.keys.issue("k", List.of("ossian-user"), null, null, null).key().getId();

		assertThat(this.mvc.perform(post("/api/admin/api-keys").with(PLAIN_USER)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("sneaky", "a"))).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(this.mvc.perform(put("/api/admin/api-keys/" + id + "/agent").with(PLAIN_USER)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"agentId\":\"x\"}")).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(this.mvc.perform(delete("/api/admin/api-keys/" + id + "/agent").with(PLAIN_USER)).andReturn()
			.getResponse()
			.getStatus()).isEqualTo(403);
		assertThat(this.jdbc.queryForObject("select agent_id from api_keys where id = ?", String.class, id)).isNull();
	}

	@Test
	@DisplayName("a key bound through the API is held to that agent on its next request")
	void boundThroughTheApiIsEnforced() throws Exception {
		String secret = JSON.readTree(create(body("end-to-end", "ops-bot")).getResponse().getContentAsString())
			.get("secret")
			.asText();
		this.jdbc.update("""
				insert into agent_memories (agent_id, content, content_hash)
				values ('finance-bot', 'payroll runs on the 25th', 'h1')
				""");

		assertThat(this.mvc.perform(get("/api/memory").param("agentId", "finance-bot").header("X-API-Key", secret))
			.andReturn()
			.getResponse()
			.getStatus()).isEqualTo(403);
		assertThat(this.mvc.perform(get("/api/memory").header("X-API-Key", secret)).andReturn().getResponse().getStatus())
			.isEqualTo(200);
	}

}
