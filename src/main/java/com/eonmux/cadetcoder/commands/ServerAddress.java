package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where a model server runs, for a connector whose server may be on any machine.
 *
 * <h2>Which connectors are asked</h2>
 *
 * <p>A connector whose default address is on this machine: a local OpenAI-compatible server such
 * as llama-server, LM Studio or Ollama. Such a server is as often on another machine on the
 * network, and a hosted provider's address is never the user's to choose, so it is the address on
 * this machine that marks a connector as one to ask. No list of connector ids has to be kept.</p>
 *
 * <h2>How an answer is read</h2>
 *
 * <p>As {@code host:port}, a bare host, or a full URL. Whatever the answer leaves out is taken
 * from the address in use: {@code 192.168.1.20} means the same scheme, port and path on another
 * machine. A trailing slash is dropped, because the listing and the requests append their own
 * paths to the address.</p>
 */
final class ServerAddress {

    /** The host names that mean this machine. */
    private static final Set<String> THIS_MACHINE = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private static final int HIGHEST_PORT = 65_535;

    /**
     * What the user answered.
     *
     * @param baseUrl   the address to use, or {@code null} to keep the one in use
     * @param cancelled whether the answer could not be used, in which case nothing may change
     */
    record Answer(String baseUrl, boolean cancelled) {

        /** Keep the address in use; also the answer for a connector that is not asked. */
        static final Answer KEEP = new Answer(null, false);

        /** An answer that could not be read. */
        static final Answer CANCELLED = new Answer(null, true);

        static Answer at(String baseUrl) {
            return new Answer(baseUrl, false);
        }
    }

    private ServerAddress() {
    }

    /**
     * @param connector the connector about to be used
     * @return whether to ask where its server runs
     */
    static boolean asksWhere(ProviderConnector connector) {
        String address = connector == null ? null : connector.getDefaultBaseUrl();
        if (address == null) {
            return false;
        }
        try {
            String host = new URI(address).getHost();
            return host != null && THIS_MACHINE.contains(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException unreadable) {
            return false;
        }
    }

    /**
     * The address the connector uses now.
     *
     * @param connector the connector
     * @param options   its configured options, may be {@code null}
     * @return the configured {@code baseURL}, or the connector's default
     */
    static String inUse(ProviderConnector connector, Map<String, String> options) {
        String configured = options == null ? null : options.get(ConnectorSupport.BASE_URL_OPTION);
        return configured != null && !configured.isBlank() ? configured.trim()
                                                           : connector.getDefaultBaseUrl();
    }

    /**
     * Reads an answer as the address of a server.
     *
     * @param typed   what the user typed; blank means the address in use
     * @param current the address in use, which supplies whatever the answer leaves out
     * @return the full address, with no trailing slash
     * @throws IllegalArgumentException naming what is wrong, when the answer is not an address
     */
    static String read(String typed, String current) {
        if (typed == null || typed.isBlank()) {
            return current;
        }
        String text     = typed.trim();
        URI    fallback = URI.create(current);
        URI    given    = parse(text.contains("://") ? text : fallback.getScheme() + "://" + text);

        String scheme = given.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(
                    "'" + text + "' is not an http or https address.");
        }
        String host = given.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("'" + text + "' names no host.");
        }
        int    port = given.getPort() != -1 ? given.getPort() : portOf(fallback, text.contains("://"));
        String path = given.getRawPath() == null || given.getRawPath().isEmpty()
                      ? fallback.getRawPath() : given.getRawPath();
        String address = scheme + "://" + host + (port != -1 ? ":" + port : "") + path;
        return address.endsWith("/") ? address.substring(0, address.length() - 1) : address;
    }

    /**
     * The port an answer with none of its own gets.
     *
     * <p>A bare host keeps the port in use: a server moved to another machine is usually the same
     * server. A full URL written without a port means the scheme's own port, as a browser reads
     * it.</p>
     */
    private static int portOf(URI fallback, boolean aFullUrl) {
        return aFullUrl ? -1 : fallback.getPort();
    }

    private static URI parse(String address) {
        URI uri;
        try {
            uri = new URI(address);
        } catch (URISyntaxException unreadable) {
            throw new IllegalArgumentException(
                    "'" + address + "' is not an address: " + unreadable.getReason() + ".");
        }
        if (uri.getHost() == null && uri.getAuthority() != null) {
            // java.net.URI parses a port it cannot accept -- 99999, or letters -- as a registry
            // authority with no host, rather than failing.
            throw new IllegalArgumentException("'" + uri.getAuthority() + "' needs a host and a "
                                               + "port from 1 to " + HIGHEST_PORT + ".");
        }
        if (uri.getPort() > HIGHEST_PORT) {
            throw new IllegalArgumentException("The port must be from 1 to " + HIGHEST_PORT + ".");
        }
        return uri;
    }
}
