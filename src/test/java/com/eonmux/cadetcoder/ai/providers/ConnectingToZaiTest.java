package com.eonmux.cadetcoder.ai.providers;

import com.eonmux.cadetcoder.ai.catalog.ModelsDevProvider;
import com.eonmux.cadetcoder.net.OpenAICompatibleBackend;
import com.eonmux.cadetcoder.security.SecretRedactor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Z.ai and Zhipu AI connectors, and the facts about them that have to agree with the platform.
 *
 * <h2>What is locked here</h2>
 *
 * <p>That all four are registered under the ids the models.dev catalog uses, because the catalog is
 * where {@code models} reads their GLM models from and a different id would list none; that each
 * points at the {@code /api/paas/v4} or {@code /api/coding/paas/v4} root of its host, since the
 * coding-plan root is what a GLM Coding Plan pays for; that they speak OpenAI Chat Completions with
 * a bearer key; that they read the environment variables the platforms document; and that a key of
 * their shape is redacted.</p>
 */
public class ConnectingToZaiTest {

    private final ProviderRegistry registry = ProviderRegistry.getInstance();

    /** The models.dev snapshot bundled in the jar: what the tool falls back to with no network. */
    private static Map<String, ModelsDevProvider> catalog;

    @BeforeClass
    public static void readBundledCatalog() throws Exception {
        try (InputStream in = ConnectingToZaiTest.class
                .getResourceAsStream("/catalog/models-dev-snapshot.json")) {
            assertThat(in).as("bundled snapshot resource present").isNotNull();
            catalog = new ObjectMapper().readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    new TypeReference<LinkedHashMap<String, ModelsDevProvider>>() {});
        }
    }

    @Test
    public void allFourConnectorsAreRegisteredUnderTheCatalogsIds() {
        for (String id : new String[]{"zai", "zai-coding-plan", "zhipuai", "zhipuai-coding-plan"}) {
            assertThat(registry.has(id)).as(id).isTrue();
            assertThat(catalog.get(id))
                    .as("models.dev lists the GLM models of %s under this id", id)
                    .isNotNull();
            assertThat(catalog.get(id).getModels()).as(id).isNotEmpty();
        }
        assertThat(registry.get("zai").getDisplayName()).isEqualTo("Z.AI");
        assertThat(registry.get("zai-coding-plan").getDisplayName()).isEqualTo("Z.AI Coding Plan");
        assertThat(registry.get("zhipuai").getDisplayName()).isEqualTo("Zhipu AI");
        assertThat(registry.get("zhipuai-coding-plan").getDisplayName())
                .isEqualTo("Zhipu AI Coding Plan");
    }

    @Test
    public void eachPointsAtTheEndpointThatBillsTheWayItsNameSays() {
        assertThat(registry.get("zai").getDefaultBaseUrl())
                .isEqualTo("https://api.z.ai/api/paas/v4");
        assertThat(registry.get("zai-coding-plan").getDefaultBaseUrl())
                .isEqualTo("https://api.z.ai/api/coding/paas/v4");
        assertThat(registry.get("zhipuai").getDefaultBaseUrl())
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        assertThat(registry.get("zhipuai-coding-plan").getDefaultBaseUrl())
                .isEqualTo("https://open.bigmodel.cn/api/coding/paas/v4");
    }

    @Test
    public void theyAgreeWithTheCatalogOnTheEndpoint() {
        // The catalog records each provider's API root. A disagreement means one of the two is
        // stale, and the request goes to whichever this connector says.
        for (String id : new String[]{"zai", "zai-coding-plan", "zhipuai", "zhipuai-coding-plan"}) {
            assertThat(registry.get(id).getDefaultBaseUrl())
                    .as(id)
                    .isEqualTo(catalog.get(id).getApi());
        }
    }

    @Test
    public void theySpeakChatCompletionsWithABearerKey() {
        for (String id : new String[]{"zai", "zai-coding-plan", "zhipuai", "zhipuai-coding-plan"}) {
            ProviderConnector connector = registry.get(id);
            assertThat(connector.getProtocol()).as(id).isEqualTo(ConnectorProtocol.OPENAI_CHAT);
            assertThat(connector.getAuthScheme()).as(id).isEqualTo(AuthScheme.BEARER);
            assertThat(connector.createBackend("glm-5.1", "key", Map.of()))
                    .isInstanceOf(OpenAICompatibleBackend.class);
        }
    }

    @Test
    public void theyReadTheEnvironmentVariablesThePlatformsDocument() {
        assertThat(registry.get("zai").getEnv()).containsExactly("ZAI_API_KEY", "ZHIPU_API_KEY");
        assertThat(registry.get("zai-coding-plan").getEnv())
                .containsExactly("ZAI_API_KEY", "ZHIPU_API_KEY");
        assertThat(registry.get("zhipuai").getEnv())
                .containsExactly("ZHIPUAI_API_KEY", "ZHIPU_API_KEY");
        assertThat(registry.get("zhipuai-coding-plan").getEnv())
                .containsExactly("ZHIPUAI_API_KEY", "ZHIPU_API_KEY");
    }

    @Test
    public void theyPromiseNothingAboutRetention() {
        // Neither platform has a per-request control, and claiming one would report a guarantee
        // that was never given.
        assertThat(registry.get("zai").supportsZeroDataRetention()).isFalse();
        assertThat(registry.get("zai-coding-plan").supportsZeroDataRetention()).isFalse();
    }

    @Test
    public void aBackendIsUsableOnceAKeyIsSuppliedAndNotBefore() {
        ProviderConnector connector = registry.get("zai-coding-plan");
        assertThat(connector.createBackend("glm-5.1", "", Map.of()).isAvailable()).isFalse();
        assertThat(connector.createBackend("glm-5.1", "key", Map.of()).isAvailable()).isTrue();
    }

    @Test
    public void aKeyOfItsShapeNeverReachesALogLine() {
        // Structurally a Z.ai key -- a 32-character hex id, a dot and a secret -- and invented.
        String key = "0f1e2d3c4b5a69788796a5b4c3d2e1f0.Ab12Cd34Ef56Gh78";

        String redacted = SecretRedactor.redact("POST failed for key " + key);

        assertThat(redacted).doesNotContain(key).doesNotContain("Ab12Cd34Ef56Gh78");
    }

    @Test
    public void aBalanceConnectorNamesItsCodingPlanTwinWhenTheBalanceIsEmpty() {
        assertThat(ZaiPlatform.adviceFor("zai", "1113")).contains("'zai-coding-plan'");
        assertThat(ZaiPlatform.adviceFor("zhipuai", "1113")).contains("'zhipuai-coding-plan'");
        // A coding-plan connector already is the twin, and an expired plan is not an empty balance.
        assertThat(ZaiPlatform.adviceFor("zai-coding-plan", "1113")).isNull();
        assertThat(ZaiPlatform.adviceFor("zai", "1309")).isNull();
    }
}
