package io.github.dockndevai.ossian.chat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.dockndevai.ossian.ingest.IngestionService;

import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How retrieved chunks become numbered sources.
 *
 * <p>Numbered per chunk, one document retrieved three times reads as three sources agreeing — to
 * the model and to the person checking the answer. Nothing errors when that happens; the answer
 * just looks better supported than it is.
 */
class SourceGroupingTests {

	private static Document chunk(String id, String documentId, String filename, String text, double score) {
		return Document.builder()
			.id(id)
			.text(text)
			.metadata(Map.of(IngestionService.META_DOCUMENT, documentId, IngestionService.META_FILENAME, filename))
			.score(score)
			.build();
	}

	@Test
	@DisplayName("chunks of one document share one citation number")
	void chunksOfOneDocumentAreOneSource() {
		List<Document> hits = List.of(
				chunk("c1", "doc-a", "handbook.md", "Leave is 25 days.", 0.91),
				chunk("c2", "doc-a", "handbook.md", "Leave carries over.", 0.84),
				chunk("c3", "doc-b", "policy.md", "Leave needs approval.", 0.80),
				chunk("c4", "doc-a", "handbook.md", "Leave is booked in HR.", 0.72));

		List<Dtos.Citation> citations = RagService.toCitations(hits);

		assertThat(citations).extracting(Dtos.Citation::filename).containsExactly("handbook.md", "policy.md");
		assertThat(citations).extracting(Dtos.Citation::index).containsExactly(1, 2);
		// Every passage is kept, not just the best one.
		assertThat(citations.get(0).excerpt())
			.contains("Leave is 25 days.", "Leave carries over.", "Leave is booked in HR.");
	}

	@Test
	@DisplayName("the prompt numbers documents, not chunks")
	void promptNumbersDocuments() {
		List<Document> hits = List.of(
				chunk("c1", "doc-a", "handbook.md", "Leave is 25 days.", 0.91),
				chunk("c2", "doc-a", "handbook.md", "Leave carries over.", 0.84),
				chunk("c3", "doc-b", "policy.md", "Leave needs approval.", 0.80));

		String message = RagService.buildUserMessage("how much leave?", hits);

		assertThat(message).contains("[1] handbook.md", "[2] policy.md").doesNotContain("[3]");
		// Both handbook passages sit under [1], before the policy block begins.
		assertThat(message.indexOf("Leave carries over.")).isLessThan(message.indexOf("[2] policy.md"));
		assertThat(message).endsWith("QUESTION: how much leave?");
	}

	@Test
	@DisplayName("a document ranks by its best chunk, whatever order the store returns them in")
	void documentRanksByBestChunk() {
		List<Document> hits = List.of(
				chunk("c1", "doc-b", "policy.md", "weaker", 0.60),
				chunk("c2", "doc-a", "handbook.md", "weak", 0.55),
				chunk("c3", "doc-a", "handbook.md", "strongest", 0.95));

		List<Dtos.Citation> citations = RagService.toCitations(hits);

		assertThat(citations).extracting(Dtos.Citation::filename).containsExactly("handbook.md", "policy.md");
		assertThat(citations.get(0).score()).isEqualTo(0.95);
	}

	@Test
	@DisplayName("two documents with the same filename stay separate")
	void sameFilenameDifferentDocument() {
		List<Document> hits = List.of(
				chunk("c1", "doc-a", "README.md", "one", 0.9),
				chunk("c2", "doc-b", "README.md", "two", 0.8));

		// Grouping is on document id: two files that share a name are still two sources.
		assertThat(RagService.toCitations(hits)).extracting(Dtos.Citation::documentId)
			.containsExactly("doc-a", "doc-b");
	}

}
