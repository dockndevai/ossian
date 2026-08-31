package io.github.dockndevai.ossian.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an agent remembers, and how it gets it back.
 *
 * <p>Memory is not a document corpus with a different name, and the differences are what this
 * class is about.
 *
 * <p><b>Recency counts.</b> For a document, the best match is the best answer; a runbook written
 * three years ago is as true as one written today. For memory the opposite holds — "the user
 * prefers the dark theme" from this morning outranks the same sentence from last year, because
 * the recent one is likelier still to be true. Ranking is therefore similarity shaded by age and
 * by an importance the agent sets, not similarity alone.
 *
 * <p><b>Restatement is not new information.</b> An agent that writes what it already knows on
 * every turn would bury itself. Identical content within one scope updates the existing row and
 * refreshes it rather than adding another.
 *
 * <p><b>Some of it should be forgotten.</b> Session memory is scratch. Expiry is enforced on read
 * as well as by cleanup, so an expired memory is never returned even in the window before it is
 * deleted — a memory that outlives its stated lifetime is worse than one that was never kept.
 */
@Service
public class MemoryService {

	/**
	 * How much a memory's score decays with age.
	 *
	 * <p>A half-life, not a cliff: at 30 days a memory is worth half as much as an identical one
	 * written today, and it keeps mattering afterwards rather than vanishing. Long enough that
	 * durable facts survive, short enough that a stale preference loses to a fresh one.
	 */
	private static final Duration HALF_LIFE = Duration.ofDays(30);

	private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

	/** One remembered thing, with the score it was retrieved at. */
	public record Memory(UUID id, String agentId, String sessionId, String subject, String kind, String content,
			String metadata, double importance, Double similarity, Double score, Instant createdAt,
			Instant lastUsedAt, int useCount, Instant expiresAt) {
	}

	private final JdbcTemplate jdbc;

	private final EmbeddingModel embeddings;

	private final ObjectMapper json = new ObjectMapper();

	public MemoryService(JdbcTemplate jdbc, EmbeddingModel embeddings) {
		this.jdbc = jdbc;
		this.embeddings = embeddings;
	}

	/**
	 * Records something, or refreshes it if this agent already holds the same statement.
	 *
	 * @param ttlSeconds how long to keep it; null means indefinitely
	 */
	@Transactional
	public Memory remember(String agentId, String sessionId, String subject, String kind, String content,
			String metadataJson, Double importance, Long ttlSeconds) {
		String text = content.strip();
		String hash = hash(text);
		Instant expiresAt = (ttlSeconds == null || ttlSeconds <= 0) ? null
				: Instant.now().plusSeconds(ttlSeconds);
		String metadata = normaliseMetadata(metadataJson);

		float[] vector = this.embeddings.embed(text);

		// The conflict target matches the unique index, which coalesces a null session to '' so
		// that two session-less memories with the same content collide as intended. Postgres
		// treats plain nulls as distinct, which would let duplicates through.
		UUID id = this.jdbc.queryForObject("""
				insert into agent_memories
				  (agent_id, session_id, subject, kind, content, metadata, importance,
				   embedding, content_hash, expires_at)
				values (?, ?, ?, ?, ?, ?::jsonb, ?, ?::vector, ?, ?)
				on conflict (agent_id, coalesce(session_id, ''), content_hash) do update set
				  subject    = excluded.subject,
				  kind       = excluded.kind,
				  metadata   = excluded.metadata,
				  importance = greatest(agent_memories.importance, excluded.importance),
				  expires_at = excluded.expires_at,
				  updated_at = now()
				returning id
				""", UUID.class, agentId, sessionId, subject, kind, text, metadata,
				importance == null ? 1.0 : importance, literal(vector), hash, toTimestamp(expiresAt));

		return byId(id).orElseThrow(() -> new IllegalStateException("memory vanished after being written"));
	}

	/**
	 * Recalls what is relevant, ranked by similarity, recency and importance.
	 *
	 * <p>Scoping is additive: an agent sees its own memories and narrows further by session or
	 * subject if it asks to. Two agents do not read each other's recollections by default.
	 */
	public List<Memory> recall(String query, String agentId, String sessionId, String subject, int topK,
			double minSimilarity) {
		float[] vector = this.embeddings.embed(query);
		double halfLifeSeconds = HALF_LIFE.toSeconds();

		return this.jdbc.query("""
				select id, agent_id, session_id, subject, kind, content, metadata::text, importance,
				       1 - (embedding <=> ?::vector) as similarity,
				       created_at, last_used_at, use_count, expires_at,
				       (1 - (embedding <=> ?::vector))
				         * importance
				         * power(0.5, extract(epoch from (now() - created_at)) / ?) as score
				from agent_memories
				where agent_id = ?
				  and (?::text is null or session_id = ?)
				  and (?::text is null or subject = ?)
				  and (expires_at is null or expires_at > now())
				  and 1 - (embedding <=> ?::vector) >= ?
				order by score desc
				limit ?
				""", (rs, i) -> map(rs), literal(vector), literal(vector), halfLifeSeconds,
				agentId, sessionId, sessionId, subject, subject, literal(vector), minSimilarity, topK);
	}

