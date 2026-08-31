package io.github.dockndevai.ossian.memory;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Memory for agents built on top of this.
 *
 * <p>Separate from {@code /api/chat} on purpose. Chat answers a question from a document corpus;
 * this is the agent's own recollection of what happened and what it was told, which has different
 * lifetime, different ranking and a different audience. Mixing them would put "the user said they
 * prefer email" in the citations of an answer about the deployment policy.
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

	public record RememberRequest(@NotBlank @Size(max = 128) String agentId, @Size(max = 128) String sessionId,
			@Size(max = 256) String subject, @Size(max = 32) String kind,
			@NotBlank @Size(max = 8000) String content, String metadata, Double importance, Long ttlSeconds) {
	}

	public record RecallRequest(@NotBlank @Size(max = 128) String agentId, @NotBlank @Size(max = 2000) String query,
			@Size(max = 128) String sessionId, @Size(max = 256) String subject, Integer topK,
			Double minSimilarity) {
	}

	private static final int DEFAULT_TOP_K = 8;

	/**
	 * Floor on similarity.
	 *
	 * <p>Not zero. Without a floor every recall returns the requested number of memories whether
	 * or not any of them relate to the question, and an agent handed irrelevant recollections
	 * states them as context — which reads to a user as the system making things up.
	 */
	private static final double DEFAULT_MIN_SIMILARITY = 0.35;

	private final MemoryService memories;

	public MemoryController(MemoryService memories) {
		this.memories = memories;
	}

	@PostMapping
	public ResponseEntity<MemoryService.Memory> remember(@Valid @RequestBody RememberRequest request) {
		MemoryService.Memory saved = this.memories.remember(request.agentId(), request.sessionId(),
				request.subject(), (request.kind() == null || request.kind().isBlank()) ? "fact" : request.kind(),
				request.content(), request.metadata(), request.importance(), request.ttlSeconds());
		return ResponseEntity.status(HttpStatus.CREATED).body(saved);
	}

	@PostMapping("/recall")
	public List<MemoryService.Memory> recall(@Valid @RequestBody RecallRequest request) {
		int topK = (request.topK() == null) ? DEFAULT_TOP_K : Math.min(Math.max(request.topK(), 1), 50);
		double floor = (request.minSimilarity() == null) ? DEFAULT_MIN_SIMILARITY
				: Math.min(Math.max(request.minSimilarity(), 0), 1);

		List<MemoryService.Memory> found = this.memories.recall(request.query(), request.agentId(),
				request.sessionId(), request.subject(), topK, floor);
		// Use is recorded so a memory that keeps proving useful stops decaying like one nobody
		// has needed since it was written.
		this.memories.markUsed(found.stream().map(MemoryService.Memory::id).toList());
		return found;
	}

	/** Which agents hold memories. The entry point for an operator who does not know the ids. */
	@GetMapping("/agents")
	public List<MemoryService.AgentSummary> agents() {
		return this.memories.agents();
	}

	@GetMapping
	public List<MemoryService.Memory> list(@RequestParam String agentId,
			@RequestParam(required = false) String sessionId, @RequestParam(defaultValue = "50") int limit) {
		return this.memories.list(agentId, sessionId, Math.min(Math.max(limit, 1), 200));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> forget(@PathVariable UUID id) {
		return this.memories.forget(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	/** Ends a conversation's memory. What you call when a session is over. */
	@DeleteMapping("/sessions/{sessionId}")
	public ResponseEntity<java.util.Map<String, Integer>> forgetSession(@RequestParam String agentId,
			@PathVariable String sessionId) {
		return ResponseEntity.ok(java.util.Map.of("forgotten", this.memories.forgetSession(agentId, sessionId)));
	}

}
