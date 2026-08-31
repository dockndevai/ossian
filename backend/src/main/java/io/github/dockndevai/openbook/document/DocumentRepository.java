package io.github.dockndevai.openbook.document;

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

	List<DocumentEntity> findByTenantIdAndStatus(String tenantId, DocumentEntity.Status status);

	long countByTenantId(String tenantId);

	@Query("select coalesce(sum(d.chunkCount), 0) from DocumentEntity d where d.tenantId = ?1")
	long sumChunksByTenantId(String tenantId);

	@Query("select coalesce(sum(d.sizeBytes), 0) from DocumentEntity d where d.tenantId = ?1")
	long sumBytesByTenantId(String tenantId);

	long countByTenantIdAndStatus(String tenantId, DocumentEntity.Status status);

}
