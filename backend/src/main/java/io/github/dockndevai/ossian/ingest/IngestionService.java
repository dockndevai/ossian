package io.github.dockndevai.ossian.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.dockndevai.ossian.config.OssianProperties;
import io.github.dockndevai.ossian.document.DocumentEntity;
import io.github.dockndevai.ossian.document.DocumentRepository;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an uploaded file into answerable chunks.
 * <p>
 * The pipeline is parse (Tika) → split → embed → store, and every chunk carries
 * {@code tenant_id} and {@code document_id} metadata. Those two fields are what make deletion
 * and tenant isolation possible at all: the vector store has no foreign keys, so without them
 * a deleted document's chunks would linger and keep answering questions.
 */
@Service
public class IngestionService {

	private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

	/** Metadata keys. Must match what {@link io.github.dockndevai.ossian.chat.RagService} filters on. */
	public static final String META_TENANT = "tenant_id";

	public static final String META_DOCUMENT = "document_id";

	public static final String META_FILENAME = "filename";

	public static final String META_CHUNK_INDEX = "chunk_index";

	private final DocumentRepository documents;

	private final IngestionJobRepository jobs;

	private final VectorStore vectorStore;

	private final OssianProperties properties;

	public IngestionService(DocumentRepository documents, IngestionJobRepository jobs, VectorStore vectorStore,
			OssianProperties properties) {
		this.documents = documents;
		this.jobs = jobs;
		this.vectorStore = vectorStore;
		this.properties = properties;
	}

	/** SHA-256 of the raw bytes, so the same file uploaded twice is recognised rather than duplicated. */
	public static String hash(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	/**
	 * Runs the pipeline off the request thread. Embedding a large PDF takes tens of seconds; the
	 * upload call returns as soon as the row exists so the UI can show progress instead of
	 * holding a connection open.
	 */
	@Async
	public void ingestAsync(UUID documentId, byte[] content, String filename, UUID jobId) {
		IngestionJob job = this.jobs.findById(jobId).orElseThrow();
		job.start();
		this.jobs.save(job);

		try {
			int written = ingest(documentId, content, filename);
			job.succeed(written);
			this.jobs.save(job);
		}
		catch (Exception ex) {
			log.error("Ingestion failed for document {}", documentId, ex);
			job.fail(ex.getMessage());
			this.jobs.save(job);
			this.documents.findById(documentId).ifPresent(doc -> {
				doc.setStatus(DocumentEntity.Status.FAILED);
				doc.setErrorMessage(ex.getMessage());
				this.documents.save(doc);
			});
		}
	}

	@Transactional
	public int ingest(UUID documentId, byte[] content, String filename) {
		DocumentEntity entity = this.documents.findById(documentId).orElseThrow();
		entity.setStatus(DocumentEntity.Status.PROCESSING);
		this.documents.save(entity);

		// Tika sniffs the format from the bytes, so PDF/DOCX/HTML/TXT all work without branching.
		TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		List<Document> parsed = reader.get();

		OssianProperties.Ingest cfg = this.properties.getIngest();
		TokenTextSplitter splitter = TokenTextSplitter.builder()
			.withChunkSize(cfg.getChunkSize())
			.withMinChunkSizeChars(Math.max(50, cfg.getChunkOverlap()))
			.build();
		List<Document> chunks = splitter.apply(parsed);

		for (int i = 0; i < chunks.size(); i++) {
			Map<String, Object> meta = chunks.get(i).getMetadata();
			meta.put(META_TENANT, entity.getTenantId());
			meta.put(META_DOCUMENT, entity.getId().toString());
			meta.put(META_FILENAME, entity.getFilename());
			meta.put(META_CHUNK_INDEX, i);
		}

		// Batched so a large document does not become one enormous embedding request that the
		// upstream rejects or times out on.
		int batch = Math.max(1, cfg.getEmbeddingBatchSize());
		for (int i = 0; i < chunks.size(); i += batch) {
			this.vectorStore.add(chunks.subList(i, Math.min(chunks.size(), i + batch)));
		}

		entity.setChunkCount(chunks.size());
		entity.setStatus(DocumentEntity.Status.READY);
		entity.setErrorMessage(null);
		if (entity.getTitle() == null) {
			entity.setTitle(deriveTitle(parsed, filename));
		}
		this.documents.save(entity);
		log.info("Ingested {} ({} chunks) for tenant {}", filename, chunks.size(), entity.getTenantId());
		return chunks.size();
	}

	/** Removes a document's chunks from the vector store, then the row. */
	@Transactional
	public void deleteDocument(DocumentEntity entity) {
		this.vectorStore.delete("%s == '%s'".formatted(META_DOCUMENT, entity.getId()));
		this.documents.delete(entity);
		log.info("Deleted document {} and its chunks for tenant {}", entity.getId(), entity.getTenantId());
	}

	/**
	 * Drops the existing chunks and rebuilds them. This is the operation that makes a chunking
	 * change safe to try: without it, altering chunk size would leave the old chunks in place and
	 * silently mix two strategies in one corpus.
	 */
	@Transactional
	public int reindex(DocumentEntity entity, byte[] content) {
		this.vectorStore.delete("%s == '%s'".formatted(META_DOCUMENT, entity.getId()));
		return ingest(entity.getId(), content, entity.getFilename());
	}

	private String deriveTitle(List<Document> parsed, String filename) {
		if (!parsed.isEmpty()) {
			String text = parsed.get(0).getText();
			if (text != null && !text.isBlank()) {
				String firstLine = text.strip().lines().findFirst().orElse("").strip();
				if (firstLine.length() >= 8 && firstLine.length() <= 200) {
					return firstLine;
				}
			}
		}
		return filename;
	}

	/** Bytes back to text, for the reindex path where the original upload was retained. */
	public static String asText(byte[] content) {
		return new String(content, StandardCharsets.UTF_8);
	}

}
