package io.github.dockndevai.ossian.settings;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<Setting, String> {

	Optional<Setting> findByKey(String key);

	void deleteByKey(String key);

}
