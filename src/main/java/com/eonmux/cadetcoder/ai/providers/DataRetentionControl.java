package com.eonmux.cadetcoder.ai.providers;

import java.util.Map;

/**
 * What a provider can be asked to do about keeping a request, and how to ask it.
 *
 * <h2>Why the promises are not all the same promise</h2>
 *
 * <p>"Zero data retention" is a phrase three providers use for three different things, and the
 * differences are the whole point of the setting. Command Code refuses the request outright rather
 * than route it to an upstream that would retain it. OpenRouter routes only to endpoints that have
 * agreed not to retain it. OpenAI offers no per-request control at all: its zero retention is
 * arranged for an organisation and applied by OpenAI, and the only thing a request can say is that
 * the exchange should not be stored for later retrieval -- abuse-monitoring logs are kept for thirty
 * days regardless, and no parameter turns that off.</p>
 *
 * <p>So a connector records which promise it can actually make. Flattening the three into one
 * boolean would let the tool report a guarantee that two of the three providers never gave, which is
 * worse than reporting nothing: a privacy guarantee believed is acted on, and one that is merely
 * believed is not a guarantee.</p>
 *
 * <h2>Why both headers and body fields</h2>
 *
 * <p>Providers put the control in different places. Command Code reads a header; OpenRouter and
 * OpenAI read fields in the request body. Both are carried here so the connector declares the
 * control once and neither wire has to know which form a given provider chose.</p>
 *
 * @param promise what this provider is able to promise
 * @param headers headers that ask for it, empty when it is asked for in the body or not at all
 * @param body    request-body fields that ask for it, empty when it is asked for in a header or not
 *                at all; values may be nested maps, which are serialised as nested JSON objects
 */
public record DataRetentionControl(Promise promise,
                                   Map<String, String> headers,
                                   Map<String, Object> body) {

    /** How much a provider is able to promise about not keeping a request. */
    public enum Promise {

        /** Nothing can be asked of it; whatever it does by default is what happens. */
        NONE,

        /**
         * It can be told not to store the exchange, while keeping operational logs it exposes no
         * control over. Less than zero retention, and named separately so it is not reported as
         * though it were.
         */
        NO_STORAGE,

        /**
         * It will not retain the request: it either refuses to serve it, or serves it only from
         * somewhere that has agreed not to keep it.
         */
        ZERO_RETENTION
    }

    public DataRetentionControl {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body    = body    == null ? Map.of() : Map.copyOf(body);
        promise = promise == null ? Promise.NONE : promise;
    }

    /** A provider with no control to offer, which is most of them. */
    public static DataRetentionControl none() {
        return new DataRetentionControl(Promise.NONE, Map.of(), Map.of());
    }

    /**
     * A provider that reads the request for one header.
     *
     * @param promise what honouring the header amounts to
     * @param name    the header name
     * @param value   the header value
     */
    public static DataRetentionControl header(Promise promise, String name, String value) {
        return new DataRetentionControl(promise, Map.of(name, value), Map.of());
    }

    /**
     * A provider that reads the request body.
     *
     * @param promise what honouring the fields amounts to
     * @param fields  the fields to merge into the request body
     */
    public static DataRetentionControl body(Promise promise, Map<String, Object> fields) {
        return new DataRetentionControl(promise, Map.of(), fields);
    }

    /** Whether there is anything at all to ask this provider. */
    public boolean isAvailable() {
        return promise != Promise.NONE;
    }
}
