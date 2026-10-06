package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.catalog.ModelCatalog;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.ai.catalog.ModelsDevProvider;
import com.eonmux.cadetcoder.ai.catalog.ProviderModelListing;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

/**
 * A GLM connector offers the models its platform says the key may call.
 *
 * <h2>The defect</h2>
 *
 * <p>The connector's models came from the models.dev catalog alone. Z.ai's {@code /models} answered
 * eleven models for a GLM Coding Plan key while the catalog's entry for the plan named seven, and
 * left out {@code glm-5.1}, which the plan serves. {@code models zai-coding-plan} offered the
 * catalog's seven.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That with a key the platform's list decides which models are offered, in the platform's order;
 * that each is described from the catalog, from a related GLM entry when the plan's own entry does
 * not name it; that a model the catalog does not describe at all is still offered by its id; and
 * that without a key the platform is not asked and the catalog answers as before.</p>
 */
public class AplanListsTheModelsItCoversTest {

    private static final String PLAN = "zai-coding-plan";

    private final ProviderConnector connector = ProviderRegistry.getInstance().get(PLAN);

    private TestOutputCapture output;
    private String            savedKey;

    @BeforeEach
    public void setUp() {
        ProviderModelListing.resetForTesting();
        output = new TestOutputCapture();
        output.startCapture();
        savedKey = keys().get(PLAN);
    }

    @AfterEach
    public void tearDown() {
        output.stopCapture();
        ProviderModelListing.resetForTesting();
        if (savedKey == null) {
            keys().remove(PLAN);
        } else {
            keys().put(PLAN, savedKey);
        }
    }

    private static Map<String, String> keys() {
        return ConfigManager.getInstance().getConfig().getAi().getProviderApiKeys();
    }

    @Test
    public void withAKeyThePlatformsListDecidesAndTheCatalogDescribes() {
        keys().put(PLAN, "0f1e2d3c4b5a69788796a5b4c3d2e1f0.Ab12Cd34Ef56Gh78");

        try (MockedStatic<ModelCatalog> catalog = catalogOf(Map.of(
                     PLAN, provider(model("glm-4.7", "GLM-4.7", 204_800)),
                     "zai", provider(model("glm-5.1", "GLM-5.1", 200_000))));
             MockedStatic<ProviderModelListing> listing = listingOf("glm-5.1", "glm-4.7", "glm-9")) {

            List<ModelsDevModel> models = ConnectorSupport.models(connector);

            assertThat(models).extracting(ModelsDevModel::getId)
                    .as("the platform's models, in the platform's order")
                    .containsExactly("glm-5.1", "glm-4.7", "glm-9");
            assertThat(models.get(0).getName())
                    .as("described from the platform's entry, since the plan's does not name it")
                    .isEqualTo("GLM-5.1");
            assertThat(models.get(0).getLimit().getContext()).isEqualTo(200_000L);
            assertThat(models.get(1).getName()).isEqualTo("GLM-4.7");
            assertThat(models.get(2).getName())
                    .as("a model nobody describes is still offered, by its id")
                    .isEqualTo("glm-9");
        }
    }

    @Test
    public void withoutAKeyThePlatformIsNotAskedAndTheCatalogAnswers() {
        keys().remove(PLAN);

        try (MockedStatic<ModelCatalog> catalog = catalogOf(Map.of(
                     PLAN, provider(model("glm-4.7", "GLM-4.7", 204_800))));
             MockedStatic<ProviderModelListing> listing = listingOf("glm-5.1")) {

            List<ModelsDevModel> models = ConnectorSupport.models(connector);

            // The environment may hold a key on a developer's machine, so the assertion is on what
            // was offered only when nothing was asked.
            if (System.getenv("ZAI_API_KEY") == null && System.getenv("ZHIPU_API_KEY") == null) {
                listing.verify(() -> ProviderModelListing.fetch(any(), any(), any()), never());
                assertThat(models).extracting(ModelsDevModel::getId).containsExactly("glm-4.7");
            }
        }
    }

    @Test
    public void aPlansModelIsDescribedByItsPlatformsEntryWhereverItIsLookedUp() {
        try (MockedStatic<ModelCatalog> catalog = catalogOf(Map.of(
                     PLAN, provider(model("glm-4.7", "GLM-4.7", 204_800)),
                     "zai", provider(model("glm-5.1", "GLM-5.1", 200_000))))) {

            // The context window and the image check ask this, not the model list.
            assertThat(ModelCatalog.getInstance().findServedModel(PLAN, "glm-5.1"))
                    .isNotNull()
                    .satisfies(m -> assertThat(m.getLimit().getContext()).isEqualTo(200_000L));
            assertThat(ModelCatalog.getInstance().findServedModel("groq", "glm-5.1"))
                    .as("a connector with no related entries is not described by somebody else's")
                    .isNull();
        }
    }

    // ------------------------------------------------------------------ fixtures

    /** Replaces the catalog with one holding exactly these providers. */
    private static MockedStatic<ModelCatalog> catalogOf(Map<String, ModelsDevProvider> providers) {
        ModelCatalog real = ModelCatalog.getInstance();
        ModelCatalog fake = spy(real);
        Map<String, ModelsDevProvider> keyed = new LinkedHashMap<>();
        providers.forEach((id, p) -> {
            p.setId(id);
            keyed.put(id, p);
        });
        doReturn(keyed).when(fake).getProviders();
        MockedStatic<ModelCatalog> mock = mockStatic(ModelCatalog.class, Mockito.CALLS_REAL_METHODS);
        mock.when(ModelCatalog::getInstance).thenReturn(fake);
        return mock;
    }

    /** Has every provider answer its model list with these ids. */
    private static MockedStatic<ProviderModelListing> listingOf(String... ids) {
        MockedStatic<ProviderModelListing> mock = mockStatic(ProviderModelListing.class);
        mock.when(() -> ProviderModelListing.supports(any(ProviderConnector.class))).thenReturn(true);
        mock.when(() -> ProviderModelListing.fetch(eq(ProviderRegistry.getInstance().get(PLAN)),
                                                   any(), any()))
            .thenReturn(java.util.Arrays.stream(ids).map(id -> model(id, id, 0)).toList());
        return mock;
    }

    private static ModelsDevProvider provider(ModelsDevModel... models) {
        ModelsDevProvider provider = new ModelsDevProvider();
        Map<String, ModelsDevModel> byId = new LinkedHashMap<>();
        for (ModelsDevModel m : models) {
            byId.put(m.getId(), m);
        }
        provider.setModels(byId);
        return provider;
    }

    private static ModelsDevModel model(String id, String name, long context) {
        ModelsDevModel model = new ModelsDevModel();
        model.setId(id);
        model.setName(name);
        if (context > 0) {
            ModelsDevModel.Limit limit = new ModelsDevModel.Limit();
            limit.setContext(context);
            model.setLimit(limit);
        }
        return model;
    }
}
