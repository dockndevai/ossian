package io.github.dockndevai.ossian.namespace;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.github.dockndevai.ossian.document.DocumentRepository;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Namespaces are readable by any signed-in user; creating one is an ordinary action too. */
@RestController
@RequestMapping("/api/namespaces")
public class NamespaceController {

	/**
	 * @param documents how many sources this namespace holds, and @param chunks how many
	 * passages they became. Returned with the list because a namespace picker that shows only
	 * names cannot answer the question people actually have of it — which one has my documents
	 * in it. An empty namespace is then visibly empty rather than a guess.
	 */
	public record NamespaceView(String name, String description, long documents, long chunks,
			Instant createdAt) {
	}

	public record CreateRequest(@NotBlank @Size(max = 128) String name, @Size(max = 500) String description) {
	}

	private final NamespaceService namespaces;

	private final DocumentRepository documents;

	public NamespaceController(NamespaceService namespaces, DocumentRepository documents) {
		this.namespaces = namespaces;
		this.documents = documents;
	}

	@GetMapping
	public List<NamespaceView> list() {
		return this.namespaces.list().stream().map(this::view).toList();
	}

	@PostMapping
	public NamespaceView create(@Valid @RequestBody CreateRequest request) {
		return view(this.namespaces.create(request.name(), request.description()));
	}

	private NamespaceView view(NamespaceEntity e) {
		return new NamespaceView(e.getName(), e.getDescription(),
				this.documents.countByNamespace(e.getName()), this.documents.sumChunksByNamespace(e.getName()),
				e.getCreatedAt());
	}

}
