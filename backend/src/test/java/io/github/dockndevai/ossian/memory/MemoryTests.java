package io.github.dockndevai.ossian.memory;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How memory behaves, against a real pgvector.
 *
 * <p>The ranking, the deduplication and the expiry all live in SQL, so none of them can be
 * demonstrated with a mocked repository — and each is the kind of thing that fails by returning
 * plausible rows in the wrong order rather than by throwing.
 *
 * <p>Embeddings are deterministic and derived from the first word of the text, so two memories
 * beginning with the same word are identical vectors and everything else is orthogonal. That
 * makes similarity exactly 1 or 0 and leaves the ranking formula — importance and age — as the
 * only thing that can move a result, which is what these assert on.
 */
@Testcontainers
@SpringBootTest(properties = { "spring.autoconfigure.exclude="
		+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
		+ "org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
		"spring.cache.type=none" })
@Import(MemoryTests.Stubs.class)
class MemoryTests {

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

	/**
	 * Stand-ins for everything that would reach a model or the document vector store.
	 *
	 * <p>Self-contained rather than importing the shared TestAiConfig, which supplies its own
	 * primary embedding model — two primaries of one type is ambiguous, and two beans of one
	 * name is an override Boot refuses outright.
	 */
	@TestConfiguration
	static class Stubs {

		@Bean
		@Primary
		org.springframework.ai.vectorstore.VectorStore vectorStore() {
			return new io.github.dockndevai.ossian.TestAiConfig.RecordingVectorStore();
		}

		@Bean
		@Primary
		org.springframework.ai.chat.model.ChatModel chatModel() {
			return prompt -> new org.springframework.ai.chat.model.ChatResponse(
					List.of(new org.springframework.ai.chat.model.Generation(
							new org.springframework.ai.chat.messages.AssistantMessage("stubbed"))));
		}

		/**
		 * 768-dimension vectors keyed on the first word, so similarity is exactly 1 or 0.
		 *
		 * <p>Implemented on {@code call}, not on {@code embed(String)}. Every other method on
		 * the interface is a default that routes through {@code call}, and the caching decorator
		 * this gets wrapped in overrides only that — so an {@code embed} override here would be
		 * bypassed entirely and the empty response underneath would surface as a
		 * NoSuchElementException from inside Spring AI.
		 */
		@Bean
		@Primary
		EmbeddingModel embeddingModel() {
			return new EmbeddingModel() {
				@Override
				public EmbeddingResponse call(EmbeddingRequest request) {
					List<org.springframework.ai.embedding.Embedding> out = new java.util.ArrayList<>();
					List<String> texts = request.getInstructions();
					for (int i = 0; i < texts.size(); i++) {
						out.add(new org.springframework.ai.embedding.Embedding(vectorFor(texts.get(i)), i));
					}
					return new EmbeddingResponse(out,
							new org.springframework.ai.embedding.EmbeddingResponseMetadata());
				}

				@Override
				public float[] embed(Document document) {
					return vectorFor(document.getText());
				}
			};
		}

		static float[] vectorFor(String text) {
			String first = text.strip().split("\\s+")[0].toLowerCase();
			float[] v = new float[768];
			v[Math.abs(first.hashCode()) % 768] = 1f;
			return v;
		}

	}

	@Autowired
	private MemoryService memories;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void clear() {
		this.jdbc.update("delete from agent_memories");
	}

	private void write(String agent, String session, String subject, String content, Double importance, Long ttl) {
		this.memories.remember(agent, session, subject, "fact", content, null, importance, ttl);
	}

	@Test
	@DisplayName("what was written comes back")
	void roundTrip() {
		write("support", null, null, "alpha the user prefers email", null, null);

		List<MemoryService.Memory> found = this.memories.recall("alpha anything", "support", null, null, 5, 0.5);

		assertThat(found).hasSize(1);
		assertThat(found.get(0).content()).isEqualTo("alpha the user prefers email");
		assertThat(found.get(0).similarity()).isEqualTo(1.0);
	}

	@Test
	@DisplayName("restating the same thing updates rather than accumulates")
	void restatingDeduplicates() {
		for (int i = 0; i < 4; i++) {
			write("support", null, null, "alpha the user prefers email", null, null);
		}
		// An agent that writes what it already knows on every turn would otherwise bury itself.
		assertThat(this.memories.list("support", null, 50)).hasSize(1);
	}

	@Test
	@DisplayName("the same words for a different agent are a different memory")
	void dedupeIsPerAgent() {
		write("support", null, null, "alpha shared sentence", null, null);
		write("billing", null, null, "alpha shared sentence", null, null);

		assertThat(this.memories.list("support", null, 50)).hasSize(1);
		assertThat(this.memories.list("billing", null, 50)).hasSize(1);
	}

	@Test
	@DisplayName("one agent cannot recall another's memories")
	void agentsAreSeparate() {
		write("support", null, null, "alpha something support knows", null, null);

		assertThat(this.memories.recall("alpha", "billing", null, null, 5, 0.5)).isEmpty();
		assertThat(this.memories.recall("alpha", "support", null, null, 5, 0.5)).hasSize(1);
	}

	@Test
	@DisplayName("a session filter returns only that session")
	void sessionScoping() {
		write("support", "s1", null, "alpha in session one", null, null);
		write("support", "s2", null, "alpha in session two", null, null);

		assertThat(this.memories.recall("alpha", "support", "s1", null, 5, 0.5))
			.singleElement()
			.satisfies(m -> assertThat(m.content()).contains("session one"));
		// No session named means every session, which is what an agent wants when it is not in
		// one particular conversation.
		assertThat(this.memories.recall("alpha", "support", null, null, 5, 0.5)).hasSize(2);
	}

