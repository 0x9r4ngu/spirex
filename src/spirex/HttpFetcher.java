package spirex;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Shared, thread-safe HTTP layer built on {@link java.net.http.HttpClient}.
 * Honours: -timeout, -retry, -proxy (http), -disable-redirects,
 * -max-response-size, and custom -H headers.
 */
public class HttpFetcher {

    // A real, current browser User-Agent so servers return the same page a
    // browser would see (many sites serve stripped or different HTML to
    // unrecognised agents). Overridable with -H "User-Agent: ...".
    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public record Response(int status, String contentType, String body,
                           URI finalUri, HttpHeaders headers) {
    }

    private final HttpClient client;
    private final Options opts;

    public HttpFetcher(Options opts) {
        this.opts = opts;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(opts.timeoutSeconds))
                .followRedirects(opts.disableRedirects
                        ? HttpClient.Redirect.NEVER : HttpClient.Redirect.NORMAL);
        if (opts.proxy != null && !opts.proxy.isBlank()) {
            if (opts.proxy.startsWith("socks")) {
                System.err.println("[!] -proxy: SOCKS proxies are not supported by the JDK HTTP "
                        + "client; ignoring. Use an HTTP proxy.");
            } else {
                String hostPort = opts.proxy.replaceFirst("^https?://", "");
                int idx = hostPort.lastIndexOf(':');
                if (idx > 0) {
                    String host = hostPort.substring(0, idx);
                    int port = Integer.parseInt(hostPort.substring(idx + 1));
                    b.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
                }
            }
        }
        this.client = b.build();
    }

    public Response fetch(URI uri) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt <= opts.retries; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(opts.timeoutSeconds))
                        .GET();
                boolean uaSet = false;
                for (Map.Entry<String, String> e : opts.headers.entrySet()) {
                    if (e.getKey().equalsIgnoreCase("user-agent")) {
                        uaSet = true;
                    }
                    try {
                        rb.header(e.getKey(), e.getValue());
                    } catch (IllegalArgumentException ignored) {
                        // restricted header (Host, Connection, ...) — skip
                    }
                }
                if (!uaSet) {
                    rb.header("User-Agent", DEFAULT_UA);
                }

                HttpResponse<String> resp =
                        client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                String body = resp.body();
                if (body != null && body.length() > opts.maxResponseSize) {
                    body = body.substring(0, opts.maxResponseSize);
                }
                String ct = resp.headers().firstValue("content-type").orElse("");
                return new Response(resp.statusCode(), ct, body, resp.uri(), resp.headers());
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new IllegalStateException("fetch failed: " + uri);
    }
}
