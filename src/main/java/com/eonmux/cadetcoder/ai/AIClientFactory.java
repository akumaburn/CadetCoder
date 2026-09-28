package com.eonmux.cadetcoder.ai;

/**
 * Factory interface for creating AI clients.
 * This allows for dependency injection and testing.
 */
public interface AIClientFactory {
    /**
     * Creates a new LocalAIClient instance.
     */
    LocalAIClient createLocalAIClient();

    /**
     * Creates a new APIClient instance.
     */
    APIClient createAPIClient();
}