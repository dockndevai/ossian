package io.github.dockndevai.ossian.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * A bounded pool for ingestion.
 *
 * <p>Without this, {@code @Async} uses Boot's default executor: eight core threads and an
 * effectively unbounded queue. Uploading two hundred documents then queues two hundred
 * embeddings, and the pool cheerfully accepts every one — the failure shows up somewhere else, as
 * connection-pool exhaustion or as the model gateway shedding load, which is a long way from the
 * cause.
 *
 * <p>Small on purpose. Ingestion is bound by the embedding endpoint, not by local CPU: running
 * thirty documents at once does not make the model answer faster, it just spreads the same
 * throughput over thirty half-finished documents instead of finishing three.
 */
@Configuration
public class IngestExecutorConfig {

	private static final Logger log = LoggerFactory.getLogger(IngestExecutorConfig.class);

	@Bean("applicationTaskExecutor")
	Executor applicationTaskExecutor(OssianProperties properties) {
		int concurrency = Math.max(1, properties.getIngest().getConcurrency());
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(concurrency);
		executor.setMaxPoolSize(concurrency);
		// Bounded, so saturation is visible here rather than downstream.
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("ingest-");
		// Runs the work on the submitting thread once the queue is full. The upload call then
		// blocks instead of the queue growing without limit — backpressure the client can feel,
		// which is the honest signal that the system is behind.
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		log.info("ingestion pool: {} concurrent, queue 500", concurrency);
		return executor;
	}

}
