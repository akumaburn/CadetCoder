package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.catalog.ModelsDevModel;
import com.eonmux.cadetcoder.ai.catalog.ProviderModelListing;
import com.eonmux.cadetcoder.ai.providers.ProviderConnector;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

/**
 * What {@code models} says a connector offers, and how it says a model is chosen.
 *
 * <h2>Why the count cannot come from the catalogue alone</h2>
 *
 * <p>models.dev does not list every provider a user can reach, and a gateway is exactly the kind it
 * misses. The Models column read the catalogue alone, so Command Code -- seventy-one models, every
 * one of them printed by {@code models commandcode} -- appeared in the table as {@code 0}. A user
 * looking at the row for the provider they were actively talking to was told it served nothing.</p>
 *
 * <h2>Why a dash is not a zero</h2>
 *
 * <p>"No models" and "could not be asked" are opposite facts and the column had one spelling for
 * both. A connector that publishes its own list and did not answer prints a dash, and the table
 * says underneath what a dash means.</p>
 *
 * <h2>Why the guidance is checked</h2>
 *
 * <p>Six commands were listed across two run-on lines, in source order rather than the order anyone
 * uses them, and none of them said which one actually selects a model. A listing of seventy-one
 * model ids then ended with nothing on screen saying what to type next.</p>
 */
public class AconnectorIsCountedByWhatItWillActuallyListTest {

    /** A connector the catalogue does not carry, which is the whole point of the fallback. */
    private static final String SELF_LISTING = "commandcode";

    private TestOutputCapture output;

    @BeforeEach
    public void captureOutput() {
        ProviderModelListing.resetForTesting();
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    public void releaseOutput() {
        output.stopCapture();
        ProviderModelListing.resetForTesting();
    }

    @Test
    public void aconnectorThatPublishesItsOwnListIsCountedRatherThanCalledEmpty() {
        try (MockedStatic<ProviderModelListing> listing = listing(named("a", "b", "c"))) {
            new ModelsCommand().execute(new String[] {});
        }

        assertThat(rowFor(SELF_LISTING))
                .as("the provider named three models, so the table must not say none")
                .endsWith("3");
    }

    @Test
    public void aconnectorThatCouldAnswerButDidNotIsAdashRatherThanAzero() {
        try (MockedStatic<ProviderModelListing> listing = listing(List.of())) {
            new ModelsCommand().execute(new String[] {});
        }

        assertThat(rowFor(SELF_LISTING)).endsWith("-");
        assertThat(output.getAllOutput())
                .as("a dash on its own is a symbol the reader has to guess at")
                .contains("publishes its own model list");
    }

    @Test
    public void aconnectorTheCatalogueCarriesIsCountedFromTheCatalogue() {
        try (MockedStatic<ProviderModelListing> listing = listing(List.of())) {
            new ModelsCommand().execute(new String[] {});
        }

        // Anthropic is in the catalogue, so its count is known without asking anyone.
        assertThat(rowFor("anthropic")).doesNotEndWith("-").doesNotEndWith(" 0");
    }

    @Test
    public void thesummarySaysWhichCommandSelectsAmodel() {
        try (MockedStatic<ProviderModelListing> listing = listing(List.of())) {
            new ModelsCommand().execute(new String[] {});
        }

        String printed = output.getAllOutput();
        assertThat(printed).contains("Choosing a model");
        assertThat(printed).contains("models <connector>");
        assertThat(printed)
                .as("the one command that changes which model answers")
                .contains("models use <connector> <model>");
    }

    @Test
    public void alistingOfOneConnectorSaysWhatToTypeNext() {
        try (MockedStatic<ProviderModelListing> listing = listing(named("gpt-x"))) {
            new ModelsCommand().execute(new String[] {SELF_LISTING});
        }

        assertThat(output.getAllOutput()).contains("models use " + SELF_LISTING + " <model>");
    }

    @Test
    public void searchFindsAmodelOnlyTheConnectorKnowsAbout() {
        // The catalogue has no entry for this provider, so before this the search covered every
        // provider except the ones the user had actually configured.
        try (MockedStatic<ProviderModelListing> listing = listing(named("zzz-unique-model"))) {
            new ModelsCommand().execute(new String[] {"find", "zzz-unique"});
        }

        String printed = output.getAllOutput();
        assertThat(printed).contains("zzz-unique-model");
        assertThat(printed).contains(SELF_LISTING);
    }

    /**
     * Stubs the live listing so no test here depends on a provider being reachable.
     *
     * @param models what every asked provider answers with
     * @return the static mock, to be closed by the caller
     */
    private static MockedStatic<ProviderModelListing> listing(List<ModelsDevModel> models) {
        MockedStatic<ProviderModelListing> mock = mockStatic(ProviderModelListing.class);
        mock.when(() -> ProviderModelListing.supports(any(ProviderConnector.class))).thenReturn(true);
        mock.when(() -> ProviderModelListing.fetch(any(), any(), any())).thenReturn(models);
        return mock;
    }

    /** @return one catalogue-shaped entry per id */
    private static List<ModelsDevModel> named(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> {
            ModelsDevModel model = new ModelsDevModel();
            model.setId(id);
            model.setName(id);
            return model;
        }).toList();
    }

    /** @return the connector table's row for this id, trimmed, or "" when it is not there */
    private String rowFor(String connectorId) {
        for (String line : output.getAllOutput().split("\n")) {
            String plain = line.replaceAll("\u001B\\[[0-9;]*m", "").trim();
            if (plain.startsWith(connectorId + " ")) {
                return plain;
            }
        }
        return "";
    }
}
