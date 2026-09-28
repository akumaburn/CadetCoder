package com.eonmux.cadetcoder.ai;

/**
 * Default implementation of AIClientFactory that creates real AI clients.
 */
public class DefaultAIClientFactory implements AIClientFactory {
    @Override
    public LocalAIClient createLocalAIClient() {
        return new LocalAIClient();
    }

    @Override
    public APIClient createAPIClient() {
        return new APIClient();
    }
}