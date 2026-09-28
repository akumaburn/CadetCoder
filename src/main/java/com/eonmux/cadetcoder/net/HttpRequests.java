package com.eonmux.cadetcoder.net;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.Locale;

/**
 * Where every HTTP request begins, so each one is sent in an HTTP version its server can read.
 *
 * <h2>Why a plain address is sent HTTP/1.1</h2>
 *
 * <p>{@code HttpClient} prefers HTTP/2, and on an address without TLS it asks for it with
 * {@code Upgrade: h2c} on the first request. A model server run by uvicorn -- vLLM, SGLang and
 * most Python servers -- takes that request as a protocol switch and never reads its body, and
 * answers every completion with "Field required ... 'loc': 'body'". A GET has no body, so the
 * model list worked and only the requests that mattered failed. No local model server needs
 * HTTP/2, so a plain address is sent HTTP/1.1 and no upgrade is asked for.</p>
 *
 * <h2>Why TLS is left alone</h2>
 *
 * <p>Over TLS the version is agreed during the handshake, and a server that cannot speak HTTP/2
 * simply does not offer it. No upgrade request is made, so the client's preference is kept.</p>
 */
public final class HttpRequests {

    private HttpRequests() {
    }

    /**
     * A request to {@code uri}, in the version its scheme calls for.
     *
     * @param uri where the request goes
     * @return a builder with the address and version set
     */
    public static HttpRequest.Builder to(URI uri) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri);
        String scheme = uri.getScheme();
        if (scheme != null && scheme.toLowerCase(Locale.ROOT).equals("http")) {
            builder.version(HttpClient.Version.HTTP_1_1);
        }
        return builder;
    }
}
