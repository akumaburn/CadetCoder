package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.catalog.ModelCatalog;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.eonmux.cadetcoder.net.OutputBudget;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many tokens the active model can produce as output.
 *
 * <p>The counterpart to {@link ContextWindow}, which is how many it accepts as input. Two callers
 * need this number even though no ceiling is sent on the wire by default.</p>
 *
 * <ol>
 *   <li>The compactor, which holds room back in the input window for a reply that has not been
 *       written yet. Hold back nothing and the prompt is free to fill the whole window, which leaves
 *       the model no room to answer in.</li>
 *   <li>The Anthropic Messages wire, which requires {@code max_tokens} and so cannot be told that no
 *       ceiling was asked for.</li>
 * </ol>
 *
 * <h2>Where the number comes from</h2>
 *
 * <p>Preferring, in order:</p>
 * <ol>
 *   <li>{@code ai.maxTokens}, when a ceiling was asked for. An explicit setting is an explicit
 *       instruction, and it is also the number that will be sent.</li>
 *   <li>The model's published output limit from the models.dev catalog. This is the real ceiling the
 *       provider enforces, it is already loaded, and it differs by more than a factor of ten between
 *       models.</li>
 *   <li>{@link #DEFAULT_TOKENS}, for a local or otherwise uncatalogued endpoint.</li>
 * </ol>
 *
 * <p>The wire that has to send a number asks {@link #forRequiredCeiling} instead, which follows the
 * same order and then asks the vendor whose protocol it speaks about the same model, because a
 * gateway reselling that model is a provider the catalog has never heard of.</p>
 */
public final class OutputWindow {

    /**
     * Output limit assumed when nothing better is known.
     *
     * <p>Chosen as the largest value no current API refuses. It is the whole allowance of the
     * smallest Claude models, so the Anthropic wire can send it to any of them without earning a
     * 400, and it is well under what every other provider permits.</p>
     */
    public static final int DEFAULT_TOKENS = 4096;

    private static final CadetLogger LOG = CadetLogger.getLogger(OutputWindow.class);

    /** Provider and model pairs the assumption has already been announced for, so it is said once. */
    private static final Set<String> ASSUMED = ConcurrentHashMap.newKeySet();

    private OutputWindow() {
    }

    /**
     * @return the output budget of the configured model, in tokens; always positive
     */
    public static int tokens() {
        try {
            Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
            if (ai == null) {
                return DEFAULT_TOKENS;
            }
            if (OutputBudget.isLimited(ai.getMaxTokens())) {
                return ai.getMaxTokens();
            }
            return forModel(ai.getProvider(), ai.getModel());
        } catch (RuntimeException e) {
            // Reading configuration or the catalog must not be able to fail a request.
            return DEFAULT_TOKENS;
        }
    }

    /**
     * The output budget of one named model, ignoring what is configured.
     *
     * @param providerId the connector id
     * @param modelId    the model id
     * @return the published limit, or {@link #DEFAULT_TOKENS} when the catalog does not know it
     */
    public static int forModel(String providerId, String modelId) {
        int published = publishedOutput(providerId, modelId);
        return published > 0 ? published : DEFAULT_TOKENS;
    }

    /**
     * The ceiling to send on a wire that cannot be told "no limit".
     *
     * <h2>Why this is not simply {@link #forModel}</h2>
     *
     * <p>{@link #forModel} answers from the catalog alone, and the catalog is keyed by the provider
     * the request is going to. A gateway that resells Claude is its own provider -- the id is
     * {@code commandcode}, not {@code anthropic}, deliberately, because that is what a failure has
     * to name and what the cache latch is keyed on -- and models.dev does not list the gateway. So
     * the lookup missed for every request such a gateway ever made, and the Anthropic wire, which
     * must send a number, sent {@value #DEFAULT_TOKENS} to a model that can write sixteen times
     * that. A user who asked for no ceiling got a small one, and long answers stopped mid-sentence
     * with nothing on screen to say why.</p>
     *
     * <p>The model is the same model whichever door it is reached through, so when the gateway is
     * not in the catalog the vendor whose protocol this wire speaks is asked about the same model
     * id -- including the {@code vendor/model} spelling gateways commonly use. Only when nothing at
     * all is known does an assumption get made, and then it is said out loud rather than applied
     * quietly.</p>
     *
     * @param providerId the connector the request is going to
     * @param modelId    the model being asked
     * @param vendorId   the catalog id of the vendor whose protocol this wire speaks, asked when the
     *                   connector itself is not catalogued
     * @return the ceiling to send; always positive
     */
    public static int forRequiredCeiling(String providerId, String modelId, String vendorId) {
        // An explicit setting is an explicit instruction, and it is the number that would be sent
        // anyway if the caller had passed it down. Asked first so that a user who has named a
        // ceiling is never overruled by a catalog entry.
        int configured = configuredCeiling();
        if (configured > 0) {
            return configured;
        }
        int published = publishedOutput(providerId, modelId);
        if (published > 0) {
            return published;
        }
        published = publishedOutput(vendorId, modelId);
        if (published > 0) {
            return published;
        }
        published = publishedOutput(vendorId, bareModelId(modelId));
        if (published > 0) {
            return published;
        }
        announceTheAssumption(providerId, modelId);
        return DEFAULT_TOKENS;
    }

    /**
     * @return {@code ai.maxTokens} when a ceiling was asked for, otherwise {@code 0}
     */
    private static int configuredCeiling() {
        try {
            Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
            return ai != null && OutputBudget.isLimited(ai.getMaxTokens()) ? ai.getMaxTokens() : 0;
        } catch (RuntimeException unreadable) {
            // Reading configuration must not be able to fail a request.
            return 0;
        }
    }

    /**
     * The model id with any {@code vendor/} qualifier removed.
     *
     * <p>A gateway lists the same model as {@code anthropic/claude-sonnet-4-5} where the vendor
     * lists it as {@code claude-sonnet-4-5}. The qualifier says which vendor, which is the thing
     * already known by the time this is asked.</p>
     *
     * @param modelId the model id as configured
     * @return the part after the last slash, or {@code modelId} when it carries none
     */
    private static String bareModelId(String modelId) {
        if (modelId == null) {
            return null;
        }
        int slash = modelId.lastIndexOf('/');
        return slash < 0 || slash == modelId.length() - 1 ? modelId : modelId.substring(slash + 1);
    }

    /**
     * Says, once per model, that a ceiling is being assumed because none is known.
     *
     * <p>The wire requires a number and there is no honest one to send, so one is chosen -- but a
     * ceiling nobody asked for and nobody was told about is indistinguishable, from the outside,
     * from a model that simply stops writing. The line names the setting that replaces the
     * guess.</p>
     *
     * @param providerId the connector being called
     * @param modelId    the model being asked
     */
    private static void announceTheAssumption(String providerId, String modelId) {
        if (!ASSUMED.add((providerId + "/" + modelId).toLowerCase(Locale.ROOT))) {
            return;
        }
        LOG.warn("No published output limit is known for " + providerId + "/" + modelId
                 + ", and this provider's API requires one, so " + DEFAULT_TOKENS
                 + " is being sent. Set ai.maxTokens to the ceiling this model really has if its"
                 + " answers are being cut short.");
    }

    /** Forgets which assumptions have been announced. Intended for tests. */
    static void resetForTesting() {
        ASSUMED.clear();
    }

    /**
     * The output limit the catalog publishes for a model.
     *
     * @param providerId the connector id
     * @param modelId    the model id
     * @return the limit in tokens, or {@code 0} when it is not known
     */
    static int publishedOutput(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) {
            return 0;
        }
        try {
            ModelsDevModel model = ModelCatalog.getInstance().findServedModel(providerId, modelId);
            if (model == null || model.getLimit() == null) {
                return 0;
            }
            long output = model.getLimit().getOutput();
            // Clamped for the same reason ContextWindow clamps its own: the arithmetic downstream
            // subtracts this from a window, and a wrapped negative would read as extra room.
            return output <= 0 ? 0 : (int) Math.min(output, Integer.MAX_VALUE);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
