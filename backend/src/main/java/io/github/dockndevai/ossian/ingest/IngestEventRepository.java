package io.github.dockndevai.ossian.ingest;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestEventRepository extends JpaRepository<IngestEvent, UUID> {

	Optional<IngestEvent> findByEventId(String eventId);

	Page<IngestEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);

}
