package io.github.dockndevai.ossian.memory;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Renders {@link MemoryAccessException} with its message, for the memory routes only. */
@RestControllerAdvice(assignableTypes = MemoryController.class)
class MemoryExceptionHandler {

	@ExceptionHandler(MemoryAccessException.class)
	ResponseEntity<Map<String, Object>> accessDenied(MemoryAccessException ex) {
		return ResponseEntity.status(ex.status())
			.body(Map.of("status", ex.status().value(), "error", ex.status().getReasonPhrase(), "message",
					ex.getMessage()));
	}

}
