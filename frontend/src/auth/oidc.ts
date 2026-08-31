import type { AuthProviderProps } from "react-oidc-context";
import { WebStorageStateStore } from "oidc-client-ts";

const url = import.meta.env.VITE_KEYCLOAK_URL ?? "http://localhost:8180";
const realm = import.meta.env.VITE_KEYCLOAK_REALM ?? "ossian";

/**
 * Authorization Code + PKCE against Keycloak. The client is public, so there is no secret in
 * the browser to leak; PKCE is what stops an intercepted code being exchanged by anyone else.
 *
 * Tokens live in sessionStorage rather than localStorage: closing the tab ends the session,
 * and a token is not left behind on a shared machine.
 */
export const oidcConfig: AuthProviderProps = {
  authority: `${url}/realms/${realm}`,
  client_id: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? "ossian-frontend",
  redirect_uri: window.location.origin,
  post_logout_redirect_uri: window.location.origin,
  response_type: "code",
  scope: "openid profile email",
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // Strip ?code=&state= from the address bar after the redirect so a refresh does not
  // attempt to redeem an already-used authorization code.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};
