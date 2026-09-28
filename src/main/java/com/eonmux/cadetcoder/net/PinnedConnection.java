package com.eonmux.cadetcoder.net;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * A GET connection that goes to the address that was screened, not to whatever DNS says next.
 *
 * <h2>How the name survives the pinning</h2>
 *
 * <p>The socket has to target a literal IP, so the URL is rewritten to one. Everything that
 * identifies the site by name is then restored by hand: the {@code Host} header, and for HTTPS both
 * the SNI server name -- so the server picks the right certificate -- and the hostname verifier,
 * which must compare the presented certificate against the ORIGINAL host. Left to itself the
 * default verifier compares it against the IP literal and every HTTPS fetch fails.</p>
 *
 * <h2>Why the JDK has to be told to allow a Host header</h2>
 *
 * <p>{@code HttpURLConnection} silently discards a {@code Host} set on it, deriving the header from
 * the URL instead -- which, once the URL has been rewritten, is the IP literal. Every name-based
 * virtual host on that address would then answer with whatever it serves by default, and the fetch
 * would return the wrong page without saying so. The one supported way to override it is the JDK
 * property set below; it is read when the protocol handler is first loaded, so it is set here,
 * before this class can open anything.</p>
 *
 * <h2>Why redirects are not followed here</h2>
 *
 * <p>Following them automatically would connect to whatever the redirect names without screening
 * it, which is the whole hole {@link OutboundAddress} exists to close. Each hop is the caller's to
 * screen and re-open.</p>
 */
public final class PinnedConnection {

    /** JDK switch for the header set {@code HttpURLConnection} otherwise keeps to itself. */
    private static final String ALLOW_RESTRICTED   = "sun.net.http.allowRestrictedHeaders";
    private static final String USER_AGENT         = "CadetCoder/1.0";
    /** 307, which HttpURLConnection has no constant for. */
    private static final int    TEMPORARY_REDIRECT = 307;
    /** 308, likewise. */
    private static final int    PERMANENT_REDIRECT = 308;

    static {
        allowHostHeader();
    }

    private PinnedConnection() {}

    /**
     * Lets this process set a {@code Host} header on an {@code HttpURLConnection}.
     *
     * <p>Called from {@code Main} at start-up as well as from this class's own initializer. The JDK
     * reads the setting once, when its HTTP protocol handler is first loaded, and jgit opens
     * connections of its own -- so in a session that pushed before it fetched, waiting until the
     * first fetch to ask would be asking too late, and the pinned request would name an IP literal
     * to a server that decides what to serve by name.</p>
     *
     * <p>Whatever the JVM was started with wins: an operator who set it deliberately, either way,
     * meant it.</p>
     */
    public static void allowHostHeader() {
        if (System.getProperty(ALLOW_RESTRICTED) == null) {
            System.setProperty(ALLOW_RESTRICTED, "true");
        }
    }

    /**
     * Opens a GET connection to {@code pinned}, presenting itself as {@code url}'s host.
     *
     * @param url           the URL as asked for, whose host names the site
     * @param pinned        the screened address to connect to, or {@code null} when the host did not
     *                      resolve and the ordinary failure path should report that
     * @param timeoutMillis connect and read timeout
     * @return the connection, not yet read from
     * @throws IOException if the connection cannot be opened
     */
    public static HttpURLConnection open(URL url, InetAddress pinned, int timeoutMillis)
            throws IOException {
        HttpURLConnection connection = pinned == null
                                       ? (HttpURLConnection) url.openConnection()
                                       : toPinnedAddress(url, pinned);

        connection.setRequestMethod("GET");
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        return connection;
    }

    /**
     * Whether a status code sends the caller somewhere else.
     *
     * <p>Here rather than at each caller because {@link #open} switches redirect following off, so
     * every caller has to recognise a hop for itself, and a caller that recognises fewer of them
     * than another silently treats a redirect body as the answer.</p>
     *
     * @param status the HTTP status code
     * @return {@code true} when the response is a redirect the caller must screen and re-open
     */
    public static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
               || status == HttpURLConnection.HTTP_MOVED_TEMP
               || status == HttpURLConnection.HTTP_SEE_OTHER
               || status == TEMPORARY_REDIRECT
               || status == PERMANENT_REDIRECT;
    }

    /** The same request, addressed to a literal IP with the site's own name carried alongside. */
    private static HttpURLConnection toPinnedAddress(URL url, InetAddress pinned)
            throws IOException {
        String host      = url.getHost();
        int    port      = url.getPort();
        URL    pinnedUrl = new URL(url.getProtocol(), literalForUrl(pinned), port, url.getFile());

        HttpURLConnection connection = (HttpURLConnection) pinnedUrl.openConnection();
        connection.setRequestProperty("Host", port == -1 ? host : host + ":" + port);

        if (connection instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(
                    new SniSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault(), host));
            https.setHostnameVerifier((hostname, session) ->
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session));
        }
        return connection;
    }

    /** Bracketed form for IPv6, bare host for IPv4, as required inside a URL. */
    private static String literalForUrl(InetAddress address) {
        String literal = address.getHostAddress();
        if (address instanceof Inet6Address) {
            // Strip any zone id and wrap in brackets for URL use.
            int zone = literal.indexOf('%');
            if (zone >= 0) {
                literal = literal.substring(0, zone);
            }
            return "[" + literal + "]";
        }
        return literal;
    }

    /**
     * Connects as the pinned-IP URL says, but announces the original host as the SNI server name so
     * the server selects the right certificate and the verifier can validate it.
     */
    private static final class SniSocketFactory extends SSLSocketFactory {

        private final SSLSocketFactory delegate;
        private final String           sniHost;

        SniSocketFactory(SSLSocketFactory delegate, String sniHost) {
            this.delegate = delegate;
            this.sniHost  = sniHost;
        }

        private Socket withSni(Socket socket) {
            if (socket instanceof SSLSocket ssl && sniHost != null && !sniHost.isEmpty()) {
                SSLParameters parameters = ssl.getSSLParameters();
                try {
                    List<SNIServerName> names = new ArrayList<>();
                    names.add(new SNIHostName(sniHost));
                    parameters.setServerNames(names);
                    ssl.setSSLParameters(parameters);
                } catch (IllegalArgumentException ignored) {
                    // Not a valid SNI name (an IP literal, say); skip SNI rather than fail the fetch.
                }
            }
            return socket;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return withSni(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return withSni(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                throws IOException {
            return withSni(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return withSni(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
                                   int localPort) throws IOException {
            return withSni(delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }
}
