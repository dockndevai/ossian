package io.github.dockndevai.ossian.memory;

import java.util.Optional;

import io.github.dockndevai.ossian.caller.CallerContext;
import io.github.dockndevai.ossian.config.OssianProperties;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Decides which agent's memory a request may touch.
 *
 * <p>The memory routes take an {@code agentId} from the caller. That is a convenience for a
 * person in the console, who is looking at every agent, and a hole for a machine: a key issued to
 * one agent could otherwise name any other and read, rewrite or erase what it knows. So the
 * agent is not the caller's to choose when the credential says otherwise.
 *
 * <ul>
 * <li><b>Bound key</b> (it names an agent): every route is forced to that agent. Omitting the
 * agentId means "mine"; naming another is a 403, not a quiet redirect, for the same reason
 * namespace confinement refuses: a client writing to the wrong place and being told nothing
 * looks like it worked. This applies whatever roles the key holds.</li>
 * <li><b>Unbound key with the admin role</b>: unrestricted, as before. It can already read the
 * whole installation through {@code /api/admin}.</li>
 * <li><b>Unbound key with no namespace confinement</b>: unrestricted, as before. Nobody has told
 * the system this key is meant to be narrow.</li>
 * <li><b>Unbound key confined to a namespace</b>: refused, unless
 * {@code ossian.memory.allow-unbound-confined-keys} is set. Someone confined it deliberately, and
 * unrestricted memory access is exactly what that confinement was supposed to rule out. The
 * remedy is to bind it.</li>
 * <li><b>A person</b> (Keycloak token): unrestricted, as before.</li>
 * </ul>
 */
@Component
public class MemoryAccess {

	private final CallerContext caller;

	private final OssianProperties properties;

	public MemoryAccess(CallerContext caller, OssianProperties properties) {
		this.caller = caller;
		this.properties = properties;
	}

	/**
	 * The agent a request acts on.
	 *
	 * @param requested the agentId the request named, possibly null or blank
	 * @throws MemoryAccessException 403 if the caller may not use memory, or is bound to a
	 * different agent; 400 if the caller must name an agent and did not
	 */
	public String agentFor(String requested) {
		Optional<String> bound = restriction();
		boolean named = requested != null && !requested.isBlank();
		if (bound.isPresent()) {
			if (named && !requested.equals(bound.get())) {
				throw new MemoryAccessException(HttpStatus.FORBIDDEN, "This API key is bound to agent '"
						+ bound.get() + "' and cannot access the memory of agent '" + requested + "'");
			}
			return bound.get();
		}
		if (!named) {
			throw new MemoryAccessException(HttpStatus.BAD_REQUEST, "agentId is required");
		}
		return requested;
	}

	/**
	 * The one agent this caller is limited to, or empty if it may reach any.
	 *
	 * <p>For the routes that are not about a named agent — listing which agents exist, deleting by
	 * id — where the answer must be narrowed rather than checked.
	 *
	 * @throws MemoryAccessException 403 if the caller may not use memory at all
	 */
	public Optional<String> restriction() {
		Optional<String> bound = this.caller.boundAgent();
		if (bound.isPresent()) {
			return bound;
		}
		if (this.caller.isMachine() && !this.caller.isAdmin()
				&& !this.properties.getMemory().isAllowUnboundConfinedKeys()) {
			this.caller.confinedNamespace().ifPresent(namespace -> {
				throw new MemoryAccessException(HttpStatus.FORBIDDEN, "This API key is confined to the '"
						+ namespace + "' namespace but is not bound to an agent, so it cannot use agent memory. "
						+ "Bind the key to an agent (PUT /api/admin/api-keys/{id}/agent) or issue it with an "
						+ "agentId.");
			});
		}
		return Optional.empty();
	}

}
