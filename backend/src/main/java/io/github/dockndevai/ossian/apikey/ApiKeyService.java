package io.github.dockndevai.ossian.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.dockndevai.ossian.caller.CallerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and resolves machine credentials.
 *
 * <p>Keys are random, hashed before storage, and returned exactly once. There is no endpoint that
 * reveals an existing key and no way to add one: a system where an administrator can read out a
 * tenant's credentials is a system where an attacker who reaches the administrator can too.
 *
 * <p>SHA-256 rather than bcrypt deliberately. Password hashes are deliberately slow because
 * passwords are low-entropy and guessable; a 256-bit random key is neither, and this runs on
 * every authenticated request. The slow hash would buy nothing and cost a great deal.
 */
@Service
public class ApiKeyService {

	/** Marks the string as ours, so a leaked key is recognisable in a log or a repository scan. */
	public static final String PREFIX = "osk_";

	private static final int SECRET_BYTES = 32;

	/** Same ceiling as {@code agent_memories.agent_id} and the memory request bodies. */
	public static final int AGENT_ID_MAX_LENGTH = 128;

	/**
	 * What a bound agent id may look like: no control characters and no leading or trailing
	 * whitespace.
	 *
	 * <p>The charset is otherwise as open as the agentId memory routes accept, because a binding
	 * has to be able to name any agent that already holds memories. The edges are closed because
	 * {@code "bot"} and {@code "bot "} are different agents to the database and the same one to a
	 * person reading a console, and a binding that only looks right is worse than none.
	 */
	public static final String AGENT_ID_PATTERN = "^[^\\p{Cntrl}\\s](?:[^\\p{Cntrl}]*[^\\p{Cntrl}\\s])?$";

	private static final java.util.regex.Pattern AGENT_ID = java.util.regex.Pattern.compile(AGENT_ID_PATTERN);

	private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

	private final ApiKeyRepository repository;

	private final CallerContext caller;

	private final SecureRandom random = new SecureRandom();

	public ApiKeyService(ApiKeyRepository repository, CallerContext caller) {
		this.repository = repository;
		this.caller = caller;
	}

	/** A newly issued key. The secret is present here and nowhere else, ever again. */
	public record Issued(ApiKeyEntity key, String secret) {
	}

	@Transactional
	public Issued issue(String name, List<String> roles, String namespace, Integer requestsPerMinute,
			Instant expiresAt) {
		return issue(name, roles, namespace, null, requestsPerMinute, expiresAt);
	}

	/**
	 * Issues a key, optionally bound to one agent.
	 *
	 * @param agentId the agent the key acts as on memory routes; null for an unbound key
	 * @throws IllegalArgumentException if agentId is present but not a valid agent id
	 */
	@Transactional
	public Issued issue(String name, List<String> roles, String namespace, String agentId,
			Integer requestsPerMinute, Instant expiresAt) {
		String boundAgent = (agentId == null) ? null : requireValidAgentId(agentId);
		byte[] bytes = new byte[SECRET_BYTES];
		this.random.nextBytes(bytes);
		String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		ApiKeyEntity entity = new ApiKeyEntity();
		entity.setName(name);
		entity.setKeyHash(hash(secret));
		entity.setKeyPrefix(secret.substring(0, Math.min(secret.length(), 12)));
		entity.setRoles(roles == null || roles.isEmpty() ? "ossian-user" : String.join(",", roles));
		entity.setNamespace((namespace == null || namespace.isBlank()) ? null : namespace);
		entity.setAgentId(boundAgent);
		entity.setCreatedBy(this.caller.username());
		entity.setRequestsPerMinute(requestsPerMinute);
		entity.setExpiresAt(expiresAt);

		ApiKeyEntity saved = this.repository.save(entity);
		// The name and prefix, never the secret. A key in a log file is a key in everyone's hands.
		log.info("issued api key '{}' ({})", saved.getName(), saved.getKeyPrefix());
		return new Issued(saved, secret);
	}

	/**
	 * Binds a key to an agent, or unbinds it when agentId is null.
	 *
	 * <p>Takes effect on the key's next request: the key is resolved from the database every time
	 * rather than cached, so there is no window in which the old binding still applies.
	 *
	 * @return the updated key, or empty if there is no such key
	 * @throws IllegalArgumentException if agentId is present but not a valid agent id
	 */
	@Transactional
	public Optional<ApiKeyEntity> bindAgent(UUID id, String agentId) {
		String boundAgent = (agentId == null) ? null : requireValidAgentId(agentId);
		return this.repository.findById(id).map(key -> {
			key.setAgentId(boundAgent);
			ApiKeyEntity saved = this.repository.save(key);
			log.info("api key '{}' ({}) is now {}", saved.getName(), saved.getKeyPrefix(),
					boundAgent == null ? "unbound" : "bound to agent '" + boundAgent + "'");
			return saved;
		});
	}

	/** Whether this is acceptable as the agent a key is bound to. */
	public static boolean isValidAgentId(String agentId) {
		return agentId != null && agentId.length() <= AGENT_ID_MAX_LENGTH && AGENT_ID.matcher(agentId).matches();
	}

	private static String requireValidAgentId(String agentId) {
		if (!isValidAgentId(agentId)) {
			throw new IllegalArgumentException("agentId must be 1-" + AGENT_ID_MAX_LENGTH
					+ " characters, with no control characters and no leading or trailing whitespace");
		}
		return agentId;
	}

	/**
	 * Resolves a presented key, or empty if it is unknown, revoked or expired.
	 *
	 * <p>The lookup is by hash, so an attacker with read access to the table still cannot
	 * authenticate: they hold digests, and the request needs the preimage.
	 */
	@Transactional
	public Optional<ApiKeyEntity> resolve(String presented) {
		if (presented == null || !presented.startsWith(PREFIX)) {
			return Optional.empty();
		}
		Optional<ApiKeyEntity> found = this.repository.findByKeyHash(hash(presented));
		if (found.isEmpty()) {
			return Optional.empty();
		}
		ApiKeyEntity key = found.get();
		if (!key.isUsable(Instant.now())) {
			log.debug("api key {} presented but is revoked or expired", key.getKeyPrefix());
			return Optional.empty();
		}
		touch(key);
		return Optional.of(key);
	}

	/**
	 * Records use, but only about once a minute per key.
	 *
	 * <p>Written on every request this would be a row update per API call — the busiest write in
	 * the system, in service of a timestamp nobody reads to the second. Coarse is the point.
	 */
	private void touch(ApiKeyEntity key) {
		Instant now = Instant.now();
		if (key.getLastUsedAt() == null || key.getLastUsedAt().isBefore(now.minusSeconds(60))) {
			key.setLastUsedAt(now);
			this.repository.save(key);
		}
	}

	public List<ApiKeyEntity> list() {
		return this.repository.findAllByOrderByCreatedAtDesc();
	}

	/**
	 * Revokes a key. Kept as a row rather than deleted, so that a key seen in an old log can
	 * still be identified afterwards.
	 */
	@Transactional
	public boolean revoke(UUID id) {
		return this.repository.findById(id).map(key -> {
			if (key.getRevokedAt() == null) {
				key.setRevokedAt(Instant.now());
				this.repository.save(key);
				log.info("revoked api key '{}' ({})", key.getName(), key.getKeyPrefix());
			}
			return true;
		}).orElse(false);
	}

	static String hash(String secret) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by the JDK", ex);
		}
	}

}
