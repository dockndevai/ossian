package io.github.dockndevai.ossian.config;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration flag is a string an operator types into a config file or an environment variable,
 * so what is proved here is that the documented spelling is the one that binds, and that nothing
 * but an explicit true turns the default-deny off.
 */
class MemoryPropertiesTests {

	private static OssianProperties bind(Map<String, String> source) {
		return new Binder(new MapConfigurationPropertySource(source)).bind("ossian", OssianProperties.class)
			.orElseGet(OssianProperties::new);
	}

	@Test
	@DisplayName("unbound confined keys are denied unless configured otherwise")
	void defaultsToDeny() {
		assertThat(new OssianProperties().getMemory().isAllowUnboundConfinedKeys()).isFalse();
		assertThat(bind(Map.of()).getMemory().isAllowUnboundConfinedKeys()).isFalse();
	}

	@Test
	@DisplayName("ossian.memory.allow-unbound-confined-keys=true turns it on")
	void documentedSpellingBinds() {
		assertThat(bind(Map.of("ossian.memory.allow-unbound-confined-keys", "true")).getMemory()
			.isAllowUnboundConfinedKeys()).isTrue();
		assertThat(bind(Map.of("ossian.memory.allow-unbound-confined-keys", "false")).getMemory()
			.isAllowUnboundConfinedKeys()).isFalse();
	}

}
