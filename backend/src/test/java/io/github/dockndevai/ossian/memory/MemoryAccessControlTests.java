package io.github.dockndevai.ossian.memory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.github.dockndevai.ossian.apikey.ApiKeyService;
import io.github.dockndevai.ossian.config.OssianProperties;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may reach which agent's memory, through the real filter chain against a real database.
 *
 * <p>Regression suite for a hole found in review: {@code /api/memory} was only
 * {@code authenticated()} and never consulted the caller, so any valid API key could list,
 * recall, rewrite and erase every agent's memory by naming it. A key can now be bound to an
 * agent, and a bound key is held to it. These tests started life as the reproduction of that hole
 * (the five "another agent" cases below are those tests, now run with the key bound as an
 * operator is expected to bind it) and grew the rest of the contract:
 *
 * <ul>
 * <li>a bound key is forced to its agent on every route, and cannot name another;</li>
 * <li>an unbound key confined to a namespace is refused unless the migration flag is set;</li>
 * <li>admin keys, unconfined keys and people are unchanged;</li>
 * <li>erasing memory leaves an audit row naming the key and the agent.</li>
 * </ul>
 *
 * <p>Each assertion on a refusal also asserts the data is still there. A 403 that arrives after
 * the delete ran is not a refusal.
 */
@Testcontainers
@SpringBootTest(properties = { "spring.autoconfigure.exclude="
		+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
		"spring.cache.type=none" })
@AutoConfigureMockMvc
@Import(MemoryTests.Stubs.class)
class MemoryAccessControlTests {

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

	private static final String USER = "ossian-user";

	private static final String ADMIN = "ossian-admin";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private MemoryService memories;

	@Autowired
	private ApiKeyService keys;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private OssianProperties properties;

	/** Bound to ops-bot, and confined to the "ops" namespace: the key an ops agent is given. */
	private String boundKey;

	private UUID boundKeyId;

	/** Confined to "ops" like the above, but bound to nothing: every key issued before binding. */
	private String unboundConfinedKey;

	private UUID opsMemory;

	private UUID financeMemory;

	@BeforeEach
	void seed() {
		this.jdbc.update("delete from agent_memories");
		this.jdbc.update("delete from audit_log");
		this.jdbc.update("delete from api_keys");
		var bound = this.keys.issue("ops-agent", List.of(USER), "ops", "ops-bot", null, null);
		this.boundKey = bound.secret();
		this.boundKeyId = bound.key().getId();
		this.unboundConfinedKey = this.keys.issue("ops-legacy", List.of(USER), "ops", null, null).secret();
		this.opsMemory = this.memories.remember("ops-bot", "s1", null, "fact", "deploys happen on tuesdays", null,
				null, null).id();
		this.financeMemory = this.memories
			.remember("finance-bot", "s1", "user:cfo", "fact", "payroll runs on the 25th", null, null, null)
			.id();
	}

	@AfterEach
	void restoreDefaults() {
		this.properties.getMemory().setAllowUnboundConfinedKeys(false);
	}

	private long financeRows() {
		return this.jdbc.queryForObject("select count(*) from agent_memories where agent_id = 'finance-bot'",
				Long.class);
	}

	private long opsRows() {
		return this.jdbc.queryForObject("select count(*) from agent_memories where agent_id = 'ops-bot'",
				Long.class);
	}

	private MvcResult run(MockHttpServletRequestBuilder request, String key) throws Exception {
		return this.mvc.perform(request.header("X-API-Key", key)).andReturn();
	}

