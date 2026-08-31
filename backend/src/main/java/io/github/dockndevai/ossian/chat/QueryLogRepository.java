package io.github.dockndevai.ossian.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QueryLogRepository extends JpaRepository<QueryLog, UUID> {

	Page<QueryLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);

	/** The gap analysis: what people asked that the corpus could not answer. */
	List<QueryLog> findTop50ByTenantIdAndAnsweredFalseOrderByCreatedAtDesc(String tenantId);

	long countByTenantIdAndCreatedAtAfter(String tenantId, Instant since);

	long countByTenantIdAndAnsweredFalseAndCreatedAtAfter(String tenantId, Instant since);

	@Query("select avg(q.latencyMs) from QueryLog q where q.tenantId = ?1 and q.createdAt > ?2")
	Double avgLatency(String tenantId, Instant since);

	@Query("select avg(q.topScore) from QueryLog q where q.tenantId = ?1 and q.createdAt > ?2 and q.topScore is not null")
	Double avgTopScore(String tenantId, Instant since);

}
