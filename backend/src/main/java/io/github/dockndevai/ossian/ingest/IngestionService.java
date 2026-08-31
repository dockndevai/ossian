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
import io.github.dockndevai.ossian.settings.SettingsService;
import io.github.dockndevai.ossian.transform.TransformationService;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.reader.tika.TikaDocumentReader;
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

	/** Which slice of the tenant's corpus a chunk belongs to; the retriever filters on it. */
	public static final String META_NAMESPACE = "namespace";

	private final DocumentRepository documents;

	private final IngestionJobRepository jobs;

	private final VectorStore vectorStore;

	private final OssianProperties properties;

	private final SettingsService settings;

	/**
	 * Resolved lazily. Transformations read documents and content, and ingestion triggers
	 * transformations; asking for the bean at construction would be a cycle.
	 */
	private final ObjectProvider<TransformationService> transformations;

	public IngestionService(DocumentRepository documents, IngestionJobRepository jobs, VectorStore vectorStore,
			OssianProperties properties, SettingsService settings,
			ObjectProvider<TransformationService> transformations) {
		this.documents = documents;
		this.jobs = jobs;
		this.vectorStore = vectorStore;
		this.properties = properties;
		this.settings = settings;
		this.transformations = transformations;
	}

	/**
	 * Parses a file to plain text.
	 *
	 * <p>Shared with transformations, which need the whole document rather than the chunks the
	 * retriever would return. Tika sniffs the format from the bytes, so PDF, DOCX, HTML and text
	 * all work without the caller branching on type.
	 */
	public static String parseToText(byte[] content, String filename) {
		TikaDocumentReader reader = new TikaDocumentReader(new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		StringBuilder sb = new StringBuilder();
		for (Document parsed : reader.get()) {
			if (parsed.getText() != null && !parsed.getText().isBlank()) {
				if (!sb.isEmpty()) {
					sb.append("\n\n");
				}
				sb.append(parsed.getText());
			}
		}
		return sb.toString();
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
			runIngestTransformations(documentId);
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
		// Chunking is read from settings rather than straight from the file, so a tenant can tune
		// it without a redeploy. Changing it only affects documents indexed afterwards; existing
		// chunks stay as they were until reindexed, which is why the setting is flagged as
		// requiring a reindex in the UI.
		// The tenant comes from the entity, not from the security context: this runs on an async
		// thread where the context is not propagated.
		String tenantId = entity.getTenantId();
		ChunkSplitter splitter = new ChunkSplitter(
				this.settings.effectiveIntFor(tenantId, SettingsService.INGEST_CHUNK_SIZE),
				this.settings.effectiveIntFor(tenantId, SettingsService.INGEST_CHUNK_OVERLAP));
		List<Document> chunks = splitter.apply(parsed);

		for (int i = 0; i < chunks.size(); i++) {
			Map<String, Object> meta = chunks.get(i).getMetadata();
			meta.put(META_TENANT, entity.getTenantId());
			meta.put(META_DOCUMENT, entity.getId().toString());
			meta.put(META_FILENAME, entity.getFilename());
			meta.put(META_CHUNK_INDEX, i);
			meta.put(META_NAMESPACE, entity.getNamespace());
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

	/**
	 * Applies any transformation marked to run on ingest.
	 *
	 * <p>Deliberately after the job is recorded as succeeded. A transformation is a convenience
	 * on top of a document that is already indexed and answerable; if the model is down, the
	 * ingest still worked and should say so.
	 */
	private void runIngestTransformations(UUID documentId) {
		try {
			DocumentEntity entity = this.documents.findById(documentId).orElse(null);
			if (entity != null) {
				// The tenant is passed explicitly: this runs on an async thread where the
				// security context is not propagated.
				this.transformations.getObject().runOnIngest(entity, entity.getTenantId());
			}
		}
		catch (RuntimeException ex) {
			log.warn("On-ingest transformations failed for document {}: {}", documentId, ex.getMessage());
		}
	}

	/**
	 * Removes a document's chunks, leaving the row. Used when replacing a document's content:
	 * the old vectors have to go before the new ones arrive, or both answer questions and the
	 * stale text may score higher than the current text.
	 */
	@Transactional
	public void deleteChunks(DocumentEntity entity) {
		this.vectorStore.delete("%s == '%s'".formatted(META_DOCUMENT, entity.getId()));
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
