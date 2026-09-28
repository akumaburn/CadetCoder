package com.eonmux.cadetcoder.ai.catalog;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding a model's input window by the model's own name, across every provider that lists it.
 *
 * <h2>The defect</h2>
 *
 * <p>The catalog is keyed by the provider serving a model, and a gateway that resells other
 * vendors' models is not in it. {@code ModelCatalog.findServedModel} covers the ordinary shape of
 * that, {@code vendor/model}, by asking the vendor named in the id. It cannot cover
 * {@code commandcode} serving {@code z-ai/glm-5.3-flash}, because {@code z-ai} is a vendor's brand
 * and not a provider id: no provider of that name exists, so the lookup missed and 65,536 tokens
 * were assumed for a model with a million-token window. Every prompt was then measured against a
 * number fifteen times too small.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the model's own name is enough. It appears in the catalog once per provider serving it,
 * so the window is published many times over even when the pair asked about is published nowhere.
 * Providers disagree about the same model, usually by rounding and sometimes by serving a
 * shortened variant, so the number published most often is the one taken.</p>
 */
class AwindowIsTakenFromWhoeverPublishesTheModelTest {

    /** @return a provider listing each given model id at the window beside it */
    private static ModelsDevProvider serving(Object... idsAndWindows) {
        ModelsDevProvider provider = new ModelsDevProvider();
        Map<String, ModelsDevModel> models = new LinkedHashMap<>();
        for (int i = 0; i < idsAndWindows.length; i += 2) {
            ModelsDevModel.Limit limit = new ModelsDevModel.Limit();
            limit.setContext(((Number) idsAndWindows[i + 1]).longValue());
            ModelsDevModel model = new ModelsDevModel();
            model.setLimit(limit);
            models.put((String) idsAndWindows[i], model);
        }
        provider.setModels(models);
        return provider;
    }

    /** @return a catalog of the given providers, keyed in the order they are named */
    private static Map<String, ModelsDevProvider> catalogOf(Object... idsAndProviders) {
        Map<String, ModelsDevProvider> catalog = new LinkedHashMap<>();
        for (int i = 0; i < idsAndProviders.length; i += 2) {
            catalog.put((String) idsAndProviders[i], (ModelsDevProvider) idsAndProviders[i + 1]);
        }
        return catalog;
    }

    @Test
    void amodelTheProviderBeingCalledDoesNotListIsFoundUnderItsOwnName() {
        Map<String, ModelsDevProvider> catalog = catalogOf(
                "zai", serving("glm-5.3-flash", 1_000_000),
                "openrouter", serving("z-ai/glm-5.3-flash", 1_000_000));

        ContextByModelName.Found found =
                ContextByModelName.searchIn(catalog, "commandcode", "z-ai/glm-5.3-flash");

        assertThat(found).isNotNull();
        assertThat(found.tokens()).isEqualTo(1_000_000);
        assertThat(found.name()).isEqualTo("glm-5.3-flash");
    }

    @Test
    void thewindowMostProvidersPublishIsTheOneTaken() {
        Map<String, ModelsDevProvider> catalog = catalogOf(
                "one", serving("vendor/a-model", 200_000),
                "two", serving("a-model", 200_000),
                "three", serving("other/a-model", 200_000),
                "four", serving("a-model", 8_192));

        ContextByModelName.Found found =
                ContextByModelName.searchIn(catalog, "a-gateway", "a-model");

        assertThat(found.tokens()).isEqualTo(200_000);
        assertThat(found.agreeing()).isEqualTo(3);
        assertThat(found.publishers()).isEqualTo(4);
    }

    /**
     * Two windows published as often as each other: the wider one is taken.
     *
     * <p>For the reason {@code ContextWindow.DEFAULT_TOKENS} is generous rather than cautious.
     * Assuming too little refuses work the model would have taken, and says nothing about why.
     * Assuming too much costs one refused request that names the real limit.</p>
     */
    @Test
    void atieIsBrokenTowardsTheWiderWindow() {
        Map<String, ModelsDevProvider> catalog = catalogOf(
                "one", serving("a-model", 200_000),
                "two", serving("a-model", 1_000_000),
                "three", serving("a-model", 200_000),
                "four", serving("a-model", 1_000_000));

        assertThat(ContextByModelName.searchIn(catalog, "a-gateway", "a-model").tokens())
                .isEqualTo(1_000_000);
    }

    /**
     * The provider the request goes to is believed over every other, when it publishes anything.
     *
     * <p>It is the one serving the request, so its number is the one that will refuse the prompt.
     * A consensus of providers nobody is calling is only worth having when it is all there is.</p>
     */
    @Test
    void theproviderBeingCalledIsBelievedOverTheRest() {
        Map<String, ModelsDevProvider> catalog = catalogOf(
                "zai", serving("glm-5.2", 200_000),
                "one", serving("z-ai/glm-5.2", 1_000_000),
                "two", serving("z-ai/glm-5.2", 1_000_000),
                "three", serving("z-ai/glm-5.2", 1_000_000));

        ContextByModelName.Found found =
                ContextByModelName.searchIn(catalog, "zai", "z-ai/glm-5.2");

        assertThat(found.tokens()).isEqualTo(200_000);
        assertThat(found.publishers()).isEqualTo(1);
    }

    @Test
    void thespellingOfTheModelIsNotWhatDecidesIt() {
        Map<String, ModelsDevProvider> catalog =
                catalogOf("one", serving("zai-org/GLM-5.3-Flash", 1_000_000));

        assertThat(ContextByModelName.searchIn(catalog, "a-gateway", "z-ai/glm-5.3-flash").tokens())
                .isEqualTo(1_000_000);
    }

    @Test
    void amodelNobodyListsIsNotFound() {
        Map<String, ModelsDevProvider> catalog =
                catalogOf("one", serving("a-model", 200_000));

        assertThat(ContextByModelName.searchIn(catalog, "a-gateway", "acme/private-model-9"))
                .isNull();
    }

    /** A listing with no window published is no evidence of one, so it is not counted. */
    @Test
    void alistingWithoutApublishedWindowIsNotCounted() {
        Map<String, ModelsDevProvider> catalog = catalogOf(
                "one", serving("a-model", 0),
                "two", serving("a-model", 200_000));

        ContextByModelName.Found found =
                ContextByModelName.searchIn(catalog, "a-gateway", "a-model");

        assertThat(found.tokens()).isEqualTo(200_000);
        assertThat(found.publishers())
                .as("the unpublished listing is not one of them").isEqualTo(1);
    }

    @Test
    void anemptyCatalogAndAnemptyNameAreBothAnswerable() {
        assertThat(ContextByModelName.searchIn(catalogOf(), "a-gateway", "a-model")).isNull();
        assertThat(ContextByModelName.searchIn(null, "a-gateway", "a-model")).isNull();
        assertThat(ContextByModelName.searchIn(catalogOf(), "a-gateway", null)).isNull();
        assertThat(ContextByModelName.searchIn(catalogOf(), null, "vendor/")).isNull();
    }
}