	@Test
	@DisplayName("a subject filter returns only memories about that subject")
	void subjectScoping() {
		write("support", null, "user:ankit", "alpha ankit prefers british english", null, null);
		write("support", null, "user:priya", "alpha priya prefers code examples", null, null);

		assertThat(this.memories.recall("alpha", "support", null, "user:ankit", 5, 0.5))
			.singleElement()
			.satisfies(m -> assertThat(m.content()).contains("ankit"));
	}

	@Test
	@DisplayName("importance lifts a memory above an equally similar one")
	void importanceRanks() {
		write("support", null, null, "alpha ordinary recollection", 1.0, null);
		write("support", null, null, "alpha important recollection", 3.0, null);

		List<MemoryService.Memory> found = this.memories.recall("alpha", "support", null, null, 5, 0.5);

		// Identical similarity by construction, so only importance can separate them.
		assertThat(found).hasSize(2);
		assertThat(found.get(0).content()).contains("important");
		assertThat(found.get(0).score()).isGreaterThan(found.get(1).score());
	}

	@Test
	@DisplayName("an older memory scores below an identical newer one")
	void recencyRanks() {
		write("support", null, null, "alpha remembered long ago", null, null);
		// Age it by 90 days — three half-lives, so roughly an eighth of its original weight.
		this.jdbc.update("update agent_memories set created_at = now() - interval '90 days'");
		write("support", null, null, "alpha remembered just now", null, null);

		List<MemoryService.Memory> found = this.memories.recall("alpha", "support", null, null, 5, 0.5);

		assertThat(found).hasSize(2);
		assertThat(found.get(0).content()).contains("just now");
		assertThat(found.get(1).score()).isLessThan(found.get(0).score() / 4);
	}

	@Test
	@DisplayName("unrelated memories fall below the similarity floor")
	void dissimilarIsExcluded() {
		write("support", null, null, "alpha about deployments", null, null);
		write("support", null, null, "omega about catering", null, null);

		List<MemoryService.Memory> found = this.memories.recall("alpha query", "support", null, null, 5, 0.35);

		// Without a floor every recall returns the requested number whether or not any of them
		// relate, and an agent states what it is handed.
		assertThat(found).singleElement().satisfies(m -> assertThat(m.content()).contains("deployments"));
	}

	@Test
	@DisplayName("an expired memory is never recalled, even before it is purged")
	void expiryIsEnforcedOnRead() {
		write("support", "s1", null, "alpha this was only for the session", null, 3600L);
		this.jdbc.update("update agent_memories set expires_at = now() - interval '1 minute'");

		assertThat(this.memories.recall("alpha", "support", null, null, 5, 0.0)).isEmpty();
		assertThat(this.memories.list("support", null, 50)).isEmpty();
		// Still present until purged — the read filter is what makes that safe.
		assertThat(this.jdbc.queryForObject("select count(*) from agent_memories", Integer.class)).isEqualTo(1);
		assertThat(this.memories.purgeExpired()).isEqualTo(1);
	}

	@Test
	@DisplayName("a memory with no expiry is kept")
	void noExpiryMeansKept() {
		write("support", null, null, "alpha durable fact", null, null);
		assertThat(this.memories.purgeExpired()).isZero();
		assertThat(this.memories.list("support", null, 50)).hasSize(1);
	}

	@Test
	@DisplayName("forgetting a session removes only that session")
	void forgetSession() {
		write("support", "s1", null, "alpha in session one", null, null);
		write("support", "s2", null, "alpha in session two", null, null);
		write("support", null, null, "alpha outside any session", null, null);

		assertThat(this.memories.forgetSession("support", "s1")).isEqualTo(1);
		assertThat(this.memories.list("support", null, 50)).hasSize(2);
	}

	@Test
	@DisplayName("use is recorded, which is what keeps a live memory from decaying away")
	void useIsCounted() {
		write("support", null, null, "alpha frequently needed", null, null);
		List<MemoryService.Memory> found = this.memories.recall("alpha", "support", null, null, 5, 0.5);

		this.memories.markUsed(found.stream().map(MemoryService.Memory::id).toList());
		this.memories.markUsed(found.stream().map(MemoryService.Memory::id).toList());

		MemoryService.Memory after = this.memories.list("support", null, 50).get(0);
		assertThat(after.useCount()).isEqualTo(2);
		assertThat(after.lastUsedAt()).isNotNull();
	}

	@Test
	@DisplayName("caller metadata round-trips, and malformed metadata does not break the write")
	void metadataIsKeptButNotTrusted() {
		this.memories.remember("support", null, null, "fact", "alpha with metadata",
				"{\"source\":\"crm\",\"confidence\":0.8}", null, null);
		this.memories.remember("support", null, null, "fact", "beta with broken metadata", "not json", null, null);

		List<MemoryService.Memory> all = this.memories.list("support", null, 50);
		assertThat(all).hasSize(2);
		assertThat(all).anySatisfy(m -> assertThat(m.metadata()).contains("crm"));
		// Unparseable metadata becomes an empty object rather than failing the write: losing the
		// annotation is recoverable, losing the memory is not.
		assertThat(all).anySatisfy(m -> assertThat(m.metadata()).isEqualTo("{}"));
	}

	@Test
	@DisplayName("deleting one memory leaves the rest")
	void forgetOne() {
		write("support", null, null, "alpha first", null, null);
		write("support", null, null, "beta second", null, null);
		var first = this.memories.list("support", null, 50).get(0);

		assertThat(this.memories.forget(first.id())).isTrue();
		assertThat(this.memories.list("support", null, 50)).hasSize(1);
	}

}
