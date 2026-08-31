package io.github.dockndevai.ossian.namespace;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Namespaces are readable by any signed-in user; creating one is an ordinary action too. */
@RestController
@RequestMapping("/api/namespaces")
public class NamespaceController {

	public record NamespaceView(String name, String description, Instant createdAt) {

		static NamespaceView of(NamespaceEntity e) {
			return new NamespaceView(e.getName(), e.getDescription(), e.getCreatedAt());
		}
	}

	public record CreateRequest(@NotBlank @Size(max = 128) String name, @Size(max = 500) String description) {
	}

	private final NamespaceService namespaces;

	public NamespaceController(NamespaceService namespaces) {
		this.namespaces = namespaces;
	}

	@GetMapping
	public List<NamespaceView> list() {
		return this.namespaces.list().stream().map(NamespaceView::of).toList();
	}

	@PostMapping
	public NamespaceView create(@Valid @RequestBody CreateRequest request) {
		return NamespaceView.of(this.namespaces.create(request.name(), request.description()));
	}

}
