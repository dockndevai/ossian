package io.github.dockndevai.ossian.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.dockndevai.ossian.ingest.IngestionService;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which passages survive the cut to top-k.
 *
 * <p>Retrieval ranks passages, so a document split into many chunks has many chances to fill the
 * slots. These assert on structure — how many passages each document got — rather than on any
 * answer, which is what lets them catch a skew that every individual answer would hide.
 */
class RetrievalSelectionTests {

	private static Document chunk(String documentId, double score) {
		return Document.builder()
			.text(documentId + " @ " + score)
			.metadata(Map.of(IngestionService.META_DOCUMENT, documentId, IngestionService.META_FILENAME, documentId))
			.score(score)
			.build();
	}

	private static Map<String, Long> perDocument(List<Document> selected) {
		return selected.stream()
			.collect(Collectors.groupingBy(d -> (String) d.getMetadata().get(IngestionService.META_DOCUMENT),
					Collectors.counting()));
	}

	/** A long runbook with many near-identical scores, and several short documents just below it. */
	private static List<Document> flatSpread() {
		List<Document> c = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			c.add(chunk("runbook", 0.70 - i * 0.002));
		}
		c.add(chunk("handbook", 0.66));
		c.add(chunk("policy", 0.65));
		c.add(chunk("faq", 0.64));
		c.add(chunk("onboarding", 0.63));
		return c;
	}

	@Test
	@DisplayName("when relevance is spread, one long document cannot take every slot")
	void flatSpreadIsCapped() {
		List<Document> selected = RagService.select(flatSpread(), 6, 2, 0.1);

		assertThat(selected).hasSize(6);
		assertThat(perDocument(selected)).containsEntry("runbook", 2L)
			.containsKeys("handbook", "policy", "faq", "onboarding");
	}

	@Test
	@DisplayName("without the cap, the same candidates are all the long document")
	void capOffReproducesTheSkew() {
		// The behaviour the cap exists to change, pinned so the test above is shown to matter.
		assertThat(perDocument(RagService.select(flatSpread(), 6, 0, 0.1))).containsOnlyKeys("runbook");
	}

	@Test
	@DisplayName("when one document clearly leads, it keeps the slots it earned")
	void dominantDocumentIsNotCapped() {
		List<Document> c = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			c.add(chunk("runbook", 0.86 - i * 0.01));
		}
		c.add(chunk("handbook", 0.62));
		c.add(chunk("policy", 0.60));

		// A 0.24 lead is the long-procedure case: capping it would drop half the steps.
		assertThat(perDocument(RagService.select(c, 6, 2, 0.1))).containsOnly(Map.entry("runbook", 6L));
	}

	@Test
	@DisplayName("the cap never sends fewer passages than there are slots to fill")
	void heldBackPassagesBackfill() {
		List<Document> c = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			c.add(chunk("runbook", 0.70 - i * 0.01));
		}
		c.add(chunk("handbook", 0.68));

		List<Document> selected = RagService.select(c, 6, 2, 0.1);

		assertThat(selected).hasSize(6);
		assertThat(selected).extracting(Document::getScore).isSortedAccordingTo((a, b) -> Double.compare(b, a));
	}

	@Test
	@DisplayName("a single document is never capped, and fewer candidates than slots is fine")
	void singleDocumentAndShortLists() {
		List<Document> one = List.of(chunk("runbook", 0.8), chunk("runbook", 0.7), chunk("runbook", 0.6));
		assertThat(RagService.select(one, 6, 2, 0.1)).hasSize(3);
		assertThat(RagService.select(List.of(), 6, 2, 0.1)).isEmpty();
	}

	@Test
	@DisplayName("selection does not depend on the order the store returned candidates in")
	void orderIndependent() {
		List<Document> shuffled = new ArrayList<>(flatSpread());
		java.util.Collections.reverse(shuffled);

		assertThat(RagService.select(shuffled, 6, 2, 0.1)).extracting(Document::getText)
			.containsExactlyElementsOf(RagService.select(flatSpread(), 6, 2, 0.1).stream().map(Document::getText).toList());
	}

}
