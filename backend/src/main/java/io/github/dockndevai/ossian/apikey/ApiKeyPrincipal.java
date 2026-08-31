package io.github.dockndevai.ossian.apikey;

import java.util.UUID;

/**
 * Who a request is, when it arrived with a key rather than a user token.
 *
 * <p>Carries the same two things the tenant layer needs from a JWT — which tenant, and who to
 * name in an audit trail — so that everything downstream can stay indifferent to how the caller
 * authenticated.
 *
 * @param namespace optional confinement; when set, this key may only touch that namespace
 */
public record ApiKeyPrincipal(UUID keyId, String tenantId, String name, String namespace) {

	/** What appears as the actor on anything this key creates. */
	public String actor() {
		return "key:" + this.name;
	}

}
