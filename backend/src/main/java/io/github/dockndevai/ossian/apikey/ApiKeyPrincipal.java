package io.github.dockndevai.ossian.apikey;

import java.util.UUID;

/**
 * Who a request is, when it arrived with a key rather than a user token.
 *
 * <p>Carries what the rest of the application needs from an identity — who to name in an audit
 * trail, and any namespace this credential is confined to — so everything downstream can stay
 * indifferent to how the caller authenticated.
 *
 * @param namespace optional confinement; when set, this key may only touch that namespace
 */
public record ApiKeyPrincipal(UUID keyId, String name, String namespace) {

	/** What appears as the actor on anything this key creates. */
	public String actor() {
		return "key:" + this.name;
	}

}
