package io.github.dockndevai.ossian.ingest;

import java.util.concurrent.TimeUnit;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Holds ingestion to a rate the model behind the gateway can actually serve.
 *
 * <p>Separate from the HTTP rate limiter, and measured differently. That one counts requests,
 * because a caller making too many is the thing to stop. This one counts *tokens*, because one
 * upload is a single request and can be a million tokens of embedding — an ingestion that respects
 * a request limit perfectly can still exhaust a day's model budget in a minute.
 *
 * <p>It waits rather than refusing. Ingestion is already asynchronous and the caller has been
 * told the document is pending, so slowing down is invisible to them and correct; failing the job
 * because the system is busy would turn a queue into a pile of errors to retry by hand.
 *
 * <p>The wait is bounded. A job blocked indefinitely holds a thread and a database connection,
 * and a queue that never drains is harder to diagnose than one that reports why it stopped.
 */
@Component
public class IngestThrottle {

	private static final Logger log = LoggerFactory.getLogger(IngestThrottle.class);

	/** Shared bucket: the limit is on the model behind everything, not on any one caller. */
	private static final String BUCKET = "ingest-embedding-tokens";

	private final RateLimiter limiter;

	private final OssianProperties properties;

	public IngestThrottle(RateLimiter limiter, OssianProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	/**
	 * Blocks until this many embedding tokens may be spent.
	 *
	 * @return false if the budget did not free up within the maximum wait, in which case the
	 * caller should fail the job with a reason rather than proceeding
	 */
	public boolean acquire(int tokens) throws InterruptedException {
		OssianProperties.Ingest cfg = this.properties.getIngest();
		int perMinute = cfg.getEmbeddingTokensPerMinute();
		if (perMinute <= 0 || tokens <= 0) {
			return true;
		}

		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(cfg.getMaxThrottleWaitSeconds());
		int attempt = 0;
		while (true) {
			// The bucket is sized in tokens and spent in tokens, so a batch costs what it
			// actually costs rather than counting as one of anything.
			RateLimiter.Decision decision = this.limiter.checkCost(BUCKET, perMinute, tokens);
			if (decision.allowed()) {
				return true;
			}
			if (System.nanoTime() > deadline) {
				log.warn("ingestion gave up waiting for embedding budget after {}s",
						cfg.getMaxThrottleWaitSeconds());
				return false;
			}
			long waitMillis = Math.max(250, Math.min(decision.retryAfterMillis(), 5_000));
			if (attempt++ == 0) {
				log.info("ingestion is waiting {}ms for embedding budget ({} tokens/min)", waitMillis,
						perMinute);
			}
			Thread.sleep(waitMillis);
		}
	}

}
