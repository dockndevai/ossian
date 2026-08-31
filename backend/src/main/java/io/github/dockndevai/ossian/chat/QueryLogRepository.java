package io.github.dockndevai.ossian.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface QueryLogRepository extends JpaRepository<QueryLog, UUID> {

	Page<QueryLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

	/** The gap analysis: what people asked that the corpus could not answer. */
	List<QueryLog> findTop50ByAnsweredFalseOrderByCreatedAtDesc();

	long countByCreatedAtAfter(Instant since);

	long countByAnsweredFalseAndCreatedAtAfter(Instant since);

	@Query("select avg(q.latencyMs) from QueryLog q where q.createdAt > ?1")
	Double avgLatency(Instant since);

	@Query("select avg(q.topScore) from QueryLog q where q.createdAt > ?1 and q.topScore is not null")
	Double avgTopScore(Instant since);

	/*
	 * Namespace-scoped variants.
	 *
	 * A question asked without naming a namespace is logged with a null one, and it genuinely
	 * did search everything — so it belongs in the unscoped totals and in no single namespace's.
	 * These deliberately match on equality rather than treating null as a wildcard.
	 */

	long countByNamespaceAndCreatedAtAfter(String namespace, Instant since);

	long countByNamespaceAndAnsweredFalseAndCreatedAtAfter(String namespace,
			Instant since);

	List<QueryLog> findTop50ByNamespaceAndAnsweredFalseOrderByCreatedAtDesc(String namespace);

	@Query("select avg(q.latencyMs) from QueryLog q "
			+ "where q.namespace = ?1 and q.createdAt > ?2")
	Double avgLatency(String namespace, Instant since);

	@Query("select avg(q.topScore) from QueryLog q "
			+ "where q.namespace = ?1 and q.createdAt > ?2 and q.topScore is not null")
	Double avgTopScore(String namespace, Instant since);

}