	private static MockHttpServletRequestBuilder recall(String body) {
		return post("/api/memory/recall").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	private static MockHttpServletRequestBuilder remember(String body) {
		return post("/api/memory").contentType(MediaType.APPLICATION_JSON).content(body);
	}

	// ---- a key bound to an agent -------------------------------------------------------------

	@Test
	@DisplayName("a bound key does not learn which other agents exist")
	void agentsEnumeration() throws Exception {
		MvcResult result = run(get("/api/memory/agents"), this.boundKey);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("ops-bot").doesNotContain("finance-bot");
	}

	@Test
	@DisplayName("a bound key cannot list another agent's memories")
	void listOtherAgent() throws Exception {
		MvcResult result = run(get("/api/memory").param("agentId", "finance-bot"), this.boundKey);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("payroll runs on the 25th");
	}

	@Test
	@DisplayName("a bound key cannot recall another agent's memories")
	void recallOtherAgent() throws Exception {
		MvcResult result = run(recall("{\"agentId\":\"finance-bot\",\"query\":\"payroll\"}"), this.boundKey);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(result.getResponse().getContentAsString()).doesNotContain("payroll runs on the 25th");
	}

	@Test
	@DisplayName("a bound key cannot erase another agent's session (forget_session)")
	void forgetOtherAgentsSession() throws Exception {
		MvcResult result = run(delete("/api/memory/sessions/s1").param("agentId", "finance-bot"), this.boundKey);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(financeRows()).isEqualTo(1);
		assertThat(opsRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("a bound key cannot delete another agent's memory by id")
	void forgetOtherAgentsMemoryById() throws Exception {
		MvcResult result = run(delete("/api/memory/" + this.financeMemory), this.boundKey);

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(financeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("a bound key cannot write into another agent's memory")
	void rememberOtherAgent() throws Exception {
		MvcResult result = run(remember("{\"agentId\":\"finance-bot\",\"content\":\"payroll moves to the 1st\"}"),
				this.boundKey);

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(financeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("deleting another agent's memory looks exactly like deleting one that does not exist")
	void foreignIdGivesNoExistenceLeak() throws Exception {
		MvcResult foreign = run(delete("/api/memory/" + this.financeMemory), this.boundKey);
		MvcResult missing = run(delete("/api/memory/" + UUID.randomUUID()), this.boundKey);

		assertThat(foreign.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus()).isEqualTo(404);
		assertThat(foreign.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
		assertThat(foreign.getResponse().getHeaderNames()).isEqualTo(missing.getResponse().getHeaderNames());
	}

	@Test
	@DisplayName("the refusal says which agent the key is bound to")
	void refusalIsExplained() throws Exception {
		this.mvc.perform(get("/api/memory").param("agentId", "finance-bot").header("X-API-Key", this.boundKey))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("bound to agent 'ops-bot'")));
	}

	@Test
	@DisplayName("a bound key that omits agentId is the agent it is bound to, on every route")
	void omittedAgentDefaultsToTheBinding() throws Exception {
		// list
		this.mvc.perform(get("/api/memory").header("X-API-Key", this.boundKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].agentId").value("ops-bot"));

		// recall: finds its own, and a query for the other agent's words finds nothing of theirs
		this.mvc.perform(recall("{\"query\":\"deploys\"}").header("X-API-Key", this.boundKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].content").value("deploys happen on tuesdays"));
		MvcResult leak = run(recall("{\"query\":\"payroll\",\"minSimilarity\":0}"), this.boundKey);
		assertThat(leak.getResponse().getContentAsString()).doesNotContain("payroll runs on the 25th");

		// remember: lands under the bound agent, not under nothing and not under a blank id
		this.mvc.perform(remember("{\"content\":\"rollbacks need two approvers\"}").header("X-API-Key", this.boundKey))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.agentId").value("ops-bot"));
		assertThat(this.jdbc.queryForObject(
				"select count(*) from agent_memories where content = 'rollbacks need two approvers' and agent_id = 'ops-bot'",
				Long.class)).isEqualTo(1);

		// forget_session
		this.mvc.perform(delete("/api/memory/sessions/s1").header("X-API-Key", this.boundKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.forgotten").value(1));
		assertThat(financeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("a blank agentId is treated as omitted, never as another agent")
	void blankAgentIdDefaults() throws Exception {
		this.mvc.perform(get("/api/memory").param("agentId", "  ").header("X-API-Key", this.boundKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].agentId").value("ops-bot"));
	}

	@Test
	@DisplayName("a bound key can name its own agent explicitly, and delete its own memory by id")
	void boundKeyKeepsFullAccessToItsOwnAgent() throws Exception {
		this.mvc.perform(get("/api/memory").param("agentId", "ops-bot").header("X-API-Key", this.boundKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].content").value("deploys happen on tuesdays"));

		this.mvc.perform(delete("/api/memory/" + this.opsMemory).header("X-API-Key", this.boundKey))
			.andExpect(status().isNoContent());
		assertThat(opsRows()).isZero();
		assertThat(financeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("the binding holds even when the key also carries the admin role")
	void bindingBeatsTheAdminRole() throws Exception {
		String key = this.keys.issue("ops-admin-bound", List.of(USER, ADMIN), null, "ops-bot", null, null).secret();

		assertThat(run(get("/api/memory").param("agentId", "finance-bot"), key).getResponse().getStatus())
			.isEqualTo(403);
		assertThat(run(get("/api/memory/agents"), key).getResponse().getContentAsString())
			.doesNotContain("finance-bot");
		assertThat(run(delete("/api/memory/" + this.financeMemory), key).getResponse().getStatus()).isEqualTo(404);
		assertThat(financeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("rebinding takes effect on the key's very next request")
	void rebindingIsImmediate() throws Exception {
		assertThat(run(get("/api/memory").param("agentId", "finance-bot"), this.boundKey).getResponse().getStatus())
			.isEqualTo(403);

		this.keys.bindAgent(this.boundKeyId, "finance-bot");

		MvcResult result = run(get("/api/memory").param("agentId", "finance-bot"), this.boundKey);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("payroll runs on the 25th");
		assertThat(run(get("/api/memory").param("agentId", "ops-bot"), this.boundKey).getResponse().getStatus())
			.isEqualTo(403);
	}

	// ---- an unbound key that is confined to a namespace --------------------------------------

	@Test
	@DisplayName("an unbound key confined to a namespace is refused on every memory route, and told to bind it")
	void unboundConfinedKeyIsRefusedEverywhere() throws Exception {
		Map<String, MockHttpServletRequestBuilder> routes = new java.util.LinkedHashMap<>();
		routes.put("agents", get("/api/memory/agents"));
		routes.put("list", get("/api/memory").param("agentId", "finance-bot"));
		routes.put("list-default", get("/api/memory"));
		routes.put("recall", recall("{\"agentId\":\"finance-bot\",\"query\":\"payroll\"}"));
		routes.put("remember", remember("{\"agentId\":\"finance-bot\",\"content\":\"payroll moves\"}"));
		routes.put("delete-by-id", delete("/api/memory/" + this.financeMemory));
		routes.put("forget-session", delete("/api/memory/sessions/s1").param("agentId", "finance-bot"));

		for (var route : routes.entrySet()) {
			MvcResult result = run(route.getValue(), this.unboundConfinedKey);
			assertThat(result.getResponse().getStatus()).as(route.getKey()).isEqualTo(403);
			assertThat(result.getResponse().getContentAsString()).as(route.getKey())
				.contains("not bound to an agent")
				.contains("Bind the key")
				.contains("'ops' namespace")
				.doesNotContain("payroll runs on the 25th");
		}
		assertThat(financeRows()).isEqualTo(1);
		assertThat(opsRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("binding is the way back for a confined key")
	void bindingRestoresAccessForAConfinedKey() throws Exception {
		UUID id = this.jdbc.queryForObject("select id from api_keys where name = 'ops-legacy'", UUID.class);
		assertThat(run(get("/api/memory").param("agentId", "ops-bot"), this.unboundConfinedKey).getResponse()
			.getStatus()).isEqualTo(403);

		this.keys.bindAgent(id, "ops-bot");

		MvcResult result = run(get("/api/memory").param("agentId", "ops-bot"), this.unboundConfinedKey);
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("deploys happen on tuesdays");
	}

	@Test
	@DisplayName("ossian.memory.allow-unbound-confined-keys is off unless someone turns it on")
	void flagDefaultsOff() {
		assertThat(this.properties.getMemory().isAllowUnboundConfinedKeys()).isFalse();
	}

	@Test
	@DisplayName("with the migration flag set, an unbound confined key behaves as it did before keys could be bound")
	void flagRestoresLegacyBehaviour() throws Exception {
		this.properties.getMemory().setAllowUnboundConfinedKeys(true);

		MvcResult list = run(get("/api/memory").param("agentId", "finance-bot"), this.unboundConfinedKey);
		assertThat(list.getResponse().getStatus()).isEqualTo(200);
		assertThat(list.getResponse().getContentAsString()).contains("payroll runs on the 25th");
		assertThat(run(get("/api/memory/agents"), this.unboundConfinedKey).getResponse().getContentAsString())
			.contains("finance-bot");
		// It is the old behaviour, which still needs an agent named.
		assertThat(run(get("/api/memory"), this.unboundConfinedKey).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	@DisplayName("the migration flag never loosens a binding")
	void flagDoesNotAffectBoundKeys() throws Exception {
		this.properties.getMemory().setAllowUnboundConfinedKeys(true);

		assertThat(run(get("/api/memory").param("agentId", "finance-bot"), this.boundKey).getResponse().getStatus())
			.isEqualTo(403);
		assertThat(run(get("/api/memory/agents"), this.boundKey).getResponse().getContentAsString())
			.doesNotContain("finance-bot");
	}

	// ---- callers whose behaviour does not change ---------------------------------------------

	@Test
	@DisplayName("an unbound admin key reaches every agent, even when confined to a namespace")
	void adminKeyCanReachAnyAgent() throws Exception {
		for (String namespace : new String[] { null, "ops" }) {
			String admin = this.keys.issue("admin-" + namespace, List.of(USER, ADMIN), namespace, null, null)
				.secret();

			assertThat(run(get("/api/memory").param("agentId", "finance-bot"), admin).getResponse()
				.getContentAsString()).as("list, namespace=" + namespace).contains("payroll runs on the 25th");
			assertThat(run(recall("{\"agentId\":\"finance-bot\",\"query\":\"payroll\"}"), admin).getResponse()
				.getContentAsString()).as("recall, namespace=" + namespace).contains("payroll runs on the 25th");
			assertThat(run(get("/api/memory/agents"), admin).getResponse().getContentAsString())
				.as("agents, namespace=" + namespace)
				.contains("finance-bot")
				.contains("ops-bot");
		}
		String admin = this.keys.issue("admin-eraser", List.of(USER, ADMIN), "ops", null, null).secret();
		assertThat(run(delete("/api/memory/sessions/s1").param("agentId", "finance-bot"), admin).getResponse()
			.getStatus()).isEqualTo(200);
		assertThat(financeRows()).isZero();
	}

	@Test
	@DisplayName("an unbound key with no namespace confinement keeps its current behaviour")
	void unconfinedUnboundKeyIsUnchanged() throws Exception {
		String key = this.keys.issue("integration", List.of(USER), null, null, null).secret();

		assertThat(run(get("/api/memory").param("agentId", "finance-bot"), key).getResponse().getContentAsString())
			.contains("payroll runs on the 25th");
		assertThat(run(delete("/api/memory/" + this.financeMemory), key).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	@DisplayName("a signed-in person keeps their current access, which is what the console uses")
	void personIsUnchanged() throws Exception {
		this.mvc.perform(get("/api/memory").param("agentId", "finance-bot")
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + USER))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].content").value("payroll runs on the 25th"));
		this.mvc.perform(get("/api/memory/agents").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + USER))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	@DisplayName("a caller who is not bound must still say which agent")
	void unboundCallerMustNameAnAgent() throws Exception {
		String admin = this.keys.issue("admin-no-agent", List.of(USER, ADMIN), null, null, null).secret();

		assertThat(run(get("/api/memory"), admin).getResponse().getStatus()).isEqualTo(400);
		assertThat(run(recall("{\"query\":\"payroll\"}"), admin).getResponse().getStatus()).isEqualTo(400);
		assertThat(run(remember("{\"content\":\"no home\"}"), admin).getResponse().getStatus()).isEqualTo(400);
		assertThat(run(delete("/api/memory/sessions/s1"), admin).getResponse().getStatus()).isEqualTo(400);
		assertThat(financeRows()).isEqualTo(1);
	}

	@Test
	@DisplayName("memory still requires authentication")
	void anonymousIsStillRejected() throws Exception {
		this.mvc.perform(get("/api/memory/agents")).andExpect(status().isUnauthorized());
	}

	// ---- audit -------------------------------------------------------------------------------

	@Test
	@DisplayName("deleting a memory by id is audited with the acting key and the agent")
	void forgetByIdIsAudited() throws Exception {
		run(delete("/api/memory/" + this.opsMemory), this.boundKey);

		Map<String, Object> row = this.jdbc.queryForMap(
				"select actor, subject, machine, target_type, target_id, detail, outcome from audit_log "
						+ "where action = 'memory.forgotten'");
		assertThat(row.get("actor")).isEqualTo("key:ops-agent");
		assertThat(row.get("subject")).isEqualTo("key:" + this.boundKeyId);
		assertThat(row.get("machine")).isEqualTo(true);
		assertThat(row.get("target_type")).isEqualTo("memory");
		assertThat(row.get("target_id")).isEqualTo(this.opsMemory.toString());
		assertThat((String) row.get("detail")).contains("agent=ops-bot").doesNotContain("deploys happen");
		assertThat(row.get("outcome")).isEqualTo("success");
	}

	@Test
	@DisplayName("forgetting a session is audited with the acting key, the agent and how much went")
	void forgetSessionIsAudited() throws Exception {
		run(delete("/api/memory/sessions/s1"), this.boundKey);

		Map<String, Object> row = this.jdbc.queryForMap(
				"select subject, target_type, target_id, detail from audit_log where action = 'memory.forgotten'");
		assertThat(row.get("subject")).isEqualTo("key:" + this.boundKeyId);
		assertThat(row.get("target_type")).isEqualTo("memory.session");
		assertThat(row.get("target_id")).isEqualTo("s1");
		assertThat((String) row.get("detail")).contains("agent=ops-bot").contains("forgotten=1");
	}

	@Test
	@DisplayName("an erase an admin performs on another agent is audited against that agent")
	void adminEraseNamesTheAgentErased() throws Exception {
		String admin = this.keys.issue("cleanup", List.of(USER, ADMIN), null, null, null).secret();

		run(delete("/api/memory/" + this.financeMemory), admin);

		String detail = this.jdbc.queryForObject("select detail from audit_log where action = 'memory.forgotten'",
				String.class);
		assertThat(detail).contains("agent=finance-bot");
	}

	@Test
	@DisplayName("a refused or missing delete does not leave a forgotten row behind")
	void refusedEraseIsNotRecordedAsForgotten() throws Exception {
		run(delete("/api/memory/" + this.financeMemory), this.boundKey);
		run(delete("/api/memory/sessions/s1").param("agentId", "finance-bot"), this.boundKey);
		run(delete("/api/memory/" + this.opsMemory), this.unboundConfinedKey);

		assertThat(this.jdbc.queryForObject("select count(*) from audit_log where action = 'memory.forgotten'",
				Long.class)).isZero();
	}

}
