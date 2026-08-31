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

	Page<DocumentEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

	Optional<DocumentEntity> findByIdAndTenantId(UUID id, String tenantId);

	Optional<DocumentEntity> findByTenantIdAndContentHash(String tenantId, String contentHash);

	/**
	 * Deduplication is per namespace, not per tenant: the same file may legitimately be filed in
	 * two namespaces, and treating that as a duplicate would make one of them silently empty.
	 */
	Optional<DocumentEntity> findByTenantIdAndNamespaceAndContentHash(String tenantId, String namespace,
			String contentHash);

	/** The key an event-driven importer addresses a document by. */
	Optional<DocumentEntity> findByTenantIdAndNamespaceAndExternalId(String tenantId, String namespace,
			String externalId);

	Page<DocumentEntity> findByTenantIdAndNamespaceOrderByCreatedAtDesc(String tenantId, String namespace,
			Pageable pageable);

	long countByTenantIdAndNamespace(String tenantId, String namespace);

	List<DocumentEntity> findByTenantIdAndStatus(String tenantId, DocumentEntity.Status status);

	long countByTenantId(String tenantId);

	@Query("select coalesce(sum(d.chunkCount), 0) from DocumentEntity d where d.tenantId = ?1")
	long sumChunksByTenantId(String tenantId);

	@Query("select coalesce(sum(d.sizeBytes), 0) from DocumentEntity d where d.tenantId = ?1")
	long sumBytesByTenantId(String tenantId);

	long countByTenantIdAndStatus(String tenantId, DocumentEntity.Status status);

	long countByTenantIdAndNamespaceAndStatus(String tenantId, String namespace, DocumentEntity.Status status);

	@Query("select coalesce(sum(d.chunkCount), 0) from DocumentEntity d "
			+ "where d.tenantId = ?1 and d.namespace = ?2")
	long sumChunksByTenantIdAndNamespace(String tenantId, String namespace);

	@Query("select coalesce(sum(d.sizeBytes), 0) from DocumentEntity d "
			+ "where d.tenantId = ?1 and d.namespace = ?2")
	long sumBytesByTenantIdAndNamespace(String tenantId, String namespace);

}
