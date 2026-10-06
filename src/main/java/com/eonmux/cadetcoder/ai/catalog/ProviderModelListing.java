package com.eonmux.cadetcoder.ai.catalog;

import com.eonmux.cadetcoder.ai.providers.AuthHeaders;
import com.eonmux.cadetcoder.ai.providers.ConnectorProtocol;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.net.BoundedHttp;
import com.eonmux.cadetcoder.net.HttpRequests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Asks a provider which models it has, when the catalog cannot say.
 *
 * <h2>The gap this fills</h2>
 *
 * <p>Every model the tool offers comes from the models.dev catalog, and models.dev does not list
 * every provider a user can connect to. A gateway too new to appear there, a company's internal
 * endpoint, an Ollama or LM Studio server on the machine -- for all of these the catalog answers
 * "no models", and both places that ask it degraded to the same thing: a bare prompt inviting the
 * user to type a model id from memory. Command Code, which carries sixty-nine models behind one
 * key, is the case that made it plain: the tool could reach every one of them and could name none
 * of them.</p>
 *
 * <p>An OpenAI-compatible endpoint already publishes the answer at {@code GET {base}/models}, so it
 * is asked directly. This is a fallback and not a replacement: models.dev carries pricing, context
 * limits, tool-call support and modality flags that {@code /models} does not, so a provider the
 * catalog knows is still read from the catalog.</p>
 *
 * <h2>Why nothing here throws</h2>
 *
 * <p>The caller is always in the middle of something the user asked for -- choosing a model, or
 * printing a table -- and a provider that cannot be reached is not a reason to abandon it. An empty
 * list means "could not say", which is precisely the state the caller already handles, because it
 * is the state it was in before this class existed.</p>
 *
 * <h2>Why the answer is remembered</h2>
 *
 * <p>Four places want the same list within one run of {@code models}: the count beside a connector,
 * the table of one connector's models, a search across providers, and the interactive picker. Asked
 * afresh each time, a single command spent four round trips on one unchanging answer, and the user
 * waited for each. A model list does not change while a process runs, so the first answer is the
 * answer.</p>
 */
public final class ProviderModelListing {

    private static final CadetLogger  LOG    = CadetLogger.getLogger(ProviderModelListing.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wait for response headers. A listing is small; an endpoint this slow is an endpoint that is down. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** Ceiling for the whole exchange, body included; {@link BoundedHttp} explains why both exist. */
    private static final int DEADLINE_SECONDS = 20;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** Characters that a terminal reads as control rather than text. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\x{80}-\\x{9F}]");

    /**
     * How long a failed answer is remembered for a view of every connector.
     *
     * <p>Long enough that the views of every connector in one {@code models} command do not each
     * wait for an endpoint that is down. Short enough that a long-lived shell asks again after a
     * server starts.</p>
     */
    private static final long FAILURE_REMEMBERED_NANOS = Duration.ofMinutes(1).toNanos();

    /**
     * What each endpoint answered for each key.
     *
     * <p>Keyed by URL rather than by connector id because {@code baseURL} is a per-provider option:
     * the same connector pointed at a gateway and at the vendor is two different questions. Keyed
     * by the key as well, because the list is what that key may call, and another key can belong
     * to another plan or account. The key is held as a digest.</p>
     */
    private static final Map<String, Answer> ANSWERED = new ConcurrentHashMap<>();

    /** One answer: the models, and when a failed answer stops counting. */
    private record Answer(List<ModelsDevModel> models, long expiresAtNanos) {

        static Answer listed(List<ModelsDevModel> models) {
            return new Answer(List.copyOf(models), Long.MAX_VALUE);
        }

        static Answer failed() {
            return new Answer(List.of(), System.nanoTime() + FAILURE_REMEMBERED_NANOS);
        }

        boolean current() {
            return expiresAtNanos == Long.MAX_VALUE || System.nanoTime() - expiresAtNanos < 0;
        }
    }

    private ProviderModelListing() {
    }

    /** Forgets what every endpoint answered. Intended for tests. */
    public static void resetForTesting() {
        ANSWERED.clear();
    }

    /**
     * Whether this connector is one whose models can be listed live.
     *
     * <p>Only the OpenAI Chat Completions family publishes {@code /models} in a shape this can read.
     * The Anthropic, Google and Bedrock wires each list models differently or not at all, and every
     * provider on those three is in the catalog already, so there is nothing here for them to
     * gain.</p>
     *
     * @param connector the connector, or {@code null}
     * @return whether {@link #fetch} could return anything for it
     */
    public static boolean supports(ProviderConnector connector) {
        return connector != null
               && connector.getProtocol() == ConnectorProtocol.OPENAI_CHAT
               && baseUrl(connector, null) != null;
    }

    /**
     * Asks the provider for its models, for a view of the one connector the user named.
     *
     * <p>A remembered list is reused. A remembered failure is not: the user may have started the
     * server or fixed the key since, and names the connector to find that out.</p>
     *
     * @param connector the connector to ask
     * @param apiKey    the resolved credential, or {@code null} for an endpoint that needs none
     * @param options   per-provider options; only {@code baseURL} is read, to honour an endpoint the
     *                  user has overridden
     * @return the models it named, in the order it named them; empty when it could not be asked
     */
    public static List<ModelsDevModel> fetch(ProviderConnector connector, String apiKey,
                                             Map<String, String> options) {
        return fetch(connector, apiKey, options, false);
    }

