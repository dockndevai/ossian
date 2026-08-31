package io.github.dockndevai.ossian.transform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransformationRepository extends JpaRepository<Transformation, UUID> {

	List<Transformation> findByTenantIdOrderByPositionAscNameAsc(String tenantId);

	List<Transformation> findByTenantIdAndApplyOnIngestTrueOrderByPositionAsc(String tenantId);

	Optional<Transformation> findByTenantIdAndSlug(String tenantId, String slug);

	Optional<Transformation> findByIdAndTenantId(UUID id, String tenantId);

	boolean existsByTenantId(String tenantId);

}
