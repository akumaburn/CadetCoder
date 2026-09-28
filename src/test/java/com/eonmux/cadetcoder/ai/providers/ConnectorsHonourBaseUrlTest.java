package com.eonmux.cadetcoder.ai.providers;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code baseURL} option means the same thing for every connector.
 *
 * <p>{@code createBackend}'s own contract lists {@code baseURL} first among the per-provider
 * options, and {@code cadet config} accepts one for any provider. The Bedrock branch computed it
 * and then dropped it on the floor: it built its endpoint from the region alone, so a private
 * VPC endpoint, a gateway or a test double was accepted, saved, reported as set, and never
 * used -- the request went to the public AWS host regardless.</p>
 *
 * <p>Driven off the registry rather than a written-out list, so a connector added later cannot
 * quietly opt out of an option the user has been told exists.</p>
 */
public class ConnectorsHonourBaseUrlTest {

    private static final String OVERRIDE = "https://llm.internal.example";

    @Test
    public void everyConnectorSendsWhereTheBaseUrlSays() {
        for (ProviderConnector connector : ProviderRegistry.getInstance().all()) {
            String endpoint = connector
                    .createBackend("some-model", "a-key", Map.of("baseURL", OVERRIDE))
                    .getApiEndpoint();

            assertThat(endpoint)
                    .as("%s accepted a baseURL and then ignored it", connector.getId())
                    .startsWith(OVERRIDE);
        }
    }

    @Test
    public void bedrockStillDerivesItsEndpointFromTheRegionWhenNoBaseUrlIsGiven() {
        assertThat(ProviderRegistry.getInstance().get("amazon-bedrock")
                           .createBackend("anthropic.claude-3", "a-key", Map.of("region", "eu-west-1"))
                           .getApiEndpoint())
                .isEqualTo("https://bedrock-runtime.eu-west-1.amazonaws.com");
    }
}
