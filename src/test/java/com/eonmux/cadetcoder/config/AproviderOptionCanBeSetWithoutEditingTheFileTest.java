package com.eonmux.cadetcoder.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The options three connectors cannot be reached without can be set by the tool that needs them.
 *
 * <p><b>The defect</b>: {@code ai.providerOptions} is read by {@code ProviderConnector} to build
 * Azure's endpoint from {@code resourceName} and both Cloudflare endpoints from {@code accountId}
 * and {@code gatewayId} -- and nothing in the tool could write it. Azure reported "missing
 * credentials?" when the key was fine and only the resource name was absent; Cloudflare built a URL
 * with holes in it, which is non-blank enough to look configured, and answered 404 at the first
 * request. {@code /config} listed such an option once it existed and then refused to set the name it
 * had just printed.</p>
 */
class AproviderOptionCanBeSetWithoutEditingTheFileTest {

    @Test
    void anOptionIsFiledUnderTheProviderItBelongsTo() {
        Configuration config = new Configuration();

        ConfigOverrides.apply(config, "ai.providerOptions.azure.resourceName", "my-resource");
        ConfigOverrides.apply(config, "ai.providerOptions.cloudflare-ai-gateway.accountId", "acct");
        ConfigOverrides.apply(config, "ai.providerOptions.cloudflare-ai-gateway.gatewayId", "gw");

        assertThat(config.getAi().getProviderOptions().get("azure"))
                .containsEntry("resourceName", "my-resource");
        assertThat(config.getAi().getProviderOptions().get("cloudflare-ai-gateway"))
                .containsEntry("accountId", "acct")
                .containsEntry("gatewayId", "gw");
    }

    @Test
    void settingItToNothingTakesItBackOut() {
        Configuration config = new Configuration();
        ConfigOverrides.apply(config, "ai.providerOptions.azure.resourceName", "my-resource");

        ConfigOverrides.apply(config, "ai.providerOptions.azure.resourceName", "");

        assertThat(config.getAi().getProviderOptions()).doesNotContainKey("azure");
    }

    @Test
    void anameThatDoesNotSayWhichProviderIsRefusedAndExplained() {
        Configuration config = new Configuration();

        assertThatThrownBy(() -> ConfigOverrides.apply(config, "ai.providerOptions.resourceName", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ai.providerOptions.<provider>.<option>");
    }

    /**
     * The prefix before the provider is matched whatever its case, so the provider itself has to be
     * too. Stored as typed, {@code ai.providerOptions.Azure.resourceName} was accepted and saved,
     * and then never read: {@code ConnectorAIClient} looks the map up by the connector's id, which
     * is {@code azure}. Azure then reports missing credentials for a key that is present, because
     * what is actually missing is the resource name the user believed they had just set.
     */
    @Test
    void anoptionIsFoundUnderTheIdTheLookupUsesWhateverCaseWasTyped() {
        Configuration config = new Configuration();

        ConfigOverrides.apply(config, "ai.providerOptions.Azure.resourceName", "my-resource");
        ConfigOverrides.apply(config, "ai.ProviderOptions.CLOUDFLARE-AI-GATEWAY.accountId", "acct");

        assertThat(config.getAi().getProviderOptions().get("azure"))
                .as("the id ConnectorAIClient asks for is the id the option is filed under")
                .containsEntry("resourceName", "my-resource");
        assertThat(config.getAi().getProviderOptions().get("cloudflare-ai-gateway"))
                .containsEntry("accountId", "acct");
    }

    /** Clearing it has to find the same entry setting it created, whichever case was used. */
    @Test
    void anoptionSetInOneCaseIsClearedInAnother() {
        Configuration config = new Configuration();
        ConfigOverrides.apply(config, "ai.providerOptions.azure.resourceName", "my-resource");

        ConfigOverrides.apply(config, "ai.providerOptions.AZURE.resourceName", "");

        assertThat(config.getAi().getProviderOptions()).doesNotContainKey("azure");
    }

    /** The whole-map name is still not a scalar, and still says what to write instead. */
    @Test
    void thebareNameIsStillRefused() {
        Configuration config = new Configuration();

        assertThatThrownBy(() -> ConfigOverrides.apply(config, "ai.providerOptions", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerOptions.<provider>.<option>");
    }
}
