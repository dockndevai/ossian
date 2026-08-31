package io.github.dockndevai.ossian.namespace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NamespaceRepository extends JpaRepository<NamespaceEntity, UUID> {

	List<NamespaceEntity> findByTenantIdOrderByName(String tenantId);

	Optional<NamespaceEntity> findByTenantIdAndName(String tenantId, String name);

	boolean existsByTenantIdAndName(String tenantId, String name);

}
