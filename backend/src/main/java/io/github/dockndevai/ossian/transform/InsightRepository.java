package io.github.dockndevai.ossian.transform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InsightRepository extends JpaRepository<Insight, UUID> {

	List<Insight> findByDocumentIdOrderByCreatedAtDesc(UUID documentId);

	List<Insight> findTop100ByOrderByCreatedAtDesc();

	Optional<Insight> findById(UUID id);

	/**
	 * The most recent output produced from exactly these inputs.
	 *
	 * <p>This is the durable half of the cache. Redis expires and can be flushed; the run itself
	 * is a row that does not, so an identical request stays cheap indefinitely rather than only
	 * until the next restart.
	 */
	Optional<Insight> findFirstByCacheKeyOrderByCreatedAtDesc(String cacheKey);

}
