package io.github.dockndevai.ossian.transform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * Transformations and the insights they produce.
 *
 * <p>Reading and running are ordinary user actions — a transformation is a way of reading your
 * own document. Editing the prompt library is not under {@code /api/admin} either, because a
 * bad prompt produces a bad insight and nothing worse; the prompts that decide what the system
 * will refuse to answer live in settings, which is admin-only.
 */
@RestController
@RequestMapping("/api")
public class TransformationController {

	public record TransformationView(UUID id, String slug, String name, String description, String prompt,
			boolean applyOnIngest, int position, Instant updatedAt) {

		static TransformationView of(Transformation t) {
			return new TransformationView(t.getId(), t.getSlug(), t.getName(), t.getDescription(), t.getPrompt(),
					t.isApplyOnIngest(), t.getPosition(), t.getUpdatedAt());
		}
	}

	/**
	 * @param fromCache whether this output was reused from an earlier identical run rather than
	 * recomputed. Reported rather than hidden: a cached result presented as fresh is a small lie
	 * that becomes a large one the moment someone edits a prompt and cannot tell whether the
	 * output reflects the change.
	 */
	public record InsightView(UUID id, UUID documentId, String transformationName, String promptUsed, String output,
			String model, int passes, Long durationMs, String createdBy, boolean fromCache, Instant createdAt) {

		static InsightView of(Insight i) {
			return new InsightView(i.getId(), i.getDocumentId(), i.getTransformationName(), i.getPromptUsed(),
					i.getOutput(), i.getModel(), i.getPasses(), i.getDurationMs(), i.getCreatedBy(),
					i.isFromCache(), i.getCreatedAt());
		}
	}

	public record SaveRequest(@NotBlank @Size(max = 200) String name, @Size(max = 500) String description,
			@NotBlank String prompt, boolean applyOnIngest, Integer position) {
	}

	private final TransformationService service;

	public TransformationController(TransformationService service) {
		this.service = service;
	}

	@GetMapping("/transformations")
	public List<TransformationView> list() {
		return this.service.list().stream().map(TransformationView::of).toList();
	}

	@PostMapping("/transformations")
	public ResponseEntity<?> create(@Valid @RequestBody SaveRequest request) {
		return save(null, request);
	}

	@PutMapping("/transformations/{id}")
	public ResponseEntity<?> update(@PathVariable UUID id, @Valid @RequestBody SaveRequest request) {
		return save(id, request);
	}

	private ResponseEntity<?> save(UUID id, SaveRequest request) {
		try {
			Transformation saved = this.service.save(id, request.name(), request.description(), request.prompt(),
					request.applyOnIngest(), request.position());
			return ResponseEntity.ok(TransformationView.of(saved));
		}
		catch (IllegalArgumentException ex) {
			// The message names what is wrong with the prompt, so the editor can show it against
			// the field rather than as an opaque failure.
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
		}
	}

	@DeleteMapping("/transformations/{id}")
	public ResponseEntity<Void> delete(@PathVariable UUID id) {
		this.service.delete(id);
		return ResponseEntity.noContent().build();
	}

	/** Runs a transformation over a document. Synchronous: the caller wants the result. */
	@PostMapping("/documents/{documentId}/transformations/{slug}")
	public ResponseEntity<?> run(@PathVariable UUID documentId, @PathVariable String slug) {
		try {
			return ResponseEntity.ok(InsightView.of(this.service.run(documentId, slug)));
		}
		catch (IllegalArgumentException ex) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
		}
	}

	@GetMapping("/documents/{documentId}/insights")
	public List<InsightView> insights(@PathVariable UUID documentId) {
		return this.service.insightsFor(documentId).stream().map(InsightView::of).toList();
	}

	@DeleteMapping("/insights/{id}")
	public ResponseEntity<Void> deleteInsight(@PathVariable UUID id) {
		this.service.deleteInsight(id);
		return ResponseEntity.noContent().build();
	}

}
