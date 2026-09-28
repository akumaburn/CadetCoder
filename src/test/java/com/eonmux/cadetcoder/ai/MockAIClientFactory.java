package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.util.Map;

/**
 * Mock AI client factory for testing purposes
 */
public class MockAIClientFactory implements AIClientFactory {

    private LocalAIClient mockLocalClient;
    private APIClient     mockAPIClient;
    private boolean       localClientAvailable = false;
    private boolean       apiClientAvailable   = true;
    private String        localResponse        = "Mock local response";
    private String        apiResponse          = "Mock API response";

    public MockAIClientFactory() {
        // Ensure valid configuration is set up before any clients are created
        ensureValidConfiguration();
    }

    private void ensureValidConfiguration() {
        // Ensure ConfigManager returns valid configuration to avoid URI errors
        try {
            ConfigManager configManager = ConfigManager.getInstance();
            Configuration config        = configManager.getConfig();
            if (config.getAi().getLocalEndpoint() == null) {
                config.getAi().setLocalEndpoint("http://localhost:8012");
            }
            if (config.getAi().getApiEndpoint() == null) {
                config.getAi().setApiEndpoint("https://api.openai.com/v1");
            }
            // Ensure LoggingConfig is not null
            if (config.getLogging() == null) {
                config.setLogging(new Configuration.LoggingConfig());
            }
        } catch (Exception e) {
            // If ConfigManager is mocked, this might fail - that's OK
        }
    }

    @Override
    public LocalAIClient createLocalAIClient() {
        if (mockLocalClient == null) {
            mockLocalClient = new MockLocalAIClient();
        }
        return mockLocalClient;
    }

    @Override
    public APIClient createAPIClient() {
        if (mockAPIClient == null) {
            // Create an APIClient that overrides key methods for testing
            mockAPIClient = new APIClient() {
                @Override
                public String complete(PromptData promptData, Map<String, Object> options) {
                    return apiResponse;
                }

                @Override
                public boolean isAvailable() {
                    return apiClientAvailable;
                }

                @Override
                public String getModelName() {
                    return "mock-api-model";
                }
            };
        }
        return mockAPIClient;
    }

    // Configuration methods for tests
    public void setLocalClientAvailable(boolean available) {
        this.localClientAvailable = available;
    }

    public void setApiClientAvailable(boolean available) {
        this.apiClientAvailable = available;
    }

    public void setLocalResponse(String response) {
        this.localResponse = response;
    }

    public void setApiResponse(String response) {
        this.apiResponse = response;
    }

    // Mock implementations
    private class MockLocalAIClient extends LocalAIClient {
        @Override
        public String complete(PromptData promptData, Map<String, Object> options) {
            return localResponse;
        }

        @Override
        public boolean isAvailable() {
            return localClientAvailable;
        }

        @Override
        public String getModelName() {
            return "mock-local-model";
        }
    }

}