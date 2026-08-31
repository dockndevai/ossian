package io.github.dockndevai.ossian.settings;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime configuration, editable per tenant.
 *
 * <p>Under {@code /api/admin} so it inherits the admin-role rule: retrieval thresholds and the
 * system prompt decide what the system will and will not answer, which is not something an
 * ordinary user should be able to change for everyone in their tenant.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class SettingsController {

	public record UpdateRequest(@NotBlank String value) {
	}

	private final SettingsService settings;

	public SettingsController(SettingsService settings) {
		this.settings = settings;
	}

	@GetMapping
	public List<SettingsService.SettingView> list() {
		return this.settings.all();
	}

	@PutMapping("/{key}")
	public ResponseEntity<?> update(@PathVariable String key, @RequestBody UpdateRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		try {
			this.settings.set(key, request.value(), (jwt == null) ? null : jwt.getClaimAsString("preferred_username"));
			return ResponseEntity.ok(this.settings.all());
		}
		catch (IllegalArgumentException ex) {
			// The message names the setting and the bound it broke, so the form can show it
			// against the field rather than as an opaque failure.
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
		}
	}

	@DeleteMapping("/{key}")
	public ResponseEntity<?> reset(@PathVariable String key) {
		try {
			this.settings.reset(key);
			return ResponseEntity.ok(this.settings.all());
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
		}
	}

}