    /**
     * Asks the provider for its models, for a view of every connector.
     *
     * <p>A failure from the last minute is reused as well as a list, so a view that covers every
     * connector does not wait again for each endpoint that is down.</p>
     *
     * @param connector the connector to ask
     * @param apiKey    the resolved credential, or {@code null} for an endpoint that needs none
     * @param options   per-provider options; only {@code baseURL} is read
     * @return the models it named; empty when it could not be asked
     */
    public static List<ModelsDevModel> fetchForEveryConnector(ProviderConnector connector,
                                                              String apiKey,
                                                              Map<String, String> options) {
        return fetch(connector, apiKey, options, true);
    }

    private static List<ModelsDevModel> fetch(ProviderConnector connector, String apiKey,
                                              Map<String, String> options,
                                              boolean reuseFailure) {
        if (!supports(connector)) {
            return List.of();
        }
        String url = baseUrl(connector, options) + "/models";
        String asked = url + "\n" + digest(apiKey);
        Answer remembered = ANSWERED.get(asked);
        if (remembered != null && remembered.current()
            && (reuseFailure || !remembered.models().isEmpty())) {
            return remembered.models();
        }
        List<ModelsDevModel> listed = ask(connector, apiKey, url);
        // An interrupted request says nothing about the endpoint, so it is not remembered.
        if (!Thread.currentThread().isInterrupted()) {
            ANSWERED.put(asked, listed.isEmpty() ? Answer.failed() : Answer.listed(listed));
        }
        return listed;
    }

    /**
     * Sends one listing request.
     *
     * @return the models named; empty when the request failed or named none
     */
    private static List<ModelsDevModel> ask(ProviderConnector connector, String apiKey,
                                            String url) {
        try {
            HttpRequest.Builder builder = HttpRequests.to(URI.create(url))
                                                     .timeout(REQUEST_TIMEOUT)
                                                     .header("Accept", "application/json")
                                                     .GET();
            for (Map.Entry<String, String> header : connector.getStaticHeaders().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            AuthHeaders.apply(builder, connector.getAuthScheme(), apiKey);

            HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
            HttpResponse<String> response = BoundedHttp.send(client, builder.build(), DEADLINE_SECONDS);
            if (response.statusCode() != 200) {
                // The body can quote the key back in an auth failure, so only the code is logged.
                // Debug rather than warn: every caller already has something to say about an empty
                // list, and the connector table asks each provider in turn -- so one endpoint that
                // is merely not running printed a warning above every listing the user asked for.
                LOG.debug("Model listing for " + connector.getId()
                          + " returned status " + response.statusCode());
                return List.of();
            }
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.debug("Model listing for " + connector.getId() + " failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Reads an OpenAI {@code /models} body.
     *
     * <p>Package-visible so the shapes a provider can answer with are tested without a server.</p>
     *
     * @param body the response body
     * @return one entry per model named, empty when the body names none
     */
    static List<ModelsDevModel> parse(String body) {
        List<ModelsDevModel> models = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return models;
        }
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!data.isArray()) {
                return models;
            }
            for (JsonNode entry : data) {
                ModelsDevModel model = toModel(entry);
                if (model != null) {
                    models.add(model);
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not read a model listing: " + e.getMessage());
        }
        return models;
    }

    /**
     * One entry of the listing, in the shape the rest of the tool reads models in.
     *
     * <p>{@code id} is the only field the protocol requires. {@code name} and {@code context_length}
     * are what gateways commonly add, and they are the two that make a listing readable rather than
     * a column of identifiers, so they are taken when they are offered and passed over when they are
     * not.</p>
     *
     * @param entry one element of the {@code data} array
     * @return the model, or {@code null} when the entry has no usable id
     */
    private static ModelsDevModel toModel(JsonNode entry) {
        String id = entry.path("id").asText(null);
        // An id with a control character in it is not one a request could name, and printing it
        // would hand the terminal an escape sequence.
        if (id == null || id.isBlank() || CONTROL.matcher(id).find()) {
            return null;
        }
        ModelsDevModel model = new ModelsDevModel();
        model.setId(id);
        model.setName(CONTROL.matcher(entry.path("name").asText(id)).replaceAll(""));

        long context = entry.path("context_length").asLong(0L);
        if (context > 0) {
            ModelsDevModel.Limit limit = new ModelsDevModel.Limit();
            limit.setContext(context);
            model.setLimit(limit);
        }
        return model;
    }

    /** A key as the cache holds it: a SHA-256 digest, or a fixed mark for no key. */
    private static String digest(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return "-";
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /** The endpoint to ask, preferring one the user has overridden. */
    private static String baseUrl(ProviderConnector connector, Map<String, String> options) {
        String overridden = options == null ? null : options.get("baseURL");
        String base = (overridden != null && !overridden.isBlank())
                ? overridden : connector.getDefaultBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
