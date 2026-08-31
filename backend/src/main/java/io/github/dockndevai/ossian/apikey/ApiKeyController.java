package io.github.dockndevai.ossian.apikey;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import io.github.dockndevai.ossian.audit.AuditService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issuing and revoking machine credentials. Admin-only, by way of the {@code /api/admin} prefix.
 */
@RestController
@RequestMapping("/api/admin/api-keys")
public class ApiKeyController {

	public record CreateRequest(@NotBlank @Size(max = 200) String name, List<String> roles,
			@Size(max = 128) String namespace, Integer requestsPerMinute, Instant expiresAt) {
	}

	/** What a key looks like in a listing: enough to recognise, never enough to use. */
	public record KeyView(UUID id, String name, String keyPrefix, List<String> roles, String namespace,
			Integer requestsPerMinute, String createdBy, Instant createdAt, Instant lastUsedAt,
			Instant expiresAt, Instant revokedAt, boolean active) {

		static KeyView of(ApiKeyEntity k) {
			return new KeyView(k.getId(), k.getName(), k.getKeyPrefix(), k.roleList(), k.getNamespace(),
					k.getRequestsPerMinute(), k.getCreatedBy(), k.getCreatedAt(), k.getLastUsedAt(),
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
				request.requestsPerMinute(), request.expiresAt());
		// The prefix and name, never the secret — an audit row is not a place to put a credential.
		this.audit.record(AuditService.KEY_ISSUED, "apikey", issued.key().getKeyPrefix(),
				issued.key().getNamespace(),
				issued.key().getName() + " roles=" + issued.key().getRoles());
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new CreatedKey(KeyView.of(issued.key()), issued.secret(),
					"Copy this now. It is stored as a hash and cannot be shown again."));
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
