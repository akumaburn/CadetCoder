package com.eonmux.cadetcoder.net;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where this tool will fetch from is one rule, and the address it fetches is the one that answered
 * it.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code webfetch} and {@code websearch} each carried their own copy of the screen. The copies
 * agreed on which ranges to refuse, but not on what to do afterwards: {@code webfetch} connected to
 * the address it had validated, while {@code websearch} validated a name and then called
 * {@code openConnection()}, which resolves it a second time. A record that answers publicly on the
 * first lookup and {@code 169.254.169.254} on the second passed the screen and was fetched anyway --
 * in the command whose javadoc said it mirrored the hardened one.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Every range both copies refused is still refused; loopback is still reachable, because this is
 * a developer tool; a name that does not resolve is not reported as an SSRF refusal; and a
 * connection opened through the gate reaches the screened address rather than whatever the name
 * would resolve to next, carrying the site's own name with it.</p>
 */
public class OneOutboundAddressRuleTest {

    private HttpServer server;

    @After
    public void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static OutboundAddress screen(String url) throws Exception {
        return OutboundAddress.of(new URI(url).toURL());
    }

    @Test
    public void theCloudMetadataAddressIsRefused() throws Exception {
        assertThat(screen("http://169.254.169.254/latest/meta-data/").refusal())
                .contains("link-local").contains("169.254.169.254");
    }

    @Test
    public void everyPrivateRangeBothCopiesRefusedIsStillRefused() throws Exception {
        for (String target : new String[] {"http://10.1.2.3/", "http://172.16.0.1/",
                                           "http://192.168.1.1/", "http://[fd00::1]/",
                                           "http://[fc00::1]/"}) {
            assertThat(screen(target).refusal())
                    .as("%s is a private address and must not be fetchable", target)
                    .contains("private address");
        }
    }

    @Test
    public void anAddressThatGoesNowhereInParticularIsRefused() throws Exception {
        for (String target : new String[] {"http://0.0.0.0/", "http://[::]/", "http://224.0.0.1/"}) {
            assertThat(screen(target).refusal())
                    .as("%s is not a routable target", target)
                    .contains("non-routable");
        }
    }

    @Test
    public void loopbackStaysReachableBecauseThisIsADeveloperTool() throws Exception {
        for (String target : new String[] {"http://127.0.0.1:8080/", "http://[::1]:8080/"}) {
            OutboundAddress screened = screen(target);
            assertThat(screened.refusal())
                    .as("%s is where a developer's own server runs", target)
                    .isNull();
            assertThat(screened.pinned()).isNotNull();
        }
    }

    @Test
    public void aTargetThatIsAllowedComesBackWithSomewhereToConnectTo() throws Exception {
        OutboundAddress screened = screen("http://93.184.216.34/");

        assertThat(screened.refusal()).isNull();
        assertThat(screened.pinned())
                .as("an allowed target has to say which address was allowed, or nothing is pinned")
                .isNotNull();
        assertThat(screened.pinned().getHostAddress()).isEqualTo("93.184.216.34");
    }

    @Test
    public void aHostThatDoesNotResolveIsNotReportedAsARefusal() throws Exception {
        // .invalid never resolves (RFC 2606), and a name that resolves to nothing cannot be an SSRF
        // target -- saying "refused" about it would blame the screen for a typo.
        assertThat(screen("http://nowhere.invalid/").refusal()).isNull();
    }

    @Test
    public void thereIsNothingToScreenWithoutATarget() {
        assertThat(OutboundAddress.of(null).refusal()).contains("no target");
    }

    @Test
    public void aUrlWithNoHostIsRefusedRatherThanResolved() throws Exception {
        assertThat(screen("file:///etc/passwd").refusal()).contains("missing host");
    }

    @Test
    public void theConnectionGoesToTheAddressThatWasScreenedAndNotToTheName() throws Exception {
        AtomicReference<String> hostSeen = new AtomicReference<>();
        int port = start(exchange -> {
            hostSeen.set(exchange.getRequestHeaders().getFirst("Host"));
            byte[] body = "reached".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        // A name that resolves to nothing at all: the only way this request can arrive is by going
        // to the pinned address. Without pinning, opening it throws UnknownHostException.
        URL named = new URI("http://nowhere.invalid:" + port + "/probe").toURL();
        HttpURLConnection connection =
                PinnedConnection.open(named, InetAddress.getByName("127.0.0.1"), 5000);

        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
        } finally {
            connection.disconnect();
        }
        assertThat(hostSeen.get())
                .as("the site is still identified by its own name, port included")
                .isEqualTo("nowhere.invalid:" + port);
    }

    @Test
    public void everyRedirectStatusThatSendsUsElsewhereIsRecognisedAsOne() {
        for (int status : new int[] {301, 302, 303, 307, 308}) {
            assertThat(PinnedConnection.isRedirect(status))
                    .as("%d moves the fetch to another host, which has to be screened too", status)
                    .isTrue();
        }
        for (int status : new int[] {200, 204, 304, 400, 500}) {
            assertThat(PinnedConnection.isRedirect(status))
                    .as("%d is an answer, not a hop", status)
                    .isFalse();
        }
    }

    /** A server on loopback that only the pinned address can reach. */
    private int start(HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", handler);
        server.start();
        return server.getAddress().getPort();
    }
}
