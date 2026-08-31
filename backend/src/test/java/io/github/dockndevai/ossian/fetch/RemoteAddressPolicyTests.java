package io.github.dockndevai.ossian.fetch;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The addresses this server must refuse to fetch.
 *
 * <p>Worth testing directly rather than through the controller, because the consequence of a gap
 * is not a failed request — it is a successful one. An SSRF hole returns 200 and a body, and the
 * body is whatever was on the internal network.
 */
class RemoteAddressPolicyTests {

	private final RemoteAddressPolicy policy = new RemoteAddressPolicy(false);

	private static InetAddress at(String literal) throws UnknownHostException {
		// Literals only: this must not perform a DNS lookup during a unit test.
		return InetAddress.getByName(literal);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"127.0.0.1",        // loopback
			"127.1.2.3",        // the rest of 127/8, which people forget
			"0.0.0.0",          // unspecified
			"10.0.0.5",         // private
			"172.16.0.9",       // private
			"192.168.1.1",      // private
			"169.254.169.254",  // cloud metadata — the one that hands out credentials
			"169.254.0.1",      // link-local generally
			"100.64.0.1",       // carrier-grade NAT
			"224.0.0.1",        // multicast
			"240.0.0.1",        // reserved
			"::1",              // IPv6 loopback
			"fc00::1",          // IPv6 unique-local
			"fd12:3456::1",     // IPv6 unique-local, the fd half of fc00::/7
			"fe80::1",          // IPv6 link-local
	})
	@DisplayName("refuses addresses that are only reachable from inside")
	void refusesInternalAddresses(String literal) throws Exception {
		InetAddress address = at(literal);
		assertThat(this.policy.isAllowed(address))
			.as("%s must be refused", literal)
			.isFalse();
		assertThat(this.policy.rejectionReason(address)).isNotBlank();
	}

	@ParameterizedTest
	@ValueSource(strings = { "1.1.1.1", "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111" })
	@DisplayName("allows ordinary public addresses")
	void allowsPublicAddresses(String literal) throws Exception {
		assertThat(this.policy.isAllowed(at(literal))).as("%s should be allowed", literal).isTrue();
	}

	@Test
	@DisplayName("the escape hatch allows everything, which is the point of it being off")
	void allowPrivateOptIn() throws Exception {
		RemoteAddressPolicy permissive = new RemoteAddressPolicy(true);
		assertThat(permissive.isAllowed(at("127.0.0.1"))).isTrue();
		assertThat(permissive.isAllowed(at("169.254.169.254"))).isTrue();
	}

	@Test
	@DisplayName("the reason names the category, so the caller learns what was wrong")
	void reasonsAreSpecific() throws Exception {
		assertThat(this.policy.rejectionReason(at("127.0.0.1"))).contains("loopback");
		assertThat(this.policy.rejectionReason(at("10.1.2.3"))).contains("private");
		assertThat(this.policy.rejectionReason(at("169.254.169.254"))).contains("link-local");
	}

}
