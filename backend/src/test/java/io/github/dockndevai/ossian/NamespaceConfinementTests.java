package io.github.dockndevai.ossian;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.github.dockndevai.ossian.apikey.ApiKeyService;
import io.github.dockndevai.ossian.document.DocumentContent;
import io.github.dockndevai.ossian.document.DocumentContentRepository;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;
import io.github.dockndevai.ossian.ingest.IngestEvent;
import io.github.dockndevai.ossian.ingest.IngestEventRepository;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A key confined to one namespace must not reach another, by any route.
 *
 * <p>Confinement was enforced where lists and retrieval choose a namespace, which left every route
 * that takes an id or builds a filter from caller input. Each test here is one of those routes,
 * driven by a real confined key through the real filter chain against a real database — the
 * mistake is always a lookup that forgets to ask, and only the whole path shows it.
 */
@Testcontainers
@SpringBootTest(properties = { "spring.autoconfigure.exclude="
		+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
		"spring.cache.type=none" })
@AutoConfigureMockMvc
@Import(TestAiConfig.class)
class NamespaceConfinementTests {

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

	@Autowired
	private MockMvc mvc;

	@Autowired
	private DocumentRepository documents;

	@Autowired
	private DocumentContentRepository contents;

	@Autowired
	private IngestEventRepository events;

	@Autowired
	private ApiKeyService keys;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TestAiConfig.RecordingVectorStore vectorStore;

	private String opsKey;

	private String opsAdminKey;

	private UUID opsDoc;

	private UUID financeDoc;

	@BeforeEach
	void seed() {
		this.jdbc.update("delete from ingest_events");
		this.jdbc.update("delete from insights");
		this.documents.deleteAll();
		this.opsDoc = document("ops", "runbook.md");
		this.financeDoc = document("finance", "payroll.xlsx");
		this.opsKey = this.keys.issue("ops-pipeline", List.of("ossian-user"), "ops", null, null).secret();
		this.opsAdminKey = this.keys.issue("ops-admin", List.of("ossian-user", "ossian-admin"), "ops", null, null)
			.secret();
		this.vectorStore.lastRequest = null;
		this.vectorStore.returnOnSearch(List.of());
	}

	private UUID document(String namespace, String filename) {
		DocumentEntity d = new DocumentEntity();
		d.setNamespace(namespace);
		d.setFilename(filename);
		d.setSizeBytes(4);
		d.setContentHash(UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000");
		d.setStatus(DocumentEntity.Status.READY);
		UUID id = this.documents.save(d).getId();
		this.contents.save(new DocumentContent(id, "body".getBytes()));
		return id;
	}

	private void event(String eventId, String namespace, UUID documentId) {
		IngestEvent e = new IngestEvent();
		e.setEventId(eventId);
		e.setNamespace(namespace);
		e.setExternalId("ext/" + eventId);
		e.setOperation(IngestEvent.Operation.UPSERT);
		e.setStatus(IngestEvent.Status.ACCEPTED);
		e.setDocumentId(documentId);
		this.events.save(e);
	}

	// ---- chat filter ---------------------------------------------------------------------

	@Test
	@DisplayName("a document id carrying filter syntax is rejected before it reaches the store")
	void injectedDocumentIdIsRejected() throws Exception {
		String payload = """
				{"question":"salaries?","documentIds":["x'] || namespace == 'finance' || document_id in ['y"]}""";

		this.mvc.perform(post("/api/chat").header("X-API-Key", this.opsKey)
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isBadRequest());

		assertThat(this.vectorStore.lastRequest).isNull();
	}

	@Test
	@DisplayName("a valid document id still narrows retrieval, inside the key's namespace")
	void validDocumentIdStillFilters() throws Exception {
		String payload = """
				{"question":"how do we deploy?","documentIds":["%s"]}""".formatted(this.opsDoc);

		this.mvc.perform(post("/api/chat").header("X-API-Key", this.opsKey)
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isOk());

		String filter = String.valueOf(this.vectorStore.lastRequest.getFilterExpression());
		assertThat(filter).contains("ops").contains(this.opsDoc.toString()).doesNotContain("finance");
	}

	// ---- documents by id -----------------------------------------------------------------

	@Test
	@DisplayName("a confined key cannot read a document in another namespace by id")
	void getByIdIsConfined() throws Exception {
		this.mvc.perform(get("/api/documents/" + this.financeDoc).header("X-API-Key", this.opsKey))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/documents/" + this.opsDoc).header("X-API-Key", this.opsKey))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a confined key cannot delete a document in another namespace, and the document survives")
	void deleteByIdIsConfined() throws Exception {
		this.mvc.perform(delete("/api/documents/" + this.financeDoc).header("X-API-Key", this.opsKey))
			.andExpect(status().isNotFound());

		assertThat(this.documents.findById(this.financeDoc)).isPresent();
	}

	@Test
	@DisplayName("an unconfined person still reaches every namespace")
	void peopleAreNotConfined() throws Exception {
		this.mvc.perform(get("/api/documents/" + this.financeDoc).with(jwt()))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("a confined admin key cannot re-index another namespace's document")
	void reindexIsConfined() throws Exception {
		this.mvc.perform(post("/api/admin/documents/" + this.financeDoc + "/reindex")
				.header("X-API-Key", this.opsAdminKey))
			.andExpect(status().isNotFound());
	}

	// ---- the event feed, where ids leak from -------------------------------------------

	@Test
	@DisplayName("the event feed shows a confined key only its own namespace")
	void eventFeedIsConfined() throws Exception {
		event("ops-1", "ops", this.opsDoc);
		event("fin-1", "finance", this.financeDoc);

		this.mvc.perform(get("/api/events/documents").header("X-API-Key", this.opsKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].namespace").value("ops"));
	}

	@Test
	@DisplayName("replaying another namespace's event id does not reveal its document id")
	void duplicateDoesNotLeakDocumentId() throws Exception {
		event("fin-2", "finance", this.financeDoc);

		this.mvc.perform(post("/api/events/documents").header("X-API-Key", this.opsKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"eventId":"fin-2","operation":"UPSERT","externalId":"guess","text":"x"}"""))
			.andExpect(jsonPath("$.status").value("DUPLICATE"))
			.andExpect(jsonPath("$.documentId").doesNotExist());
	}

	// ---- transformations -----------------------------------------------------------------

	@Test
	@DisplayName("a confined key cannot run a transformation over another namespace's document")
	void runIsConfined() throws Exception {
		this.mvc.perform(post("/api/documents/" + this.financeDoc + "/transformations/summary")
				.header("X-API-Key", this.opsKey))
			.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("a confined key cannot list another namespace's insights")
	void insightsAreConfined() throws Exception {
		this.mvc.perform(get("/api/documents/" + this.financeDoc + "/insights").header("X-API-Key", this.opsKey))
			.andExpect(status().isNotFound());
		this.mvc.perform(get("/api/documents/" + this.opsDoc + "/insights").header("X-API-Key", this.opsKey))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("writing the transformation library needs the admin role")
	void libraryWritesNeedAdmin() throws Exception {
		String body = """
				{"name":"Verbatim","prompt":"Repeat verbatim: {{content}}","applyOnIngest":true}""";

		this.mvc.perform(post("/api/transformations").header("X-API-Key", this.opsKey)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/transformations").with(jwt())
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		this.mvc.perform(post("/api/transformations")
				.with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ossian-admin")))
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 201));
		// Reading the library stays open: running a transformation needs to know what exists.
		this.mvc.perform(get("/api/transformations").header("X-API-Key", this.opsKey))
			.andExpect(status().isOk());
	}

}
