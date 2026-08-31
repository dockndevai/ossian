package io.github.dockndevai.ossian.transform;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransformationRepository extends JpaRepository<Transformation, UUID> {

	List<Transformation> findAllByOrderByPositionAscNameAsc();

	List<Transformation> findByApplyOnIngestTrueOrderByPositionAsc();

	Optional<Transformation> findBySlug(String slug);

	Optional<Transformation> findById(UUID id);

	boolean existsByIdNotNull();

}
