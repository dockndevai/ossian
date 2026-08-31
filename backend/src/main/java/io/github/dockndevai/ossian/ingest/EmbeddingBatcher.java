package io.github.dockndevai.ossian.ingest;

import java.util.ArrayList;
import java.util.List;

import io.github.dockndevai.ossian.config.OssianProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.stereotype.Component;

/**
 * Groups chunks into embedding calls that fit.
 *
 * <p>The previous rule was a fixed count — twenty-five chunks per call, whatever they contained.
 * That is the wrong unit. Embedding endpoints reject on total tokens, so twenty-five short notes
 * and twenty-five long passages are the same number and wildly different requests: one wastes
 * most of the budget it could have used, the other is rejected outright. A batch of 400-token
 * chunks and a batch of 4,000-token chunks cannot both be twenty-five.
 *
 * <p>Tokens are counted with the real tokenizer rather than estimated from character count. The
 * usual heuristic — four characters to a token — is roughly right for English prose and badly
 * wrong for the things people actually ingest: code, tables, JSON and non-Latin scripts all run
 * far denser, so a limit set from that heuristic is exceeded exactly on the documents most likely
 * to be large.
 *
 * <p>The count cap is kept as a second bound. Some endpoints limit inputs per request regardless
 * of size, and a thousand one-token chunks would satisfy any token budget while breaking that.
 */
@Component
public class EmbeddingBatcher {

	private static final Logger log = LoggerFactory.getLogger(EmbeddingBatcher.class);

	private final OssianProperties properties;

	private final TokenCountEstimator tokens = new JTokkitTokenCountEstimator();

	public EmbeddingBatcher(OssianProperties properties) {
		this.properties = properties;
	}

	/**
	 * Splits chunks into batches bounded by both token count and item count.
	 *
	 * <p>A single chunk over the token budget still goes out alone rather than being dropped or
	 * split further: it is the splitter's business how large a chunk is, and silently discarding
	 * one here would lose text from the middle of a document with nothing to show for it.
	 */
	public List<List<Document>> batch(List<Document> chunks) {
		OssianProperties.Ingest cfg = this.properties.getIngest();
		int maxTokens = Math.max(1, cfg.getEmbeddingBatchTokens());
		int maxItems = Math.max(1, cfg.getEmbeddingBatchSize());

		List<List<Document>> batches = new ArrayList<>();
		List<Document> current = new ArrayList<>();
		int currentTokens = 0;

		for (Document chunk : chunks) {
			int cost = estimate(chunk);
			boolean wouldOverflow = !current.isEmpty()
					&& (currentTokens + cost > maxTokens || current.size() >= maxItems);
			if (wouldOverflow) {
				batches.add(current);
				current = new ArrayList<>();
				currentTokens = 0;
			}
			if (cost > maxTokens) {
				log.warn("chunk of {} tokens exceeds the {}-token batch budget; sending it alone", cost,
						maxTokens);
			}
			current.add(chunk);
			currentTokens += cost;
		}
		if (!current.isEmpty()) {
			batches.add(current);
		}
		return batches;
	}

	public int estimate(Document chunk) {
		String text = chunk.getText();
		return (text == null || text.isBlank()) ? 0 : this.tokens.estimate(text);
	}

	/** Total tokens a set of chunks will cost to embed, for budgeting before the call. */
	public int totalTokens(List<Document> chunks) {
		int total = 0;
		for (Document chunk : chunks) {
			total += estimate(chunk);
		}
		return total;
	}

}
