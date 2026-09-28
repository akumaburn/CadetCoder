package com.eonmux.cadetcoder.net;

/**
 * JVM-wide HTTP settings that must be in place before the first {@link java.net.http.HttpClient}
 * exists.
 *
 * <h2>Why this cannot live next to the client it configures</h2>
 *
 * <p>The JDK reads these properties into {@code static final} fields when its connection-pool class
 * initialises, which happens the first time any {@code HttpClient} is built. Setting them afterwards
 * parses cleanly and changes nothing. So {@link #apply()} is called from the entry point, ahead of
 * the model catalog, the auth clients, and the backends.</p>
 */
public final class HttpClientTuning {

    /** How long an idle pooled connection may be reused, in seconds. */
    static final String KEEP_ALIVE_PROPERTY = "jdk.httpclient.keepalive.timeout";

    /**
     * Idle lifetime for a pooled connection.
     *
     * <p>The JDK's own default is 1200 seconds — verified by reading the resolved field rather than
     * from documentation. Almost nothing in front of a model API holds an idle connection that long:
     * an AWS load balancer closes at 60 seconds by default, nginx at 75, Cloudflare at roughly 100.
     * The pool therefore keeps handing out connections the far end closed minutes ago, and the
     * failure surfaces on the next request as an unreachable provider.</p>
     *
     * <p>An agentic run makes that routine rather than rare, because its gaps are exactly this long:
     * the user reads a result, or a build runs, and the next request lands on a dead socket. The
     * retry loop does recover it, but at the cost of a wasted attempt and a warning that reads to the
     * user as "the provider randomly dropped".</p>
     *
     * <p>50 seconds sits below the tightest of those, so a connection is dropped while both ends
     * still agree it is alive. Losing a reuse costs one TLS handshake; keeping a dead one costs a
     * failed request.</p>
     */
    static final int DEFAULT_KEEP_ALIVE_SECONDS = 50;

    private HttpClientTuning() {
    }

    /**
     * Applies the settings, leaving any the user set explicitly alone.
     *
     * <p>Safe to call more than once; only the first call before the pool initialises has an effect.</p>
     */
    public static void apply() {
        if (System.getProperty(KEEP_ALIVE_PROPERTY) == null) {
            System.setProperty(KEEP_ALIVE_PROPERTY, Integer.toString(DEFAULT_KEEP_ALIVE_SECONDS));
        }
        // jdk.httpclient.enableAllMethodRetry is deliberately NOT set. It would make the JDK repeat
        // failed POSTs inside send(), invisibly and without honouring Retry-After, on top of the
        // retry loop in AbstractLLMBackend -- turning a configured budget of N attempts into an
        // unpredictable multiple of it. Retrying belongs in the one place that can report it.
    }
}
