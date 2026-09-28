package com.eonmux.cadetcoder.auth;

import com.eonmux.cadetcoder.net.BoundedHttp;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.eonmux.cadetcoder.net.HttpRequests;
import com.eonmux.cadetcoder.security.OwnerOnlyFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Implements GitHub Copilot's OAuth device-authorization flow (the same scheme opencode
 * uses for the {@code github-copilot} provider):
 *
 * <ol>
 *   <li>request a device + user code from GitHub;</li>
 *   <li>the user authorizes the code in a browser;</li>
 *   <li>poll for a GitHub OAuth token (stored long-term);</li>
 *   <li>exchange that OAuth token for a short-lived Copilot API token used as the Bearer
 *       credential against {@code https://api.githubcopilot.com}.</li>
 * </ol>
 *
 * The persistent OAuth token is cached under {@code <baseDir>/copilot-auth.json}; the
 * short-lived Copilot token is refreshed on demand.
 */
public final class GitHubCopilotAuth {

    private static final CadetLogger LOG = CadetLogger.getLogger(GitHubCopilotAuth.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The OAuth client ID GitHub issued to its own Copilot editor integrations. GitHub has not
    // issued one to CadetCoder, which is why login shows DISCLAIMER first.
    static final String CLIENT_ID        = "Iv1.b507a08c87ecfe98";

    /** What the person is told before the login contacts GitHub. */
    static final String DISCLAIMER =
            "Note: CadetCoder is not affiliated with or endorsed by GitHub. This login uses the "
            + "OAuth client ID of GitHub's own Copilot editor integrations, and GitHub's terms may "
            + "not permit its use by other tools. GitHub could revoke access or restrict your "
            + "account. Use it at your own risk, and prefer a provider that issues API keys if that "
            + "matters to you.";
    static final String DEVICE_CODE_URL  = "https://github.com/login/device/code";
    static final String ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    static final String COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    static final String SCOPE            = "read:user";

    private final HttpClient http = HttpClient.newBuilder()
                                              .connectTimeout(Duration.ofSeconds(15))
                                              .build();

    /** Device-code response fields needed to drive the flow. */
    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final int    interval;
        public final int    expiresIn;

        DeviceCode(String deviceCode, String userCode, String verificationUri, int interval, int expiresIn) {
            this.deviceCode      = deviceCode;
            this.userCode        = userCode;
            this.verificationUri = verificationUri;
            this.interval        = interval;
            this.expiresIn       = expiresIn;
        }
    }

    /** Short-lived Copilot API token plus its expiry (epoch seconds). */
    public static final class CopilotToken {
        public final String token;
        public final long   expiresAt;

        CopilotToken(String token, long expiresAt) {
            this.token     = token;
            this.expiresAt = expiresAt;
        }
    }

    // --- flow steps ------------------------------------------------------

    /** Step 1: request a device + user code. */
    /**
     * Ceiling for each OAuth device-flow call, body included.
     *
     * <p>The request timeout above it bounds the wait for HEADERS only, so without this a call
     * that stops mid-body blocks the caller forever. A wedged auth call leaves the user staring at a device code that will never be accepted.</p>
     */
    private static final int AUTH_DEADLINE_SECONDS = 60;

    public DeviceCode requestDeviceCode() throws Exception {
        String body = "client_id=" + enc(CLIENT_ID) + "&scope=" + enc(SCOPE);
        HttpRequest req = HttpRequests.to(URI.create(DEVICE_CODE_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = BoundedHttp.send(http, req, AUTH_DEADLINE_SECONDS);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Device code request failed (status " + res.statusCode() + ")");
        }
        return parseDeviceCode(res.body());
    }

    static DeviceCode parseDeviceCode(String json) throws Exception {
        JsonNode n = MAPPER.readTree(json);
        return new DeviceCode(
                text(n, "device_code"),
                text(n, "user_code"),
                text(n, "verification_uri"),
                n.has("interval") ? n.get("interval").asInt() : 5,
                n.has("expires_in") ? n.get("expires_in").asInt() : 900);
    }

    /** Step 3: poll until the user authorizes (or the device code expires). Returns the OAuth token. */
    public String pollForOAuthToken(DeviceCode device) throws Exception {
        long deadline = System.currentTimeMillis() + device.expiresIn * 1000L;
        long intervalMs = Math.max(1, device.interval) * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(intervalMs);
            String body = "client_id=" + enc(CLIENT_ID)
                    + "&device_code=" + enc(device.deviceCode)
                    + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");
            HttpRequest req = HttpRequests.to(URI.create(ACCESS_TOKEN_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = BoundedHttp.send(http, req, AUTH_DEADLINE_SECONDS);
            JsonNode n = MAPPER.readTree(res.body());
            if (n.hasNonNull("access_token")) {
                return n.get("access_token").asText();
            }
            String error = text(n, "error");
            if (error == null || "authorization_pending".equals(error)) {
                continue; // keep waiting
            }
            if ("slow_down".equals(error)) {
                intervalMs += 5000L;
                continue;
            }
            throw new IllegalStateException("Copilot authorization failed: " + error);
        }
        throw new IllegalStateException("Copilot device authorization timed out");
    }

    /** Step 4: exchange a GitHub OAuth token for a short-lived Copilot API token. */
    public CopilotToken exchangeForCopilotToken(String oauthToken) throws Exception {
        HttpRequest req = HttpRequests.to(URI.create(COPILOT_TOKEN_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "token " + oauthToken)
                .header("User-Agent", "CadetCoder/1.0")
                .GET()
                .build();
        HttpResponse<String> res = BoundedHttp.send(http, req, AUTH_DEADLINE_SECONDS);
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Copilot token exchange failed (status " + res.statusCode() + ")");
        }
        return parseCopilotToken(res.body());
    }

    static CopilotToken parseCopilotToken(String json) throws Exception {
        JsonNode n = MAPPER.readTree(json);
        String token = text(n, "token");
        if (token == null) {
            throw new IllegalStateException("Copilot token response missing 'token'");
        }
        long expiresAt = n.has("expires_at") ? n.get("expires_at").asLong()
                : (System.currentTimeMillis() / 1000L + 1500);
        return new CopilotToken(token, expiresAt);
    }

    // --- orchestration ---------------------------------------------------

    /**
     * Runs the full interactive login: prints the user code + verification URL, waits for
     * authorization, stores the OAuth token, and returns a usable Copilot token.
     */
    public CopilotToken login(java.io.PrintStream out) throws Exception {
        out.println(DISCLAIMER);
        out.println();
        out.flush();
        DeviceCode device = requestDeviceCode();
        out.println("To connect GitHub Copilot, open: " + device.verificationUri);
        out.println("and enter the code: " + device.userCode);
        out.flush();
        String oauthToken = pollForOAuthToken(device);
        storeOAuthToken(oauthToken);
        return exchangeForCopilotToken(oauthToken);
    }

    /**
     * Reports whether a long-term GitHub OAuth token is already stored on disk, without
     * performing any network call. Useful for a fast "logged in?" status check.
     */
    public boolean hasStoredOAuthToken() {
        return loadOAuthToken() != null;
    }

    /**
     * Removes the stored GitHub OAuth token (logout).
     *
     * <p>A failed delete is raised rather than reported as a {@code false}. This used to answer
     * {@code false} to three different questions -- nothing was stored, the path could not be
     * worked out, and the delete failed -- and the caller had one message for all of them. A token
     * that could not be removed was therefore announced as already absent while it stayed on disk,
     * still valid, still authenticating this machine.</p>
     *
     * @return {@code true} if a token file existed and was deleted, {@code false} if there was
     *         nothing stored
     * @throws IOException if the token could not be removed, or its location could not be resolved
     */
    public boolean clearStoredToken() throws IOException {
        Path file = authFile();
        if (file == null) {
            throw new IOException("Could not work out where the GitHub Copilot token is stored");
        }
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Failed to remove Copilot token: " + e.getMessage());
            throw new IOException("Could not remove " + file + ": " + e.getMessage(), e);
        }
    }

    /**
     * How long before its stated expiry a token is treated as spent.
     *
     * <p>A token that expires while a request is in flight fails that request, and the request may
     * be a long completion. Five minutes covers the slowest completion this tool waits for, so the
     * token handed out is good for the whole of the call it is handed out for.</p>
     */
    static final long REFRESH_MARGIN_SECONDS = 300L;

    /**
     * The last token obtained, kept only as long as it is good for.
     *
     * <p>Not static: a cache that outlives the object makes one test's login visible to the next,
     * and the object already lives exactly as long as the client that holds it.</p>
     */
    private CopilotToken cached;

    /**
     * Returns a valid Copilot token if a stored OAuth token exists (refreshing as needed),
     * or null if the user has not yet logged in.
     *
     * <h2>Why this both caches and expires</h2>
     *
     * <p>A Copilot token lives about twenty-five minutes; the OAuth token it is minted from lives
     * until revoked. So the exchange has to be repeated during any run longer than that, and must
     * not be repeated for every request -- it is a network round trip to GitHub before the round
     * trip to the model. Holding it until shortly before it expires is both.</p>
     *
     * <p>This used to exchange on every call and be called once, at client construction, which is
     * the worst of the two: the token was minted fresh and then used until it was hours stale. The
     * expiry was parsed all along and read only to print it.</p>
     *
     * @return a token good for at least {@link #REFRESH_MARGIN_SECONDS} more, or {@code null} when
     *         there is no stored login or the exchange failed
     */
    public synchronized CopilotToken getValidCopilotToken() {
        return getValidCopilotTokenAt(System.currentTimeMillis() / 1000L);
    }

    /**
     * The same, at a stated moment, so the expiry rule can be exercised without waiting for it.
     *
     * @param nowSeconds the current time in epoch seconds
     * @return a token good at that moment, or {@code null}
     */
    synchronized CopilotToken getValidCopilotTokenAt(long nowSeconds) {
        if (isUsableAt(cached, nowSeconds)) {
            return cached;
        }
        cached = null;
        String oauthToken = loadOAuthToken();
        if (oauthToken == null) {
            return null;
        }
        try {
            cached = exchangeForCopilotToken(oauthToken);
            return cached;
        } catch (Exception e) {
            LOG.warn("Failed to refresh Copilot token: " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether a token can still be used.
     *
     * @param token      the token to judge, possibly {@code null}
     * @param nowSeconds the current time in epoch seconds
     * @return whether it exists and does not expire within the refresh margin
     */
    static boolean isUsableAt(CopilotToken token, long nowSeconds) {
        return token != null
               && token.token != null
               && !token.token.isEmpty()
               && token.expiresAt - REFRESH_MARGIN_SECONDS > nowSeconds;
    }

    // --- token persistence ----------------------------------------------

    /**
     * Writes the long-lived GitHub OAuth token to disk.
     *
     * <p>A failed write is raised rather than logged and passed over. Nothing holds the token in
     * memory -- every later credential lookup re-reads this file -- so a write that did not happen
     * means the login did not happen, and {@link #login} went on to announce a connection anyway.
     * The user had already authorized the device code by then, and the next run found no
     * credential.</p>
     *
     * @param oauthToken the token to store
     * @throws IOException if the token could not be written, or its location could not be resolved
     */
    void storeOAuthToken(String oauthToken) throws IOException {
        Path file = authFile();
        if (file == null) {
            throw new IOException("Could not work out where to store the GitHub Copilot token");
        }
        Files.createDirectories(file.getParent());
        String json = MAPPER.writeValueAsString(java.util.Map.of("github_oauth_token", oauthToken));
        // The file is brought into existence owner-only and only then written to. Restricting an
        // absent file does nothing -- there is nothing there to narrow -- so a restrict placed
        // before the write was a precaution against the one case it could not help with: the first
        // login, where the write itself created the file, at whatever the umask allowed, with the
        // token already in it. Narrowing it afterwards closes the window; not opening one is
        // better.
        String created = OwnerOnlyFile.createOwnerOnly(file);
        if (created != null) {
            LOG.warn("Could not create " + file + " restricted to this account before writing a "
                     + "long-lived GitHub token into it: " + created);
        }
        Files.writeString(file, json, StandardCharsets.UTF_8);
        // A token that IS on disk but could not be locked down is still a usable login, so this
        // warns rather than failing: refusing here would leave the user with no credential at all
        // on a filesystem that has no permission bits to set.
        String failure = OwnerOnlyFile.restrict(file);
        if (failure != null) {
            LOG.warn("Could not restrict permissions on " + file
                     + ", which holds a long-lived GitHub token: " + failure);
        }
    }

    String loadOAuthToken() {
        Path file = authFile();
        if (file == null || !Files.exists(file)) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
            return text(n, "github_oauth_token");
        } catch (Exception e) {
            return null;
        }
    }

    private Path authFile() {
        try {
            String baseDir = ConfigManager.getInstance().getConfig().getBaseDir();
            if (baseDir != null && !baseDir.isBlank()) {
                return Paths.get(baseDir, "copilot-auth.json");
            }
        } catch (Exception e) {
            // fall through
        }
        try {
            return Paths.get(System.getProperty("user.home"), ".cadet", "copilot-auth.json");
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
