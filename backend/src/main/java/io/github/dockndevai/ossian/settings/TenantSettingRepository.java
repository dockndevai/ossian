package io.github.dockndevai.ossian.settings;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantSettingRepository extends JpaRepository<TenantSetting, TenantSetting.Key> {

	List<TenantSetting> findByTenantId(String tenantId);

	Optional<TenantSetting> findByTenantIdAndKey(String tenantId, String key);

	void deleteByTenantIdAndKey(String tenantId, String key);

}
