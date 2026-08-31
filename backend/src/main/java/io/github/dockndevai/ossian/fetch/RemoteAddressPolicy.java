package io.github.dockndevai.ossian.fetch;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * Decides whether the server is allowed to open a connection to an address.
 *
 * <p>Fetching a URL somebody typed makes this service an HTTP client under their control, which
 * is the whole of server-side request forgery. The dangerous targets are not on the internet:
 * they are the loopback interface, the private ranges the container shares with its neighbours,
 * and the cloud metadata endpoint at 169.254.169.254, which hands out credentials to anything
 * that asks from inside the instance.
 *
 * <p>The check is on the resolved address, not the hostname. A name is not evidence of anything:
 * a public hostname can have an A record pointing at 127.0.0.1, and plenty do.
 */
public final class RemoteAddressPolicy {

	private final boolean allowPrivate;

	public RemoteAddressPolicy(boolean allowPrivate) {
		this.allowPrivate = allowPrivate;
	}

	/** @return null when the address is acceptable, otherwise why it is not */
	public String rejectionReason(InetAddress address) {
		if (this.allowPrivate) {
			return null;
		}
		if (address.isAnyLocalAddress()) {
			return "an unspecified address";
		}
		if (address.isLoopbackAddress()) {
			return "a loopback address";
		}
		if (address.isLinkLocalAddress()) {
			// Covers 169.254.0.0/16, and therefore the cloud metadata endpoint.
			return "a link-local address";
		}
		if (address.isSiteLocalAddress()) {
			return "a private address";
		}
		if (address.isMulticastAddress()) {
			return "a multicast address";
		}
		if (address instanceof Inet4Address v4) {
			byte[] b = v4.getAddress();
			int first = b[0] & 0xff;
			int second = b[1] & 0xff;
			// Carrier-grade NAT: routable-looking, and reachable inside many hosting networks.
			if (first == 100 && second >= 64 && second <= 127) {
				return "a carrier-grade NAT address";
			}
			// 0.0.0.0/8 and 240.0.0.0/4 are not usable destinations.
			if (first == 0 || first >= 240) {
				return "a reserved address";
			}
		}
		if (address instanceof Inet6Address v6) {
			byte[] b = v6.getAddress();
			// fc00::/7 — unique local. Java reports these as neither site-local nor link-local.
			if ((b[0] & 0xfe) == 0xfc) {
				return "a unique-local address";
			}
			// An IPv4-mapped address hides a v4 destination inside a v6 one; judge the v4.
			if (v6.isIPv4CompatibleAddress()) {
				return "an IPv4-compatible IPv6 address";
			}
		}
		return null;
	}

	public boolean isAllowed(InetAddress address) {
		return rejectionReason(address) == null;
	}

}
