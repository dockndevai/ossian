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

	/*
	 * Namespace-scoped variants.
	 *
	 * A question asked without naming a namespace is logged with a null one, and it genuinely
	 * did search everything — so it belongs in the unscoped totals and in no single namespace's.
	 * These deliberately match on equality rather than treating null as a wildcard.
	 */

	long countByTenantIdAndNamespaceAndCreatedAtAfter(String tenantId, String namespace, Instant since);

	long countByTenantIdAndNamespaceAndAnsweredFalseAndCreatedAtAfter(String tenantId, String namespace,
			Instant since);

	List<QueryLog> findTop50ByTenantIdAndNamespaceAndAnsweredFalseOrderByCreatedAtDesc(String tenantId,
			String namespace);

	@Query("select avg(q.latencyMs) from QueryLog q "
			+ "where q.tenantId = ?1 and q.namespace = ?2 and q.createdAt > ?3")
	Double avgLatency(String tenantId, String namespace, Instant since);

	@Query("select avg(q.topScore) from QueryLog q "
			+ "where q.tenantId = ?1 and q.namespace = ?2 and q.createdAt > ?3 and q.topScore is not null")
	Double avgTopScore(String tenantId, String namespace, Instant since);

}
