package io.github.dockndevai.ossian.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Reading the trail. Admin-only by way of the {@code /api/admin} prefix. */
@RestController
@RequestMapping("/api/admin/audit")
public class AuditController {

	private final AuditService audit;

	public AuditController(AuditService audit) {
		this.audit = audit;
	}

	@GetMapping
	public List<AuditService.Entry> recent(@RequestParam(required = false) String action,
			@RequestParam(required = false) String actor, @RequestParam(defaultValue = "100") int limit) {
		return this.audit.recent(blankToNull(action), blankToNull(actor), limit);
	}

	@GetMapping("/actions")
	public List<AuditService.ActionCount> actions(@RequestParam(defaultValue = "30") int days) {
		return this.audit.actions(days);
	}

	/** An empty filter parameter means "no filter", not "match the empty string". */
	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

}
