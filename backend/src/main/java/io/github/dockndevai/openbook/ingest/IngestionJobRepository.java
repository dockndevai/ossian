package io.github.dockndevai.openbook.ingest;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

	Page<IngestionJob> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

	long countByTenantIdAndStatus(String tenantId, IngestionJob.Status status);

}
