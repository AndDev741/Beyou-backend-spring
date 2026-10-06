package beyou.beyouapp.backend.domain.notebook.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

/**
 * Fetches a web page (or a PDF behind a link) that somebody added as a source.
 *
 * <p>This is the one place the server requests a URL a user typed, so it is also the one place
 * that refuses to be used as a proxy into the network it runs in (SSRF):
 * <ul>
 *   <li>http and https only, on any port, with a host;</li>
 *   <li>every address the host resolves to must be public. Loopback, private ranges, link-local
 *       (which includes the cloud metadata address), carrier-grade NAT, multicast, the
 *       unspecified address and IPv6 unique-local are refused, and so is an IPv6 address that
 *       wraps one of them;</li>
 *   <li>redirects are not followed by the HTTP client. Each hop is checked by hand, at most
 *       {@link #MAX_REDIRECTS} of them, so a public page cannot bounce the request inward;</li>
 *   <li>the body is capped while it is read, not after.</li>
 * </ul>
 *
 * <p>What remains is DNS rebinding: a name that resolves publicly when checked and privately a
 * moment later when the client connects. Closing that needs the connection pinned to the checked
 * address, which java.net.http cannot do without dropping TLS hostname checks. The window is the
 * gap between two resolutions inside one request, the targets that matter (the management port,
 * metadata) answer nothing useful to a GET that has to come back as HTML or PDF, and the response
 * is never shown raw, only as extracted text.
 */
@Component
public class LinkFetcher {

    static final int MAX_REDIRECTS = 3;
    static final int MAX_HTML_BYTES = 3 * 1024 * 1024;
    public static final int MAX_PDF_BYTES = 15 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** What came back: either text extracted from HTML, or the bytes of a PDF to read. */
    public record Fetched(String title, String text, byte[] pdf) {
        public boolean isPdf() {
            return pdf != null;
        }
    }

    /**
     * Refuses a URL the server would not fetch, before anything is stored. Resolves the host, so
     * a name that points inside the network is caught here and the person sees why at once.
     */
    public URI validate(String url) {
        URI uri;
        try {
            uri = new URI(url == null ? "" : url.strip());
        } catch (URISyntaxException bad) {
            throw refused("Not a valid link");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw refused("Only http and https links can be added");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            throw refused("The link has no usable host");
        }
        assertPublic(uri.getHost());
        return uri;
    }

    public Fetched fetch(String url) {
        URI uri = validate(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                Optional<String> location = response.headers().firstValue("location");
                if (location.isEmpty()) break;
                uri = validate(uri.resolve(location.get()).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "The page answered " + status);
            }
            String type = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (type.contains("application/pdf")) {
                return new Fetched(null, null, read(response.body(), MAX_PDF_BYTES));
            }
            if (!type.isEmpty() && !type.contains("html") && !type.startsWith("text/")) {
                closeQuietly(response.body());
                throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "Unsupported content type " + type);
            }
            byte[] body = read(response.body(), MAX_HTML_BYTES);
            return htmlToText(new String(body, charsetOf(type)), uri.toString());
        }
        throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "Too many redirects");
    }

    /** Where a link really leads, and the page's title when it has one. */
    public record Resolved(URI uri, String title) {
    }

    /** Enough of an HTML page to find its title. */
    static final int TITLE_BYTES = 256 * 1024;

    /**
     * Follows a link to the page it lands on, with the same refusals as {@link #fetch}: every hop
     * is checked against the private network. Reads only enough of an HTML page for its title.
     * Used by source discovery, where search results can be redirect links and a result is only
     * worth offering if it opens.
     */
    public Resolved resolve(String url) {
        URI uri = validate(url);
        for (int hop = 0; hop <= MAX_REDIRECTS + 1; hop++) {
            HttpResponse<InputStream> response = send(uri);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                closeQuietly(response.body());
                Optional<String> location = response.headers().firstValue("location");
                if (location.isEmpty()) break;
                uri = validate(uri.resolve(location.get()).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                closeQuietly(response.body());
                throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "The page answered " + status);
            }
            String type = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            if (type.contains("application/pdf")) {
                closeQuietly(response.body());
                return new Resolved(uri, null);
            }
            if (!type.isEmpty() && !type.contains("html") && !type.startsWith("text/")) {
                closeQuietly(response.body());
                throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "Unsupported content type " + type);
            }
            byte[] head = readUpTo(response.body(), TITLE_BYTES);
            String title = Jsoup.parse(new String(head, charsetOf(type)), uri.toString()).title();
            return new Resolved(uri, title == null || title.isBlank() ? null : title.strip());
        }
        throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "Too many redirects");
    }

    /** Readable text out of an HTML document, one block per line, without menus or scripts. */
    static Fetched htmlToText(String html, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri);
        doc.select("script, style, noscript, nav, header, footer, aside, form, svg, iframe, template").remove();
        for (Element block : doc.select("p, h1, h2, h3, h4, h5, h6, li, pre, blockquote, tr, dd, dt, figcaption, br, div")) {
            block.appendText("\n");
        }
        Element body = doc.body();
        String text = body == null ? "" : body.wholeText();
        text = text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        if (text.isEmpty()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_UNREADABLE, "The page has no readable text");
        }
        String title = doc.title() == null || doc.title().isBlank() ? null : doc.title().strip();
        return new Fetched(title, text, null);
    }

    private HttpResponse<InputStream> send(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "Beyou-Notebook/1.0 (+https://beyouweb.com)")
                .header("Accept", "text/html,application/xhtml+xml,text/plain,application/pdf;q=0.9")
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException failed) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "Could not reach the page");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "Interrupted");
        }
    }

    private static byte[] read(InputStream in, int limit) {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) {
                    throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_TOO_LARGE, "The page is too large");
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException failed) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "The page stopped answering");
        }
    }

    /** The first {@code limit} bytes, then stops reading; unlike {@link #read}, more is not an error. */
    private static byte[] readUpTo(InputStream in, int limit) {
        try (in) {
            return in.readNBytes(limit);
        } catch (IOException failed) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "Could not read the page");
        }
    }

    private static Charset charsetOf(String contentType) {
        int at = contentType.indexOf("charset=");
        if (at < 0) return StandardCharsets.UTF_8;
        try {
            return Charset.forName(contentType.substring(at + 8).replace("\"", "").split(";")[0].strip());
        } catch (RuntimeException unknown) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void assertPublic(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException unknown) {
            throw new BusinessException(ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED, "The site could not be found");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw refused("The link points inside a private network");
            }
        }
    }

    /** Whether an address is somewhere the public internet can reach. See the class comment. */
    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            if (first == 0) return false;                                  // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return false; // 100.64.0.0/10, CGNAT
            if (first == 192 && second == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (first >= 224) return false;                                // multicast and reserved
            return true;
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                       // fc00::/7, unique-local
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d) wrap a v4 address.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { mapped = false; break; }
            }
            if (mapped && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException impossible) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static BusinessException refused(String message) {
        return new BusinessException(ErrorKey.NOTEBOOK_SOURCE_URL_REFUSED, message);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Nothing to do: the response is being discarded.
        }
    }
}
