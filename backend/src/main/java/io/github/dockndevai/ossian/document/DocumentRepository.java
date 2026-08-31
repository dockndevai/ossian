package io.github.dockndevai.ossian.document;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Every method is tenant-scoped by construction. There is deliberately no {@code findById(id)}
 * exposed to callers — an id alone must never be enough to read another tenant's document.
 */
public interface DocumentRepository extends JpaRepository<DocumentEntity, UUID> {

	Page<DocumentEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

	Optional<DocumentEntity> findById(UUID id);

	Optional<DocumentEntity> findByContentHash(String contentHash);

	/**
	 * Deduplication is per namespace, not per tenant: the same file may legitimately be filed in
	 * two namespaces, and treating that as a duplicate would make one of them silently empty.
	 */
	Optional<DocumentEntity> findByNamespaceAndContentHash(String namespace,
			String contentHash);

	/** The key an event-driven importer addresses a document by. */
	Optional<DocumentEntity> findByNamespaceAndExternalId(String namespace,
			String externalId);

	Page<DocumentEntity> findByNamespaceOrderByCreatedAtDesc(String namespace,
			Pageable pageable);

	long countByNamespace(String namespace);

	List<DocumentEntity> findByStatus(DocumentEntity.Status status);

	long count();

	@Query("select coalesce(sum(d.chunkCount), 0) from DocumentEntity d")
	long sumChunks();

	@Query("select coalesce(sum(d.sizeBytes), 0) from DocumentEntity d")
	long sumBytes();

	long countByStatus(DocumentEntity.Status status);

	long countByNamespaceAndStatus(String namespace, DocumentEntity.Status status);

	@Query("select coalesce(sum(d.chunkCount), 0) from DocumentEntity d "
			+ "where d.namespace = ?1")
	long sumChunksByNamespace(String namespace);

	@Query("select coalesce(sum(d.sizeBytes), 0) from DocumentEntity d "
			+ "where d.namespace = ?1")
	long sumBytesByNamespace(String namespace);

}
