package io.github.dockndevai.ossian.client;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OssianClientTests {

	@Test
	@DisplayName("a batch is split into chunks the server will accept")
	void partitionsLongBatches() {
		List<Map<String, Object>> events = new java.util.ArrayList<>();
		for (int i = 0; i < 1200; i++) {
			events.add(OssianClient.event("e-" + i, "UPSERT", "src/" + i, "default", "body"));
		}
		var batches = OssianClient.partition(events, 500);

		assertThat(batches).hasSize(3);
		assertThat(batches.get(0)).hasSize(500);
		assertThat(batches.get(2)).hasSize(200);
		assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(1200);
	}

	@Test
	@DisplayName("an absurd batch size is clamped rather than trusted")
	void clampsBatchSize() {
		List<Map<String, Object>> events = new java.util.ArrayList<>();
		for (int i = 0; i < 40; i++) {
			events.add(OssianClient.event("e-" + i, "UPSERT", "src/" + i, "default", "body"));
		}
		assertThat(OssianClient.partition(events, 100_000).get(0)).hasSize(40);
		assertThat(OssianClient.partition(events, 0)).hasSize(40);
	}

	@Test
	@DisplayName("retryable distinguishes a server's bad day from the caller's bad request")
	void retryability() {
		assertThat(new OssianClient.OssianException(503, "x").retryable()).isTrue();
		assertThat(new OssianClient.OssianException(429, "x").retryable()).isTrue();
		// A 400 or a 403 will fail identically however many times it is sent.
		assertThat(new OssianClient.OssianException(400, "x").retryable()).isFalse();
		assertThat(new OssianClient.OssianException(403, "x").retryable()).isFalse();
	}

	@Test
	@DisplayName("a duplicate is a distinct outcome from a failure")
	void duplicateIsNotFailure() {
		var duplicate = new OssianClient.EventResult("e-1", "DUPLICATE", "doc-1", "seen before");
		assertThat(duplicate.duplicate()).isTrue();
		assertThat(duplicate.failed()).isFalse();

		var failed = new OssianClient.EventResult("e-2", "FAILED", null, "no body");
		assertThat(failed.failed()).isTrue();
		assertThat(failed.duplicate()).isFalse();
	}

	@Test
	@DisplayName("the event helper omits absent text rather than sending null")
	void eventHelperOmitsNullText() {
		assertThat(OssianClient.event("e", "DELETE", "src/1", "default", null)).doesNotContainKey("text");
		assertThat(OssianClient.event("e", "UPSERT", "src/1", "default", "hi")).containsEntry("text", "hi");
	}

}
