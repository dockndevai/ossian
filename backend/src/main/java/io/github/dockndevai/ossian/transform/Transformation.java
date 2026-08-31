package io.github.dockndevai.ossian.transform;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A named prompt that is run over a whole source.
 *
 * <p>Not a question. A question retrieves the passages most similar to it; a transformation
 * reads the entire document. "Summarise this" cannot be answered from the chunks nearest to the
 * word "summarise", so this path does not touch the retriever at all.
 */
@Entity
@Table(name = "transformations")
public class Transformation {

	@Id
	@GeneratedValue
	private UUID id;


	@Column(nullable = false)
	private String slug;

	@Column(nullable = false)
	private String name;

	private String description;

	@Column(nullable = false, columnDefinition = "text")
	private String prompt;

	@Column(name = "apply_on_ingest", nullable = false)
	private boolean applyOnIngest;

	@Column(name = "position", nullable = false)
	private int position;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	/** Slugified name, constrained because it appears in URLs and in client code. */
	public static String slugify(String raw) {
		if (raw == null || raw.isBlank()) {
			return "transformation";
		}
		String s = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
		if (s.isEmpty()) {
			return "transformation";
		}
		return (s.length() > 128) ? s.substring(0, 128) : s;
	}

	public UUID getId() {
		return this.id;
	}

	public void setId(UUID id) {
		this.id = id;
	}



	public String getSlug() {
		return this.slug;
	}

	public void setSlug(String slug) {
		this.slug = slug;
	}

	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getPrompt() {
		return this.prompt;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public boolean isApplyOnIngest() {
		return this.applyOnIngest;
	}

	public void setApplyOnIngest(boolean applyOnIngest) {
		this.applyOnIngest = applyOnIngest;
	}

	public int getPosition() {
		return this.position;
	}

	public void setPosition(int position) {
		this.position = position;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}

}
