package io.github.dockndevai.ossian.apikey;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

	Optional<ApiKeyEntity> findByKeyHash(String keyHash);

	List<ApiKeyEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

	Optional<ApiKeyEntity> findByIdAndTenantId(UUID id, String tenantId);

}
