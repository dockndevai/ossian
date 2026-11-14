package io.github.dockndevai.ossian.apikey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import io.github.dockndevai.ossian.audit.AuditService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing and revoking machine credentials. Admin-only, by way of the {@code /api/admin} prefix.
 */
@RestController
@RequestMapping("/api/admin/api-keys")
public class ApiKeyController {

	/**
	 * @param agentId optional. Binds the key to one agent: on memory routes it can then only act as
	 * that agent. Leave it out for an unbound key, which is what every key was before this existed.
	 */
	public record CreateRequest(@NotBlank @Size(max = 200) String name, List<String> roles,
			@Size(max = 128) String namespace,
			@Size(min = 1, max = ApiKeyService.AGENT_ID_MAX_LENGTH) @Pattern(regexp = ApiKeyService.AGENT_ID_PATTERN,
					message = "must not contain control characters or start or end with whitespace") String agentId,
			Integer requestsPerMinute, Instant expiresAt) {
	}

	/** Binds an existing key to an agent. To remove a binding, {@code DELETE} the same path. */
	public record BindRequest(@NotNull @Size(min = 1, max = ApiKeyService.AGENT_ID_MAX_LENGTH) @Pattern(
			regexp = ApiKeyService.AGENT_ID_PATTERN,
			message = "must not contain control characters or start or end with whitespace") String agentId) {
	}

	/** What a key looks like in a listing: enough to recognise, never enough to use. */
	public record KeyView(UUID id, String name, String keyPrefix, List<String> roles, String namespace,
			String agentId, Integer requestsPerMinute, String createdBy, Instant createdAt, Instant lastUsedAt,
			Instant expiresAt, Instant revokedAt, boolean active) {

		static KeyView of(ApiKeyEntity k) {
			return new KeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.roleList(), k.getNamespace(),
					k.getAgentId(), k.getRequestsPerMinute(), k.getCreatedBy(), k.getCreatedAt(), k.getLastUsedAt(),
					k.getExpiresAt(), k.getRevokedAt(), k.isUsable(Instant.now()));
		}
	}

	/**
	 * The one response that carries a secret.
	 *
	 * @param secret the key itself, returned only here. It is stored as a hash, so this is the
	 * single moment it exists in readable form — losing it means issuing a new one.
	 */
	public record CreatedKey(KeyView key, String secret, String note) {
	}

	private final ApiKeyService service;

	private final AuditService audit;

	public ApiKeyController(ApiKeyService service, AuditService audit) {
		this.service = service;
		this.audit = audit;
	}

	@GetMapping
	public List<KeyView> list() {
		return this.service.list().stream().map(KeyView::of).toList();
	}

	@PostMapping
	public ResponseEntity<CreatedKey> create(@Valid @RequestBody CreateRequest request) {
		ApiKeyService.Issued issued = this.service.issue(request.name(), request.roles(), request.namespace(),
				request.agentId(), request.requestsPerMinute(), request.expiresAt());
		// The prefix and name, never the secret — an audit row is not a place to put a credential.
		this.audit.record(AuditService.KEY_ISSUED, "apikey", issued.key().getKeyPrefix(),
				issued.key().getNamespace(),
				issued.key().getName() + " roles=" + issued.key().getRoles()
						+ (issued.key().getAgentId() == null ? "" : " agent=" + issued.key().getAgentId()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new CreatedKey(KeyView.of(issued.key()), issued.secret(),
					"Copy this now. It is stored as a hash and cannot be shown again."));
	}

	/**
	 * Binds a key to an agent. Takes effect on the key's next request.
	 *
	 * <p>This is how a key issued before binding existed regains access to memory if it is
	 * namespace-confined: such a key is refused on memory routes until it is bound.
	 */
	@PutMapping("/{id}/agent")
	public ResponseEntity<KeyView> bind(@PathVariable UUID id, @Valid @RequestBody BindRequest request) {
		return this.service.bindAgent(id, request.agentId()).map(key -> {
			this.audit.record(AuditService.KEY_UPDATED, "apikey", key.getKeyPrefix(), key.getNamespace(),
					key.getName() + " agent=" + key.getAgentId());
			return ResponseEntity.ok(KeyView.of(key));
		}).orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Removes a key's binding. The key then falls back to the unbound rules for memory routes. */
	@DeleteMapping("/{id}/agent")
	public ResponseEntity<KeyView> unbind(@PathVariable UUID id) {
		return this.service.bindAgent(id, null).map(key -> {
			this.audit.record(AuditService.KEY_UPDATED, "apikey", key.getKeyPrefix(), key.getNamespace(),
					key.getName() + " agent=(unbound)");
			return ResponseEntity.ok(KeyView.of(key));
		}).orElseGet(() -> ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> revoke(@PathVariable UUID id) {
		if (!this.service.revoke(id)) {
			return ResponseEntity.notFound().build();
		}
		this.audit.record(AuditService.KEY_REVOKED, "apikey", id.toString(), null, null);
		return ResponseEntity.noContent().build();
	}

}
