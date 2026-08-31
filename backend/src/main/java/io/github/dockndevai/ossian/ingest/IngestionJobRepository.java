package io.github.dockndevai.ossian.ingest;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

	Page<IngestionJob> findAllByOrderByCreatedAtDesc(Pageable pageable);

	long countByStatus(IngestionJob.Status status);

}
