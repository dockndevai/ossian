package io.github.dockndevai.ossian.ingest;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import io.github.dockndevai.ossian.TestAiConfig;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Event deletes, against a real database.
 *
 * <p>The event row carries a foreign key to documents, so a delete that recorded the id it had
 * just removed failed its own insert — and only on the first delivery, since a redelivery found
 * nothing to delete and succeeded. A retrying pipeline hid it completely, which is why it is
 * pinned here rather than left to a smoke test.
 */
@Testcontainers
@SpringBootTest(properties = { "spring.autoconfigure.exclude="
		+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
		"spring.cache.type=none" })
@AutoConfigureMockMvc
@Import(TestAiConfig.class)
class EventIngestTests {

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
	private JdbcTemplate jdbc;

	private UUID existing;

	@BeforeEach
	void seed() {
		this.jdbc.update("delete from ingest_events");
		this.documents.deleteAll();
		DocumentEntity d = new DocumentEntity();
		d.setNamespace("default");
		d.setExternalId("crm/4172");
		d.setFilename("crm/4172");
		d.setSizeBytes(12);
		d.setContentHash(UUID.randomUUID().toString().replace("-", "") + "00000000000000000000000000000000");
		d.setStatus(DocumentEntity.Status.READY);
		this.existing = this.documents.save(d).getId();
	}

	private static String deleteEvent(String eventId) {
		return """
				{"eventId":"%s","operation":"DELETE","externalId":"crm/4172","namespace":"default","source":"test"}"""
			.formatted(eventId);
	}

	@Test
	@DisplayName("deleting an existing document succeeds on first delivery")
	void deleteOfExistingDocument() throws Exception {
		this.mvc.perform(post("/api/events/documents").with(jwt()).contentType(MediaType.APPLICATION_JSON)
				.content(deleteEvent("evt-delete-1")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.documentId").value(this.existing.toString()));

		assertThat(this.documents.findById(this.existing)).isEmpty();
		assertThat(this.jdbc.queryForObject("select count(*) from ingest_events where event_id = 'evt-delete-1' "
				+ "and status = 'ACCEPTED' and external_id = 'crm/4172'", Integer.class)).isEqualTo(1);
	}

	@Test
	@DisplayName("a batch mixing a delete with an upsert reports both, rather than failing the request")
	void deleteInsideBatch() throws Exception {
		String batch = """
				{"events":[%s,
				 {"eventId":"evt-upsert-2","operation":"UPSERT","externalId":"crm/9","namespace":"default","text":"new row"}]}"""
			.formatted(deleteEvent("evt-delete-2"));

		this.mvc.perform(post("/api/events/documents/batch").with(jwt()).contentType(MediaType.APPLICATION_JSON)
				.content(batch))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].status").value("ACCEPTED"))
			.andExpect(jsonPath("$[1].status").value("ACCEPTED"));
	}

	@Test
	@DisplayName("a redelivered delete is a duplicate, not a second event")
	void redeliveredDelete() throws Exception {
		for (int i = 0; i < 2; i++) {
			this.mvc.perform(post("/api/events/documents").with(jwt()).contentType(MediaType.APPLICATION_JSON)
				.content(deleteEvent("evt-delete-3")));
		}
		assertThat(this.jdbc.queryForObject("select count(*) from ingest_events where event_id = 'evt-delete-3'",
				Integer.class)).isEqualTo(1);
	}

}
