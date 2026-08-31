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
	public Issued issue(String name, List<String> roles, String namespace, Instant expiresAt) {
		byte[] bytes = new byte[SECRET_BYTES];
		this.random.nextBytes(bytes);
		String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		ApiKeyEntity entity = new ApiKeyEntity();
		entity.setName(name);
		entity.setKeyHash(hash(secret));
		entity.setKeyPrefix(secret.substring(0, Math.min(secret.length(), 12)));
		entity.setRoles(roles == null || roles.isEmpty() ? "ossian-user" : String.join(",", roles));
		entity.setNamespace((namespace == null || namespace.isBlank()) ? null : namespace);
		entity.setCreatedBy(this.caller.username());
		entity.setExpiresAt(expiresAt);

		ApiKeyEntity saved = this.repository.save(entity);
		// The name and prefix, never the secret. A key in a log file is a key in everyone's hands.
		log.info("issued api key '{}' ({})", saved.getName(), saved.getKeyPrefix());
		return new Issued(saved, secret);
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
