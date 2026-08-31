package io.github.dockndevai.ossian.ratelimit;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

/**
 * A token bucket per caller, held in Redis.
 *
 * <p>In Redis rather than in memory because the limit has to hold across instances: two replicas
 * each enforcing sixty a minute enforce a hundred and twenty. The whole decision runs inside one
 * Lua evaluation so that reading the bucket, refilling it and spending from it cannot interleave
 * with another request doing the same.
 *
 * <p><b>It fails open.</b> If Redis is unreachable the request is allowed, with a warning. That
 * is a deliberate trade: a rate limiter that fails closed converts a cache outage into a total
 * outage, and the thing being protected against — one caller making too many requests — is
 * survivable in a way that refusing every request is not. The warning matters, because a limiter
 * that has silently stopped limiting looks exactly like one with nothing to do.
 */
@Component
public class RateLimiter {

	/** The verdict, with enough to fill in the response headers. */
	public record Decision(boolean allowed, long remaining, long retryAfterMillis, long limit) {
	}

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final StringRedisTemplate redis;

	private final RedisScript<List> script;

	public RateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
		DefaultRedisScript<List> loaded = new DefaultRedisScript<>();
		loaded.setScriptSource(new ResourceScriptSource(new ClassPathResource("rate-limit.lua")));
		loaded.setResultType(List.class);
		this.script = loaded;
	}

	/**
	 * Spends one request against {@code caller}'s bucket.
	 *
	 * @param perMinute sustained rate; the bucket also holds a minute's worth, so a caller idle
	 * for a while may burst up to that before being held to the rate. Bursts are normal — a page
	 * that loads six panels makes six requests — and a limiter that forbids them is one that
	 * breaks the UI it is protecting.
	 */
	public Decision check(String caller, int perMinute) {
		if (perMinute <= 0) {
			return new Decision(true, Long.MAX_VALUE, 0, perMinute);
		}
		double refillPerSecond = perMinute / 60.0;
		try {
			List<?> result = this.redis.execute(this.script, List.of("ossian:rl:" + caller),
					String.valueOf(perMinute), String.valueOf(refillPerSecond),
					String.valueOf(System.currentTimeMillis()), "1");
			if (result == null || result.size() < 3) {
				return new Decision(true, perMinute, 0, perMinute);
			}
			long allowed = ((Number) result.get(0)).longValue();
			long remaining = ((Number) result.get(1)).longValue();
			long retryAfter = ((Number) result.get(2)).longValue();
			return new Decision(allowed == 1, remaining, retryAfter, perMinute);
		}
		catch (RuntimeException ex) {
			log.warn("rate limiting is not working; requests are being allowed unchecked: {}", ex.toString());
			return new Decision(true, perMinute, 0, perMinute);
		}
	}

}
