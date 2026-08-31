package io.github.dockndevai.ossian.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

import io.github.dockndevai.ossian.config.OssianProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Fetches a URL on the user's behalf, under constraints.
 *
 * <p>Three things make this different from any other HTTP call the service makes. The address is
 * chosen by whoever is signed in, so it is checked against {@link RemoteAddressPolicy} before a
 * socket is opened. Redirects are followed by hand and re-checked at every hop, because a public
 * URL that 302s to 169.254.169.254 defeats a check performed only on the original. And the body
 * is read against a hard cap rather than trusting {@code Content-Length}, which is a claim by the
 * server being fetched.
 */
@Component
public class UrlFetcher {

	/** What came back, and where from after redirects. */
	public record Fetched(byte[] content, String contentType, String finalUrl, String suggestedName) {
	}

	/** Refusals a caller should turn into a 4xx rather than a 500. */
	public static class NotAllowed extends RuntimeException {

		public NotAllowed(String message) {
			super(message);
		}

	}

	private static final Logger log = LoggerFactory.getLogger(UrlFetcher.class);

	private static final int MAX_REDIRECTS = 5;

	private final OssianProperties properties;

	private final HttpClient client;

	public UrlFetcher(OssianProperties properties) {
		this.properties = properties;
		this.client = HttpClient.newBuilder()
			// Never NORMAL or ALWAYS: the client would follow a redirect to a private address
			// without the policy ever seeing it.
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(properties.getFetch().getConnectTimeoutSeconds()))
			.build();
	}

	public Fetched fetch(String rawUrl) {
		OssianProperties.Fetch cfg = this.properties.getFetch();
		RemoteAddressPolicy policy = new RemoteAddressPolicy(cfg.isAllowPrivateAddresses());

		URI uri = parse(rawUrl);
		for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
			check(uri, policy);
			HttpResponse<InputStream> response = send(uri, cfg);
			int status = response.statusCode();

			if (status >= 300 && status < 400) {
				String location = response.headers().firstValue("location").orElse(null);
				if (location == null || location.isBlank()) {
					throw new NotAllowed("The server redirected without saying where to");
				}
				// Resolve against the current URI so a relative Location works, then check the
				// new destination on the next pass round the loop.
				uri = uri.resolve(location);
				continue;
			}
			if (status != 200) {
				throw new NotAllowed("The server returned HTTP " + status);
			}

			byte[] body = read(response, cfg.getMaxBytes());
			String contentType = response.headers().firstValue("content-type").orElse(null);
			return new Fetched(body, contentType, uri.toString(), nameFor(uri, contentType));
		}
		throw new NotAllowed("Too many redirects");
	}

	private URI parse(String rawUrl) {
		URI uri;
		try {
			uri = new URI(rawUrl.trim());
		}
		catch (URISyntaxException ex) {
			throw new NotAllowed("That is not a valid URL");
		}
		String scheme = (uri.getScheme() == null) ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			// file:, gopher:, jar: and friends are how an SSRF check gets walked around.
			throw new NotAllowed("Only http and https URLs can be fetched");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new NotAllowed("That URL has no host");
		}
		return uri;
	}

	private void check(URI uri, RemoteAddressPolicy policy) {
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
		}
		catch (UnknownHostException ex) {
			throw new NotAllowed("That host could not be resolved");
		}
		// Every address, not the first: a host with both a public and a loopback record would
		// otherwise pass the check and then connect to whichever the stack picked.
		for (InetAddress address : addresses) {
			String reason = policy.rejectionReason(address);
			if (reason != null) {
				log.warn("refusing to fetch {} — {} resolves to {}", uri.getHost(), uri.getHost(), reason);
				throw new NotAllowed("That URL resolves to " + reason + ", which this server will not fetch");
			}
		}
	}

	private HttpResponse<InputStream> send(URI uri, OssianProperties.Fetch cfg) {
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(cfg.getReadTimeoutSeconds()))
			.header("User-Agent", cfg.getUserAgent())
			.header("Accept", "text/html,application/xhtml+xml,application/pdf,text/plain;q=0.9,*/*;q=0.8")
			.GET()
			.build();
		try {
			return this.client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (IOException ex) {
			throw new NotAllowed("That URL could not be fetched: " + ex.getMessage());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new NotAllowed("The fetch was interrupted");
		}
	}

	/** Reads at most {@code max} bytes, and fails rather than silently truncating past it. */
	private byte[] read(HttpResponse<InputStream> response, long max) {
		try (InputStream in = response.body()) {
			byte[] body = in.readNBytes((int) Math.min(max, Integer.MAX_VALUE));
			// One more byte: if it arrives, the document was larger than the cap and what was
			// read is a fragment. Ingesting a fragment is worse than refusing, because a partial
			// document looks exactly like a complete one once it is chunked.
			if (in.read() != -1) {
				throw new NotAllowed("That page is larger than the " + max + " byte limit");
			}
			return body;
		}
		catch (IOException ex) {
			throw new NotAllowed("That URL could not be read: " + ex.getMessage());
		}
	}

	/** A filename for the document list, derived from the path or the content type. */
	static String nameFor(URI uri, String contentType) {
		String path = uri.getPath();
		if (path != null && !path.isBlank() && !path.endsWith("/")) {
			String last = path.substring(path.lastIndexOf('/') + 1);
			if (!last.isBlank()) {
				return last.length() > 180 ? last.substring(0, 180) : last;
			}
		}
		String host = uri.getHost();
		String extension = (contentType != null && contentType.contains("pdf")) ? ".pdf" : ".html";
		return host + extension;
	}

}
