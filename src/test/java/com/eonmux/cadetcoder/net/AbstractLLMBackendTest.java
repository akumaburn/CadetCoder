package com.eonmux.cadetcoder.net;

import com.eonmux.cadetcoder.ai.PromptData;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RunWith (MockitoJUnitRunner.class)
public class AbstractLLMBackendTest {

    private static final String         MODEL_NAME   = "test-model";
    private static final String         API_ENDPOINT = "http://test-endpoint";
    @Mock
    private HttpClient mockHttpClient;
    private              TestLLMBackend testBackend;

    @Before
    public void setUp() {
        testBackend = new TestLLMBackend(MODEL_NAME, API_ENDPOINT);
    }

    @Test
    public void constructor_InitializesFieldsCorrectly() {
        // Then
        assertThat(testBackend.getModelName()).isEqualTo(MODEL_NAME);
        assertThat(testBackend.isAvailable()).isFalse(); // Default is false
        assertThat(testBackend.apiEndpoint).isEqualTo(API_ENDPOINT);
        assertThat(testBackend.httpClient).isNotNull();
    }

    @Test
    public void constructor_WithNullModelName_HandlesGracefully() {
        // When
        TestLLMBackend backend = new TestLLMBackend(null, API_ENDPOINT);

        // Then
        assertThat(backend.getModelName()).isNull();
        assertThat(backend.apiEndpoint).isEqualTo(API_ENDPOINT);
    }

    @Test
    public void constructor_WithNullApiEndpoint_HandlesGracefully() {
        // When
        TestLLMBackend backend = new TestLLMBackend(MODEL_NAME, null);

        // Then
        assertThat(backend.getModelName()).isEqualTo(MODEL_NAME);
        assertThat(backend.apiEndpoint).isNull();
    }

    @Test
    public void getModelName_ReturnsCorrectValue() {
        // Given
        String         customModel = "custom-model-v2";
        TestLLMBackend backend     = new TestLLMBackend(customModel, API_ENDPOINT);

        // When
        String modelName = backend.getModelName();

        // Then
        assertThat(modelName).isEqualTo(customModel);
    }

    @Test
    public void isAvailable_DefaultsToFalse() {
        // When
        boolean available = testBackend.isAvailable();

        // Then
        assertThat(available).isFalse();
    }

    @Test
    public void isAvailable_ReturnsCorrectValueAfterChange() {
        // Given
        testBackend.setAvailable(true);

        // When
        boolean available = testBackend.isAvailable();

        // Then
        assertThat(available).isTrue();
    }

    @Test
    public void httpClient_IsInitializedByDefault() {
        // Then
        assertThat(testBackend.httpClient).isNotNull();
        assertThat(testBackend.httpClient).isInstanceOf(HttpClient.class);
    }

    @Test
    public void complete_MustBeImplementedBySubclass() {
        // Given
        PromptData          promptData = new PromptData("system", "user");
        Map<String, Object> parameters = new HashMap<>();

        // When/Then
        assertThatThrownBy(() -> testBackend.complete(promptData, parameters))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Subclass must implement complete method");
    }

    @Test
    public void httpClient_HasAConnectTimeout() {
        // Without one, a black-holed endpoint hangs the CLI forever: the per-request timeout only
        // covers the exchange once the connection is established.
        assertThat(testBackend.httpClient.connectTimeout()).isPresent();
        assertThat(testBackend.httpClient.connectTimeout().get())
                .isEqualTo(java.time.Duration.ofSeconds(AbstractLLMBackend.DEFAULT_CONNECT_TIMEOUT_SECONDS));
    }

    @Test
    public void httpClient_IsSharedAcrossBackends() {
        // One pooled client per process instead of one per backend instance: reuses connections and
        // stops each backend from leaking its own selector thread.
        TestLLMBackend other = new TestLLMBackend("other-model", "http://other");

        assertThat(other.httpClient).isSameAs(testBackend.httpClient);
    }

    @Test
    public void providerId_DefaultsToTheEndpointHost() {
        TestLLMBackend backend = new TestLLMBackend(MODEL_NAME, "https://api.deepseek.com/v1");

        assertThat(backend.provider()).isEqualTo("api.deepseek.com");
    }

    @Test
    public void providerId_FallsBackWhenTheEndpointIsUnusable() {
        assertThat(new TestLLMBackend(MODEL_NAME, null).provider()).isEqualTo("unknown");
        assertThat(new TestLLMBackend(MODEL_NAME, "not a url").provider()).isEqualTo("not a url");
    }

    @Test
    public void multipleInstances_HaveIndependentState() {
        // Given
        TestLLMBackend backend1 = new TestLLMBackend("model1", "endpoint1");
        TestLLMBackend backend2 = new TestLLMBackend("model2", "endpoint2");

        backend1.setAvailable(true);
        backend2.setAvailable(false);

        // Then
        assertThat(backend1.getModelName()).isEqualTo("model1");
        assertThat(backend2.getModelName()).isEqualTo("model2");
        assertThat(backend1.apiEndpoint).isEqualTo("endpoint1");
        assertThat(backend2.apiEndpoint).isEqualTo("endpoint2");
        assertThat(backend1.isAvailable()).isTrue();
        assertThat(backend2.isAvailable()).isFalse();
    }

    @Test
    public void protectedFields_AreAccessibleToSubclasses() {
        // Given
        String newModel    = "updated-model";
        String newEndpoint = "http://new-endpoint";

        // When
        testBackend.updateFields(newModel, newEndpoint);

        // Then
        assertThat(testBackend.getModelName()).isEqualTo(newModel);
        assertThat(testBackend.apiEndpoint).isEqualTo(newEndpoint);
    }

    @Test
    public void httpClient_CanBeReplacedBySubclass() {
        // When
        testBackend.replaceHttpClient(mockHttpClient);

        // Then
        assertThat(testBackend.httpClient).isSameAs(mockHttpClient);
    }

    // Test implementation of AbstractLLMBackend for testing purposes
    private static class TestLLMBackend extends AbstractLLMBackend {

        public TestLLMBackend(String modelName, String apiEndpoint) {
            super(modelName, apiEndpoint);
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) throws Exception {
            throw new UnsupportedOperationException("Subclass must implement complete method");
        }

        // Helper methods for testing protected fields
        public void setAvailable(boolean available) {
            this.available = available;
        }

        public void updateFields(String modelName, String apiEndpoint) {
            this.modelName   = modelName;
            this.apiEndpoint = apiEndpoint;
        }

        public void replaceHttpClient(HttpClient client) {
            this.httpClient = client;
        }

        public String provider() {
            return providerId();
        }
    }
}