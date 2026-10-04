package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.net.InetAddress;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import beyou.beyouapp.backend.domain.notebook.source.LinkFetcher;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;

/**
 * The one place the server fetches a URL a user typed must not be a way into the network it
 * runs in. Every address here is one a request could be bounced to.
 */
class LinkFetcherTest {

    private final LinkFetcher fetcher = new LinkFetcher();

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.164", "169.254.169.254", "0.0.0.0",
        "100.64.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1"})
    void privateAndSpecialAddressesAreNotPublic(String address) throws Exception {
        assertThat(isPublic(InetAddress.getByName(address))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"})
    void ordinaryInternetAddressesArePublic(String address) throws Exception {
        assertThat(isPublic(InetAddress.getByName(address))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ftp://example.com/x", "file:///etc/passwd", "http://localhost:9091/actuator",
        "http://127.0.0.1/", "http://user:pass@example.com/", "not a url", "http:///nohost"})
    void refusedLinksNeverReachTheNetwork(String url) {
        assertThatThrownBy(() -> fetcher.validate(url))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isIn(ErrorKey.NOTEBOOK_SOURCE_URL_REFUSED, ErrorKey.NOTEBOOK_SOURCE_FETCH_FAILED);
    }

    @Test
    void htmlLosesItsScriptsAndMenusAndKeepsItsParagraphs() throws Exception {
        Method htmlToText = LinkFetcher.class.getDeclaredMethod("htmlToText", String.class, String.class);
        htmlToText.setAccessible(true);
        LinkFetcher.Fetched fetched = (LinkFetcher.Fetched) htmlToText.invoke(null, """
                <html><head><title>BST notes</title><script>steal()</script></head>
                <body><nav>Home | About</nav><h1>Binary search trees</h1><p>Left is smaller.</p><p>Right is larger.</p>
                <footer>Copyright</footer></body></html>
                """, "https://example.com");

        assertThat(fetched.title()).isEqualTo("BST notes");
        assertThat(fetched.text()).isEqualTo("Binary search trees\nLeft is smaller.\nRight is larger.");
    }

    private static boolean isPublic(InetAddress address) throws Exception {
        Method method = LinkFetcher.class.getDeclaredMethod("isPublic", InetAddress.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, address);
    }
}
