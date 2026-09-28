package com.eonmux.cadetcoder.ai.catalog;

import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.ai.providers.ProviderRegistry;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a model list that came from the provider rather than from the catalog.
 *
 * <h2>The gap</h2>
 *
 * <p>models.dev does not carry every provider a user can connect to, and for the ones it misses
 * both places that ask it -- the model picker in {@code login}, and {@code models PROVIDER} --
 * degraded to inviting the user to type a model id from memory. Command Code is the case that made
 * it plain: sixty-nine models behind one key, every one reachable, none of them nameable.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>The parsing, against the shapes a real endpoint answers with, because that is the half that
 * can be tested without a server: the minimal listing the OpenAI protocol requires, the richer one
 * gateways actually send, and the several ways a body can be useless. Also which connectors may be
 * asked at all -- asking an Anthropic or Google endpoint for {@code /models} would spend a request
 * on a 404 every time the list was wanted.</p>
 */
public class AskingAProviderWhatModelsItHasTest {

    // ------------------------------------------------------------ reading the answer

    @Test
    public void theModelsAreReadInTheOrderTheProviderNamedThem() {
        // The order is the provider's recommendation -- Command Code leads with the newest Claude
        // models -- and a picker that renumbered them would be discarding it.
        List<ModelsDevModel> models = ProviderModelListing.parse(
                "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"claude-sonnet-5\"},{\"id\":\"gpt-5.5\"},{\"id\":\"qwen3.8-max\"}]}");

        assertThat(models).extracting(ModelsDevModel::getId)
                .containsExactly("claude-sonnet-5", "gpt-5.5", "qwen3.8-max");
    }

    @Test
    public void anIdIsAllTheProtocolRequires() {
        // Everything past "id" is an extension, so a strict reader would reject a conforming server.
        List<ModelsDevModel> models = ProviderModelListing.parse(
                "{\"data\":[{\"id\":\"llama3.3:70b\",\"object\":\"model\"}]}");

        assertThat(models).singleElement()
                .satisfies(m -> assertThat(m.getId()).isEqualTo("llama3.3:70b"));
    }

    @Test
    public void aModelWithNoNameIsNamedByItsId() {
        // The listing is printed in a two-column table; an empty name column beside an id would be
        // a column of nothing.
        assertThat(ProviderModelListing.parse("{\"data\":[{\"id\":\"mistral-large\"}]}"))
                .singleElement()
                .satisfies(m -> assertThat(m.getName()).isEqualTo("mistral-large"));
    }

    @Test
    public void theNameAndContextLengthAreTakenWhenTheyAreOffered() {
        List<ModelsDevModel> models = ProviderModelListing.parse(
                "{\"object\":\"list\",\"data\":[{\"id\":\"claude-opus-5\",\"object\":\"model\","
                + "\"owned_by\":\"command-code\",\"name\":\"Claude Opus 5\","
                + "\"context_length\":1000000}]}");

        assertThat(models).singleElement().satisfies(m -> {
            assertThat(m.getId()).isEqualTo("claude-opus-5");
            assertThat(m.getName()).isEqualTo("Claude Opus 5");
            assertThat(m.getLimit().getContext()).isEqualTo(1_000_000L);
        });
    }

    @Test
    public void aModelThatDeclaresNoContextLengthClaimsNone() {
        // Zero would be printed as a context window of zero tokens, which is a worse answer than
        // saying nothing.
        assertThat(ProviderModelListing.parse("{\"data\":[{\"id\":\"local-model\"}]}"))
                .singleElement()
                .satisfies(m -> assertThat(m.getLimit()).isNull());
    }

    @Test
    public void anEntryWithNoUsableIdIsPassedOverRatherThanListed() {
        // It cannot be selected, so listing it offers the user a choice that cannot be made.
        List<ModelsDevModel> models = ProviderModelListing.parse(
                "{\"data\":[{\"object\":\"model\"},{\"id\":\"\"},{\"id\":\"  \"},{\"id\":\"real\"}]}");

        assertThat(models).extracting(ModelsDevModel::getId).containsExactly("real");
    }

    // ------------------------------------------------------------ nothing usable came back

    @Test
    public void abodyThatNamesNoModelsIsAnEmptyListAndNotAFailure() {
        // "Could not say" is the state the caller was already in before it asked, and it handles it.
        assertThat(ProviderModelListing.parse("{\"object\":\"list\",\"data\":[]}")).isEmpty();
        assertThat(ProviderModelListing.parse("{\"error\":\"unauthorized\"}")).isEmpty();
        assertThat(ProviderModelListing.parse("{\"data\":\"not an array\"}")).isEmpty();
    }

    @Test
    public void abodyThatIsNotJsonIsAnEmptyListAndNotAnException() {
        // A proxy or a captive portal answers 200 with HTML; the user asked for a model list, and
        // an exception here would end the command they were in the middle of.
        assertThat(ProviderModelListing.parse("<html>504 Gateway Timeout</html>")).isEmpty();
        assertThat(ProviderModelListing.parse("")).isEmpty();
        assertThat(ProviderModelListing.parse("   ")).isEmpty();
        assertThat(ProviderModelListing.parse(null)).isEmpty();
    }

    // ------------------------------------------------------------ who may be asked

    @Test
    public void onlyTheOpenAiWirePublishesAListThisCanRead() {
        ProviderRegistry registry = ProviderRegistry.getInstance();

        assertThat(ProviderModelListing.supports(registry.get("commandcode"))).isTrue();
        assertThat(ProviderModelListing.supports(registry.get("openai"))).isTrue();
        // Each of these lists its models differently or not at all, and all of them are already in
        // the catalog, so asking would spend a request to be told 404.
        assertThat(ProviderModelListing.supports(registry.get("anthropic"))).isFalse();
        assertThat(ProviderModelListing.supports(registry.get("google"))).isFalse();
        assertThat(ProviderModelListing.supports(registry.get("amazon-bedrock"))).isFalse();
    }

    @Test
    public void aConnectorWithNoEndpointOfItsOwnCannotBeAsked() {
        // Azure and the Cloudflare gateways build their endpoint from per-user options, so there is
        // nowhere to send the question until those are supplied.
        assertThat(ProviderModelListing.supports(ProviderRegistry.getInstance().get("azure")))
                .isFalse();
        assertThat(ProviderModelListing.supports(null)).isFalse();
    }

    @Test
    public void aConnectorThatCannotBeAskedIsNotAskedOverTheNetwork() {
        // Returns without a request; a test that reached the network would not be able to say so.
        ProviderConnector anthropic = ProviderRegistry.getInstance().get("anthropic");

        assertThat(ProviderModelListing.fetch(anthropic, "key", Map.of())).isEmpty();
        assertThat(ProviderModelListing.fetch(null, null, null)).isEmpty();
    }
}
