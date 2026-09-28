package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.catalog.ContextByModelName;
import com.eonmux.cadetcoder.ai.catalog.ModelCatalog;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.CadetLogger;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many tokens the active model can take as input.
 *
 * <p>Distinct from {@code ai.maxTokens}, which is how many it may generate. One field was answering
 * both questions: the backends sent it as {@code max_tokens} while the prompt builders read it as
 * the space available for the prompt. They are unrelated numbers -- a model that accepts 200,000
 * tokens of input and returns 8,000 of output is ordinary -- so tying them together meant the prompt
 * budget was whatever the completion limit happened to be. At the default of 4,096 that left roughly
 * 14,000 characters for an entire prompt.</p>
 *
 * <h2>Where the number comes from</h2>
 *
 * <p>Preferring, in order:</p>
 * <ol>
 *   <li>A window given by hand for this provider and model, in {@code ai.modelContextTokens}
 *       ({@code models context}). The most specific instruction there is.</li>
 *   <li>{@code ai.contextTokens}, when set. An explicit setting is an explicit instruction.</li>
 *   <li>The active model's published context length from the models.dev catalog, asked of the
 *       provider the request goes to and then of the vendor its model id names. This is the real
 *       number, it is already loaded, and it differs by orders of magnitude between models -- so a
 *       single configured default would be wrong for almost every one of them.</li>
 *   <li>The window the catalog publishes for the model's own name, wherever it appears. A gateway
 *       reselling a model under a brand rather than a provider id is published nowhere as a pair,
 *       and is still a model many providers describe. See {@link ContextByModelName}.</li>
 *   <li>{@link #DEFAULT_TOKENS}, for a local or otherwise uncatalogued endpoint, said out loud
 *       once rather than applied quietly.</li>
 * </ol>
 */
public final class ContextWindow {

    /**
     * Context length assumed when nothing better is known.
     *
     * <p>Reached only for an endpoint the catalog does not describe, which in practice means a
     * local server or a private model behind a gateway.</p>
     *
     * <h2>Why the assumption is generous rather than cautious</h2>
     *
     * <p>It was 8,192, on the reasoning that a small window cannot be overflowed. That is the wrong
     * way round for the cost involved. Assuming too little refuses work the model would have taken:
     * an ordinary first request against an uncatalogued model was answered "cannot make this
     * request fit", with the run over before it began and nothing wrong with the model. Assuming
     * too much sends a prompt the provider refuses, which is one failed request that says what the
     * real limit is. Sixty-four thousand is a window current models meet or exceed, and the
     * assumption is announced when it is used, so the setting that replaces it is named before it
     * costs anything.</p>
     */
    public static final int DEFAULT_TOKENS = 65_536;

    private static final CadetLogger LOG = CadetLogger.getLogger(ContextWindow.class);

    /** Provider and model pairs whose window has been reported, so it is said once each. */
    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();

    private ContextWindow() {
    }

    /**
     * An input window and where the number came from.
     *
     * <p>The source travels with the number because the one message that reports a prompt too big
     * to send is useless without it: a transcript that has genuinely outgrown a real window and a
     * window that was assumed look identical from the outside, and only one of them is fixed by
     * giving the model its window with {@code models context}.</p>
     *
     * @param tokens how many tokens of input the model is taken to accept
     * @param source where that figure came from, in words meant for the person reading it
     */
    public record Window(int tokens, String source) {
    }

    /**
     * @return the input budget in tokens; always positive
     */
    public static int tokens() {
        return current().tokens();
    }

    /**
     * @return the input window, and where the figure came from
     */
    public static Window current() {
        try {
            Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
            if (ai == null) {
                return assumed(null, null);
            }
            Integer given = ai.getModelContextTokens().get(keyFor(ai.getProvider(), ai.getModel()));
            if (given != null && given > 0) {
                return new Window(given, "set for " + named(ai.getProvider(), ai.getModel())
                                         + " with models context");
            }
            if (ai.getContextTokens() > 0) {
                return new Window(ai.getContextTokens(), "set by ai.contextTokens");
            }
            return resolvedFor(ai.getProvider(), ai.getModel());
        } catch (RuntimeException e) {
            // Reading configuration or the catalog must not be able to fail a request.
            return assumed(null, null);
        }
    }

    /**
     * How a window given by hand is filed.
     *
     * @param providerId the provider the model is reached through
     * @param modelId    the model
     * @return the key in {@code ai.modelContextTokens}
     */
    public static String keyFor(String providerId, String modelId) {
        return providerId + "/" + modelId;
    }

    /**
     * Whether the catalog publishes a window for this model, under the pair or under its name.
     *
     * <p>Asked quietly: when the answer is no, the caller is about to ask the user for the figure,
     * and the warning that a window is being assumed would announce a guess nobody will make.</p>
     *
     * @param providerId the provider the model is reached through
     * @param modelId    the model
     * @return whether a published figure exists
     */
    public static boolean isPublished(String providerId, String modelId) {
        try {
            if (publishedContext(providerId, modelId) > 0) {
                return true;
            }
            ContextByModelName.Found found = ContextByModelName.search(providerId, modelId);
            return found != null && found.tokens() > 0;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /**
     * The window for a provider and model, when no setting decides it.
     *
     * @param providerId the provider the request goes to
     * @param modelId    the model being asked
     * @return the window, and where the figure came from
     */
    static Window resolvedFor(String providerId, String modelId) {
        int published = publishedContext(providerId, modelId);
        if (published > 0) {
            return new Window(published, "published for " + named(providerId, modelId));
        }
        Window byName = publishedUnderTheModelsOwnName(providerId, modelId);
        if (byName != null) {
            return byName;
        }
        return assumed(providerId, modelId);
    }

    /**
     * The window the catalog publishes for this model under its own name.
     *
     * <p>Reached when nothing is published for the pair itself, which happens whenever a gateway
     * resells a model under an id whose prefix is a brand rather than a provider. The number is
     * not the serving provider's own word, so the source says how widely it is agreed and the
     * reader is left able to judge it.</p>
     *
     * @param providerId the provider the request goes to
     * @param modelId    the model being asked
     * @return the window, or {@code null} when no provider publishes one under that name
     */
    private static Window publishedUnderTheModelsOwnName(String providerId, String modelId) {
        ContextByModelName.Found found;
        try {
            found = ContextByModelName.search(providerId, modelId);
        } catch (RuntimeException e) {
            // Reading the catalog must not be able to fail a request.
            return null;
        }
        if (found == null || found.tokens() <= 0) {
            return null;
        }
        announceWhoseWindowItIs(providerId, modelId, found);
        return new Window(found.tokens(), sourceOf(found));
    }

    /**
     * @param found what the catalog publishes for the model's name
     * @return where the figure came from, in words meant for the person reading it
     */
    private static String sourceOf(ContextByModelName.Found found) {
        if (found.publishers() == 1) {
            return "published for " + found.name() + " by the one provider that lists it";
        }
        return "published for " + found.name() + " by " + found.agreeing() + " of the "
               + found.publishers() + " providers that list it";
    }

    /**
     * Says, once per model, that the window came from the catalog at large.
     *
     * @param providerId the provider being called
     * @param modelId    the model being asked
     * @param found      what the catalog publishes for the model's name
     */
    private static void announceWhoseWindowItIs(String providerId, String modelId,
                                                ContextByModelName.Found found) {
        String pair = named(providerId, modelId);
        if (!ANNOUNCED.add(pair.toLowerCase(Locale.ROOT))) {
            return;
        }
        LOG.info("No context length is published for " + pair + " itself, so the " + found.tokens()
                 + " tokens published for " + found.name() + " elsewhere in the catalog is used."
                 + " Run 'models context <tokens>' if requests are refused for being too large.");
    }

    /** The fallback, announced the first time each model reaches it. */
    private static Window assumed(String providerId, String modelId) {
        announceTheAssumption(providerId, modelId);
        return new Window(DEFAULT_TOKENS,
                          "assumed: no window is published for " + named(providerId, modelId));
    }

    /** @return the provider and model as one name, or a stand-in when neither is configured */
    private static String named(String providerId, String modelId) {
        if (providerId == null && modelId == null) {
            return "this model";
        }
        return providerId + "/" + modelId;
    }

    /**
     * Says, once per model, that an input window is being assumed because none is published.
     *
     * <p>Every prompt is measured against this number, so assuming a small one silently shrinks
     * what may be sent: a model with a million-token window was worked to eight thousand, and the
     * run ended at "cannot make this request fit" with nothing to say which number was wrong. The
     * line names the setting that replaces the guess.</p>
     *
     * @param providerId the provider being called
     * @param modelId    the model being asked
     */
    private static void announceTheAssumption(String providerId, String modelId) {
        String pair = named(providerId, modelId);
        if (!ANNOUNCED.add(pair.toLowerCase(Locale.ROOT))) {
            return;
        }
        LOG.warn("No published context length is known for " + pair + ", so " + DEFAULT_TOKENS
                 + " tokens is being assumed. Run 'models context <tokens>' with the window this"
                 + " model really has if requests are refused for being too large.");
    }

    /** Forgets which windows have been reported. Intended for tests. */
    static void resetForTesting() {
        ANNOUNCED.clear();
    }

    /**
     * The context length the catalog publishes for a model.
     *
     * @param providerId the active connector id
     * @param modelId    the active model id
     * @return the context length in tokens, or {@code 0} when it is not known
     */
    static int publishedContext(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) {
            return 0;
        }
        try {
            ModelsDevModel model = ModelCatalog.getInstance().findServedModel(providerId, modelId);
            if (model == null || model.getLimit() == null) {
                return 0;
            }
            long context = model.getLimit().getContext();
            // Catalogued windows already exceed Integer.MAX_VALUE for no model, but clamping keeps
            // the arithmetic downstream honest rather than wrapping into a negative budget.
            return context <= 0 ? 0 : (int) Math.min(context, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