	/** The agents that hold memories, with what each is carrying. */
	public List<AgentSummary> agents() {
		return this.jdbc.query("""
				select agent_id,
				       count(*) as total,
				       count(distinct session_id) as sessions,
				       count(distinct subject) filter (where subject is not null) as subjects,
				       max(created_at) as newest,
				       count(*) filter (where expires_at is not null) as expiring
				from agent_memories
				where expires_at is null or expires_at > now()
				group by agent_id
				order by max(created_at) desc
				""", (rs, i) -> new AgentSummary(rs.getString("agent_id"), rs.getLong("total"),
				rs.getLong("sessions"), rs.getLong("subjects"), rs.getLong("expiring"),
				rs.getTimestamp("newest").toInstant()));
	}

	/** One agent's memory at a glance, for an operator deciding whether it knows too much. */
	public record AgentSummary(String agentId, long memories, long sessions, long subjects, long expiring,
			Instant newest) {
	}

	/** Everything this agent holds, newest first — for an operator looking at what it knows. */
	public List<Memory> list(String agentId, String sessionId, int limit) {
		return this.jdbc.query("""
				select id, agent_id, session_id, subject, kind, content, metadata::text, importance,
				       null::float8 as similarity, created_at, last_used_at, use_count, expires_at,
				       null::float8 as score
				from agent_memories
				where agent_id = ?
				  and (?::text is null or session_id = ?)
				  and (expires_at is null or expires_at > now())
				order by created_at desc
				limit ?
				""", (rs, i) -> map(rs), agentId, sessionId, sessionId, limit);
	}

	public java.util.Optional<Memory> byId(UUID id) {
		List<Memory> found = this.jdbc.query("""
				select id, agent_id, session_id, subject, kind, content, metadata::text, importance,
				       null::float8 as similarity, created_at, last_used_at, use_count, expires_at,
				       null::float8 as score
				from agent_memories
				where id = ?
				""", (rs, i) -> map(rs), id);
		return found.stream().findFirst();
	}

	/** Records that these memories were used, which is what keeps a live one from decaying away. */
	@Transactional
	public void markUsed(List<UUID> ids) {
		if (ids == null || ids.isEmpty()) {
			return;
		}
		String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
		this.jdbc.update("update agent_memories set last_used_at = now(), use_count = use_count + 1 "
				+ "where id in (" + placeholders + ")", ids.toArray());
	}

	@Transactional
	public boolean forget(UUID id) {
		return this.jdbc.update("delete from agent_memories where id = ?", id) > 0;
	}

	/** Forgets a whole session — what you call when a conversation ends. */
	@Transactional
	public int forgetSession(String agentId, String sessionId) {
		int removed = this.jdbc.update(
				"delete from agent_memories where agent_id = ? and session_id = ?", agentId, sessionId);
		log.debug("forgot {} memories for session {}", removed, sessionId);
		return removed;
	}

	/** Removes expired rows. Reads already exclude them; this reclaims the space. */
	@Transactional
	public int purgeExpired() {
		return this.jdbc.update("delete from agent_memories where expires_at is not null and expires_at <= now()");
	}

	private Memory map(java.sql.ResultSet rs) throws java.sql.SQLException {
		Double similarity = (Double) rs.getObject("similarity");
		Double score = (Double) rs.getObject("score");
		java.sql.Timestamp lastUsed = rs.getTimestamp("last_used_at");
		java.sql.Timestamp expires = rs.getTimestamp("expires_at");
		return new Memory(rs.getObject("id", UUID.class), rs.getString("agent_id"), rs.getString("session_id"),
				rs.getString("subject"), rs.getString("kind"), rs.getString("content"), rs.getString(7),
				rs.getDouble("importance"), similarity, score, rs.getTimestamp("created_at").toInstant(),
				lastUsed == null ? null : lastUsed.toInstant(), rs.getInt("use_count"),
				expires == null ? null : expires.toInstant());
	}

	/** Caller metadata is stored as given but must at least be an object, or it cannot be indexed. */
	private String normaliseMetadata(String metadataJson) {
		if (metadataJson == null || metadataJson.isBlank()) {
			return "{}";
		}
		try {
			var node = this.json.readTree(metadataJson);
			return node.isObject() ? metadataJson : "{}";
		}
		catch (Exception ex) {
			return "{}";
		}
	}

	private static java.sql.Timestamp toTimestamp(Instant instant) {
		return instant == null ? null : java.sql.Timestamp.from(instant);
	}

	static String hash(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}

	static String literal(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 8).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}

}
