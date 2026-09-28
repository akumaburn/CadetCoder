package com.eonmux.cadetcoder.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for DefaultAIClientFactory
 */
public class DefaultAIClientFactoryTest {

    private DefaultAIClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new DefaultAIClientFactory();
    }

    @Test
    void testCreateLocalAIClient() {
        LocalAIClient client = factory.createLocalAIClient();

        assertThat(client).isNotNull();
        assertThat(client).isInstanceOf(LocalAIClient.class);
    }

    @Test
    void testCreateAPIClient() {
        APIClient client = factory.createAPIClient();

        assertThat(client).isNotNull();
        assertThat(client).isInstanceOf(APIClient.class);
    }

    @Test
    void testFactoryReturnsNewInstances() {
        // Test that factory returns new instances each time
        LocalAIClient localClient1 = factory.createLocalAIClient();
        LocalAIClient localClient2 = factory.createLocalAIClient();

        assertThat(localClient1).isNotSameAs(localClient2);

        APIClient apiClient1 = factory.createAPIClient();
        APIClient apiClient2 = factory.createAPIClient();

        assertThat(apiClient1).isNotSameAs(apiClient2);
    }

    @AfterEach
    void tearDown() {
        // Cleanup if needed
    }
}