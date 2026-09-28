package com.eonmux.cadetcoder.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 request signer. Used by the Amazon Bedrock connector to
 * authenticate Converse API calls when no bearer API key is supplied.
 *
 * <p>Implements the algorithm documented at
 * <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4_signing.html">AWS SigV4</a>.
 */
public final class AwsSigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4Signer() { }

    /** Immutable AWS credential bundle. */
    public static final class Credentials {
        private final String accessKeyId;
        private final String secretAccessKey;
        private final String sessionToken; // nullable

        public Credentials(String accessKeyId, String secretAccessKey, String sessionToken) {
            this.accessKeyId     = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.sessionToken    = sessionToken;
        }

        public String getAccessKeyId()     { return accessKeyId; }
        public String getSecretAccessKey() { return secretAccessKey; }
        public String getSessionToken()    { return sessionToken; }

        /** Resolves credentials from the standard AWS environment variables, or null. */
        public static Credentials fromEnvironment() {
            String access = System.getenv("AWS_ACCESS_KEY_ID");
            String secret = System.getenv("AWS_SECRET_ACCESS_KEY");
            if (access == null || access.isBlank() || secret == null || secret.isBlank()) {
                return null;
            }
            String session = System.getenv("AWS_SESSION_TOKEN");
            return new Credentials(access, secret, (session != null && !session.isBlank()) ? session : null);
        }
    }

    /**
     * Computes the complete set of headers (including {@code Authorization}) to send with
     * a signed request. The returned map includes {@code host}, {@code x-amz-date},
     * {@code x-amz-content-sha256}, any caller-supplied base headers, and the session
     * token header when present.
     *
     * @param method     HTTP method (e.g. POST)
     * @param uri        the full request URI
     * @param baseHeaders headers that participate in signing (e.g. content-type); names are lowercased
     * @param payload    the request body bytes (empty array for no body)
     * @param region     AWS region (e.g. us-east-1)
     * @param service    AWS service name (e.g. bedrock)
     * @param creds      AWS credentials
     * @param time       the signing timestamp
     * @return headers to apply to the outbound request
     */
    public static Map<String, String> signedHeaders(String method,
                                                     URI uri,
                                                     Map<String, String> baseHeaders,
                                                     byte[] payload,
                                                     String region,
                                                     String service,
                                                     Credentials creds,
                                                     Instant time) {
        String amzDateTime = AMZ_DATE_TIME.format(time);
        String dateStamp   = AMZ_DATE.format(time);
        String payloadHash = sha256Hex(payload);

        // Assemble the headers that will be signed (sorted, lowercased names).
        TreeMap<String, String> signing = new TreeMap<>();
        if (baseHeaders != null) {
            for (Map.Entry<String, String> e : baseHeaders.entrySet()) {
                signing.put(canonicalHeaderName(e.getKey()), canonicalHeaderValue(e.getValue()));
            }
        }
        signing.put("host", canonicalHost(uri));
        signing.put("x-amz-date", amzDateTime);
        signing.put("x-amz-content-sha256", payloadHash);
        if (creds.getSessionToken() != null) {
            signing.put("x-amz-security-token", creds.getSessionToken());
        }

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaderNames = new StringBuilder();
        for (Map.Entry<String, String> e : signing.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':').append(e.getValue()).append('\n');
            if (signedHeaderNames.length() > 0) {
                signedHeaderNames.append(';');
            }
            signedHeaderNames.append(e.getKey());
        }
        String signedHeaders = signedHeaderNames.toString();

        String canonicalRequest = method + "\n"
                + canonicalUri(uri) + "\n"
                + canonicalQuery(uri) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDateTime + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = getSignatureKey(creds.getSecretAccessKey(), dateStamp, region, service);
        String signature  = hex(hmacSha256(signingKey, stringToSign));

        String authorization = ALGORITHM
                + " Credential=" + creds.getAccessKeyId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        // Return everything the caller should set on the wire.
        TreeMap<String, String> result = new TreeMap<>(signing);
        result.put("authorization", authorization);
        return result;
    }

    // --- canonicalization helpers ---------------------------------------

    /**
     * A header name as it appears in the canonical request: lowercase by ASCII rules.
     *
     * <p>Not {@code toLowerCase()}. That uses the machine's default language, and in Turkish
     * {@code I} lowercases to the dotless {@code ı}: a header named {@code X-Amz-Invocation-Id}
     * was signed and sent as {@code x-amz-ınvocatıon-ıd}, so every signed request from a Turkish
     * machine was rejected, and only from a Turkish machine.</p>
     *
     * @param name the header name as the caller wrote it
     * @return the name AWS will lowercase it to
     */
    private static String canonicalHeaderName(String name) {
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * A header value as it appears in the canonical request.
     *
     * <p>AWS trims the value <em>and</em> collapses each run of whitespace inside it to one space
     * before signing. Trimming alone signed {@code application/json,   charset=utf-8} with the
     * spaces the caller wrote and AWS with one, which is a 403 whose cause is a space.</p>
     *
     * @param value the header value as the caller wrote it
     * @return the value AWS will sign
     */
    private static String canonicalHeaderValue(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String canonicalHost(URI uri) {
        String host = uri.getHost();
        int port = uri.getPort();
        if (port != -1 && !isDefaultPort(uri.getScheme(), port)) {
            return host + ":" + port;
        }
        return host;
    }

    private static boolean isDefaultPort(String scheme, int port) {
        return ("https".equalsIgnoreCase(scheme) && port == 443)
                || ("http".equalsIgnoreCase(scheme) && port == 80);
    }

    private static String canonicalUri(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        // Each path segment must be URI-encoded; '/' separators are preserved. Bedrock
        // model ids can contain ':' and '.', which are left as-is per AWS encoding rules.
        String[] segments = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(uriEncode(segments[i], false));
        }
        return sb.toString();
    }

    private static String canonicalQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        // Sorted by encoded name, then by encoded value -- and a name given more than once keeps
        // every value it was given. A map keyed by name kept only the last, so the signature
        // covered a query the request did not have, which AWS reads as a forged request rather
        // than a mistaken one.
        java.util.List<String[]> pairs = new java.util.ArrayList<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name  = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            pairs.add(new String[] {uriEncode(urlDecode(name), true), uriEncode(urlDecode(value), true)});
        }
        // By name, then by value -- on the components rather than on the joined string. Joined,
        // "a=2" sorts after "a0=1" because '=' is above '0', which puts the parameters in an order
        // AWS did not sign.
        pairs.sort(java.util.Comparator.<String[], String>comparing(p -> p[0]).thenComparing(p -> p[1]));
        StringBuilder sb = new StringBuilder();
        for (String[] pair : pairs) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(pair[0]).append('=').append(pair[1]);
        }
        return sb.toString();
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    /** RFC 3986 percent-encoding as required by SigV4. */
    static String uriEncode(String input, boolean encodeSlash) {
        StringBuilder result = new StringBuilder();
        for (byte b : input.getBytes(StandardCharsets.UTF_8)) {
            char ch = (char) (b & 0xFF);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-' || ch == '~' || ch == '.') {
                result.append(ch);
            } else if (ch == '/') {
                result.append(encodeSlash ? "%2F" : "/");
            } else {
                result.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return result.toString();
    }

    // --- crypto ----------------------------------------------------------

    /** Derives the SigV4 signing key. Exposed for testing against AWS's published vector. */
    public static byte[] getSignatureKey(String secretKey, String dateStamp, String region, String service) {
        byte[] kSecret  = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate    = hmacSha256(kSecret, dateStamp);
        byte[] kRegion  = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, "aws4_request");
    }

    public static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
