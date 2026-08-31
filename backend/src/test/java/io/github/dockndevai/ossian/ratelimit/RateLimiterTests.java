package io.github.dockndevai.ossian.ratelimit;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The limiter's contract with the rest of the application.
 *
 * <p>The bucket arithmetic lives in Lua and is exercised against a real Redis by hand; what
 * matters here is the behaviour around it, and especially the failure mode — a limiter that
 * fails closed turns a cache outage into a total outage, and one that has silently stopped
 * limiting looks exactly like one with nothing to do.
 */
class RateLimiterTests {

	@SuppressWarnings("unchecked")
	private static RateLimiter withRedisReturning(Object result) {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
			.thenReturn(result);
		return new RateLimiter(redis);
	}

	@Test
	@DisplayName("a request within the allowance is permitted")
	void allowsWithinTheLimit() {
		RateLimiter limiter = withRedisReturning(List.of(1L, 59L, 0L));

		RateLimiter.Decision decision = limiter.check("caller", 60);

		assertThat(decision.allowed()).isTrue();
		assertThat(decision.remaining()).isEqualTo(59);
		assertThat(decision.limit()).isEqualTo(60);
	}

	@Test
	@DisplayName("an exhausted bucket refuses, and says when to come back")
	void refusesWhenExhausted() {
		RateLimiter limiter = withRedisReturning(List.of(0L, 0L, 12_000L));

		RateLimiter.Decision decision = limiter.check("caller", 5);

		assertThat(decision.allowed()).isFalse();
		// A client told only "no" retries immediately and makes it worse.
		assertThat(decision.retryAfterMillis()).isEqualTo(12_000);
	}

	@Test
	@DisplayName("a limit of zero or less means no limit at all")
	void zeroDisables() {
		// Redis would throw if it were consulted, which is the point: it should not be.
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
			.thenThrow(new IllegalStateException("should not be called"));

		assertThat(new RateLimiter(redis).check("caller", 0).allowed()).isTrue();
		assertThat(new RateLimiter(redis).check("caller", -1).allowed()).isTrue();
	}

    @Test
	@DisplayName("an unreachable Redis allows the request rather than failing it")
	void failsOpen() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
			.thenThrow(new RedisConnectionFailureException("down"));

		RateLimiter.Decision decision = new RateLimiter(redis).check("caller", 60);

		// The thing being protected against — one caller making too many requests — is
		// survivable in a way that refusing every request is not.
		assertThat(decision.allowed()).isTrue();
	}

	@Test
	@DisplayName("a malformed reply is treated as permission, not as a refusal")
	void malformedReplyAllows() {
		assertThat(withRedisReturning(List.of(1L)).check("caller", 60).allowed()).isTrue();
		assertThat(withRedisReturning(null).check("caller", 60).allowed()).isTrue();
	}

	@Test
	@DisplayName("each caller gets their own bucket")
	void bucketsAreKeyedByCaller() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		java.util.List<String> keysSeen = new java.util.ArrayList<>();
		when(redis.execute(any(RedisScript.class), any(List.class), any(Object[].class)))
			.thenAnswer(invocation -> {
				keysSeen.addAll(invocation.getArgument(1));
				return List.of(1L, 1L, 0L);
			});

		RateLimiter limiter = new RateLimiter(redis);
		limiter.check("key:one", 60);
		limiter.check("key:two", 60);

		// Sharing a bucket would let one caller exhaust another's allowance.
		assertThat(keysSeen).containsExactly("ossian:rl:key:one", "ossian:rl:key:two");
		assertThat(keysSeen).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("the script is loaded from the classpath, so a missing file fails at startup")
	void scriptIsPresent() throws Exception {
		// Lazily-loaded Lua that is missing would first be noticed by a 500 under load.
		var resource = new org.springframework.core.io.ClassPathResource("rate-limit.lua");
		assertThat(resource.exists()).isTrue();
		String lua = new String(resource.getInputStream().readAllBytes());
		assertThat(lua).contains("PEXPIRE").contains("capacity");
	}

}
