package com.eonmux.cadetcoder.ai.catalog;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A model's input window, found by the model's own name rather than by the provider serving it.
 *
 * <h2>Why the provider is not always enough</h2>
 *
 * <p>The catalog is keyed by the provider that serves a model, and it does not list gateways.
 * {@link ModelCatalog#findServedModel} covers the ordinary shape of that: a gateway names the
 * vendor in the model id, as {@code deepseek/deepseek-v4-flash}, so the vendor is asked instead.
 * That fails when the prefix is a brand rather than a provider id. {@code commandcode} serving
 * {@code z-ai/glm-5.3-flash} is the case: no provider is called {@code z-ai}, so nothing was
 * found, and 65,536 tokens were assumed for a model with a million-token window. Every prompt was
 * then measured against a number fifteen times too small.</p>
 *
 * <p>The model's own name settles it. {@code glm-5.3-flash} is in the catalog once for every
 * provider serving it, under ids as unalike as {@code zai-org/GLM-5.3-Flash} and
 * {@code z-ai-glm-5-3}, and the window is published each of those times. A pair nobody publishes
 * is therefore still a model many providers describe.</p>
 *
 * <h2>Why the number published most often is the one taken</h2>
 *
 * <p>Providers disagree about the same model. Most of the disagreement is rounding -- 202,752
 * against 200,000 -- and some of it is a shortened variant served under the full model's name.
 * Counting is the one rule that needs no judgement about which provider to believe, and in the
 * catalog as it stands four listings in five already agree with it. A tie goes to the wider
 * window, for the reason {@link com.eonmux.cadetcoder.ai.ContextWindow#DEFAULT_TOKENS} is
 * generous: assuming too little refuses work the model would have taken and says nothing about
 * why, while assuming too much costs one refused request that names the real limit.</p>
 */
public final class ContextByModelName {

    /**
     * What the catalog publishes for one model name.
     *
     * @param name       the model's own name, lower-cased, with any vendor prefix removed
     * @param tokens     the window taken, being the one published most often
     * @param agreeing   how many listings publish that window
     * @param publishers how many listings publish a window under the name at all
     */
    public record Found(String name, int tokens, int agreeing, int publishers) {
    }

    private ContextByModelName() {
    }

    /**
     * Searches the loaded catalog.
     *
     * @param providerId the provider the request goes to
     * @param modelId    the model id as configured
     * @return what the catalog publishes, or {@code null} when no listing publishes a window
     */
    public static Found search(String providerId, String modelId) {
        return searchIn(ModelCatalog.getInstance().getProviders(), providerId, modelId);
    }

    /**
     * Searches a catalog given to it, so the rule can be exercised without loading one.
     *
     * @param catalog    providers keyed by id
     * @param providerId the provider the request goes to
     * @param modelId    the model id as configured
     * @return what the catalog publishes, or {@code null} when no listing publishes a window
     */
    static Found searchIn(Map<String, ModelsDevProvider> catalog, String providerId,
                          String modelId) {
        String name = bareName(modelId);
        if (catalog == null || name == null) {
            return null;
        }
        Found itsOwn = agreedOn(name, windowsNamed(providerAsked(catalog, providerId), name));
        if (itsOwn != null) {
            return itsOwn;
        }
        return agreedOn(name, catalog.values().stream()
                .flatMap(provider -> windowsNamed(provider, name).stream())
                .toList());
    }

    /**
     * @param catalog    providers keyed by id
     * @param providerId the provider the request goes to
     * @return its entry, or {@code null} when the catalog does not list it
     */
    private static ModelsDevProvider providerAsked(Map<String, ModelsDevProvider> catalog,
                                                   String providerId) {
        if (providerId == null) {
            return null;
        }
        ModelsDevProvider named = catalog.get(providerId);
        return named != null ? named : catalog.get(providerId.toLowerCase(Locale.ROOT));
    }

    /**
     * Every window one provider publishes under a model name.
     *
     * <p>A provider can list the same model more than once, as {@code zai-org/glm-4.7} beside
     * {@code TEE/glm-4.7}, so this is a list rather than one window.</p>
     *
     * @param provider the provider to read, which may be {@code null}
     * @param name     the model's own name, lower-cased
     * @return the windows, in the order the provider lists them
     */
    private static List<Integer> windowsNamed(ModelsDevProvider provider, String name) {
        if (provider == null || provider.getModels() == null) {
            return List.of();
        }
        return provider.getModels().entrySet().stream()
                .filter(listed -> name.equals(bareName(listed.getKey())))
                .map(listed -> windowOf(listed.getValue()))
                .filter(window -> window > 0)
                .toList();
    }

    /**
     * @param model the catalog entry to read, which may be {@code null}
     * @return the window it publishes, or {@code 0} when it publishes none
     */
    private static int windowOf(ModelsDevModel model) {
        if (model == null || model.getLimit() == null) {
            return 0;
        }
        long context = model.getLimit().getContext();
        // Clamped for the same reason ContextWindow clamps its own: a window wider than an int
        // would wrap into a negative budget rather than overflow visibly.
        return context <= 0 ? 0 : (int) Math.min(context, Integer.MAX_VALUE);
    }

    /**
     * The window published most often, with the wider one taken when two are published as often.
     *
     * @param name    the model's own name, lower-cased
     * @param windows every window published under it
     * @return what was agreed, or {@code null} when nothing was published
     */
    private static Found agreedOn(String name, List<Integer> windows) {
        if (windows.isEmpty()) {
            return null;
        }
        Map<Integer, Long> timesPublished = windows.stream()
                .collect(Collectors.groupingBy(window -> window, Collectors.counting()));
        Map.Entry<Integer, Long> agreed = timesPublished.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparingInt(Map.Entry::getKey))
                .orElseThrow();
        return new Found(name, agreed.getKey(), agreed.getValue().intValue(), windows.size());
    }

    /**
     * @param modelId the model id as configured
     * @return its own name, lower-cased and without any vendor prefix, or {@code null} when the id
     *         names no model
     */
    private static String bareName(String modelId) {
        if (modelId == null) {
            return null;
        }
        String name = modelId.substring(modelId.lastIndexOf('/') + 1)
                             .trim()
                             .toLowerCase(Locale.ROOT);
        return name.isEmpty() ? null : name;
    }
}
