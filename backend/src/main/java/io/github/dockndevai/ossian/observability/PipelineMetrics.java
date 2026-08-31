package io.github.dockndevai.ossian.observability;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

/**
 * The numbers worth watching, in one place.
 *
 * <p>Centralised rather than scattered as {@code @Timed} annotations because the names and tags
 * are a contract: a dashboard or an alert is written against them, and a meter renamed in passing
 * breaks a graph silently — it goes flat rather than red, which is the worst way for monitoring
 * to fail.
 *
 * <p>Tag cardinality is deliberately low. Namespace is a tag because a corpus has a handful;
 * document id and filename are not, because a tag with unbounded values turns a time-series
 * database into a very slow log.
 */
@Component
public class PipelineMetrics {

	private final MeterRegistry registry;

	public PipelineMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	/** One document through ingestion, with how long it took and how it ended. */
	public void ingestCompleted(String namespace, boolean succeeded, int chunks, Duration took) {
		Timer.builder("ossian.ingest.duration")
			.description("Time to parse, split, embed and store one document")
			.tag("namespace", safe(namespace))
			.tag("outcome", succeeded ? "success" : "failure")
			.register(this.registry)
			.record(took);

		if (succeeded) {
			// Chunks rather than documents, because that is what the embedding bill is charged
			// in and what makes one 400-page PDF visibly different from four hundred notes.
			Counter.builder("ossian.ingest.chunks")
				.description("Chunks written to the vector store")
				.tag("namespace", safe(namespace))
				.register(this.registry)
				.increment(chunks);
		}
	}

	/** A question, and whether the corpus could support an answer. */
	public void retrieval(String namespace, int hits, boolean grounded, Duration took) {
		Timer.builder("ossian.retrieval.duration")
			.description("Time to embed a question and return its passages")
			.tag("namespace", safe(namespace))
			.tag("grounded", Boolean.toString(grounded))
			.register(this.registry)
			.record(took);

		// The rate of ungrounded questions is the health signal for a corpus: rising, it means
		// people are asking things the documents do not cover.
		Counter.builder("ossian.retrieval.chunks")
			.description("Passages returned to ground an answer")
			.tag("namespace", safe(namespace))
			.register(this.registry)
			.increment(hits);
	}

	/** A transformation, and whether it was recomputed or served from an earlier identical run. */
	public void transformation(String slug, boolean fromCache, Duration took) {
		Timer.builder("ossian.transform.duration")
			.description("Time to produce a transformation output")
			.tag("transformation", safe(slug))
			.tag("cached", Boolean.toString(fromCache))
			.register(this.registry)
			.record(took);
	}

	/** Wraps a call, recording how long it took whichever way it ends. */
	public <T> T time(String name, java.util.function.Supplier<T> work) {
		long started = System.nanoTime();
		try {
			return work.get();
		}
		finally {
			this.registry.timer(name).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
		}
	}

	/** A null namespace would become the literal tag "null", which reads as a real one. */
	private static String safe(String value) {
		return (value == null || value.isBlank()) ? "all" : value;
	}

}
