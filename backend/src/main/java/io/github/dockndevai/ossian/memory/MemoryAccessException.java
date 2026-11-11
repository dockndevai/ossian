package io.github.dockndevai.ossian.memory;

import org.springframework.http.HttpStatus;

/**
 * A memory request the caller is not allowed to make, or made incompletely.
 *
 * <p>Its own type rather than {@code ResponseStatusException} because Boot does not put an
 * exception's message in the error body by default, and the message is the point here: a caller
 * refused for being unbound needs to be told to bind the key, not just that it was refused.
 */
public class MemoryAccessException extends RuntimeException {

	private final HttpStatus status;

	public MemoryAccessException(HttpStatus status, String message) {
		super(message);
		this.status = status;
	}

	public HttpStatus status() {
		return this.status;
	}

}
