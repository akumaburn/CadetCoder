package com.eonmux.cadetcoder.harness.tools;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tool surface is a contract, and the same list that describes it to the agent is the list that
 * checks what comes back.
 *
 * <p>Every tool call arrives as text a language model wrote. That is external data: an argument the
 * tool does not take, a required one that never came, a number that arrived as a string. Guessing
 * at any of those is how a harness ends up acting on an argument nobody meant. Each test here is a
 * way that guess gets made, turned into a refusal that names what was wrong.</p>
 */
public class EveryToolIsNamedAndItsArgumentsAreCheckedTest {

    private static ToolArgs args(String tool, Object... pairs) {
        Map<String, Object> given = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            given.put((String) pairs[at], pairs[at + 1]);
        }
        return new ToolArgs(ToolCatalog.of(tool), given);
    }

    @Test
    public void everyToolTheAgentIsToldAboutCanBeLookedUpByTheNameItWasToldTest() {
        assertThat(ToolCatalog.TOOLS).isNotEmpty();
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            assertThat(ToolCatalog.of(schema.name())).isSameAs(schema);
        }
    }

    @Test
    public void noToolIsNamedTwice() {
        List<String> names = new ArrayList<>();
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            names.add(schema.name());
        }
        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    public void everyToolSaysWhatItIsForAndNamesEveryArgumentItTakes() {
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            assertThat(schema.purpose()).as(schema.name()).isNotBlank();
            List<String> named = new ArrayList<>();
            for (ToolParam parameter : schema.parameters()) {
                assertThat(parameter.purpose()).as(schema.name() + "." + parameter.name()).isNotBlank();
                named.add(parameter.name());
            }
            assertThat(named).as(schema.name()).doesNotHaveDuplicates();
        }
    }

    @Test
    public void nothingInTheCatalogOffersToRunCodeTheHarnessDidNotWrite() {
        assertThat(ToolCatalog.of("run_python")).isNull();
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            String said = (schema.name() + " " + schema.purpose()).toLowerCase(Locale.ROOT);
            assertThat(said).as(schema.name()).doesNotContain("python");
        }
    }

    @Test
    public void theRenderedCatalogNamesEveryToolAndTheArgumentsItTakes() {
        String rendered = ToolCatalog.render();
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            assertThat(rendered).contains(schema.name());
            for (ToolParam parameter : schema.parameters()) {
                assertThat(rendered).contains(parameter.name());
            }
        }
        assertThat(rendered).contains("ledger_get(index)");
        assertThat(rendered).contains("[note]");
    }

    @Test
    public void anArgumentTheToolDoesNotTakeIsRefusedRatherThanIgnored() {
        assertThatThrownBy(() -> args("ledger_get", "index", 1, "with_obs", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger_get takes no argument called with_obs")
                .hasMessageContaining("index");
    }

    @Test
    public void aRequiredArgumentThatNeverCameIsNamedRatherThanDefaulted() {
        assertThatThrownBy(() -> args("ledger_get"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger_get needs index");
    }

    @Test
    public void aToolThatTakesNoArgumentsAtAllStillRefusesOne() {
        assertThatThrownBy(() -> args("beliefs", "n", 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("beliefs takes no arguments");
    }

    @Test
    public void aWholeNumberWrittenAsTextIsStillAWholeNumber() {
        assertThat(args("ledger_get", "index", "7").integer("index", 0)).isEqualTo(7);
        assertThat(args("ledger_tail", "n", 4.0).integer("n", 0)).isEqualTo(4);
    }

    @Test
    public void somethingThatIsNoNumberAtAllIsRefusedWithWhatWasGiven() {
        assertThatThrownBy(() -> args("ledger_get", "index", "later").integer("index", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index has to be a whole number")
                .hasMessageContaining("later");
    }

    @Test
    public void aFlagWrittenAsTextIsStillAFlag() {
        assertThat(args("ledger_tail", "with_obs", "true").flag("with_obs", false)).isTrue();
        assertThat(args("ledger_tail", "with_obs", "false").flag("with_obs", true)).isFalse();
        assertThatThrownBy(() -> args("ledger_tail", "with_obs", "maybe").flag("with_obs", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("with_obs has to be true or false");
    }

    @Test
    public void anArgumentNobodyGaveFallsBackToWhatTheToolChose() {
        assertThat(args("ledger_tail").integer("n", 10)).isEqualTo(10);
        assertThat(args("ledger_tail").flag("with_obs", false)).isFalse();
        assertThat(args("commit").text("note", "")).isEmpty();
        assertThat(args("commit").values("actions", List.of())).isEmpty();
    }

    @Test
    public void aSingleValueWhereAListWasWantedIsRefusedRatherThanWrapped() {
        assertThatThrownBy(() -> args("simulate", "actions", Map.of("move", 1)).values("actions"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actions has to be a list");
    }

    @Test
    public void ledgerIndicesAreReadAsWholeNumbersAndNothingElse() {
        assertThat(args("belief_refute", "id", "B1", "evidence", List.of(1, "2"), "reason", "no")
                           .integers("evidence")).containsExactly(1, 2);
        assertThatThrownBy(() -> args("belief_refute", "id", "B1", "evidence", List.of("soon"),
                                      "reason", "no").integers("evidence"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence")
                .hasMessageContaining("whole number");
    }

    @Test
    public void aTagListIsReadAsWordsAndNothingElse() {
        assertThat(args("belief_question", "text", "why", "tags", List.of("grid", 3)).texts("tags"))
                .containsExactly("grid", "3");
    }

    @Test
    public void everyRequiredArgumentIsAskedForBeforeTheToolRuns() {
        for (ToolSchema schema : ToolCatalog.TOOLS) {
            for (ToolParam parameter : schema.parameters()) {
                if (parameter.required()) {
                    assertThatThrownBy(() -> new ToolArgs(schema, Map.of()))
                            .as(schema.name())
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining(parameter.name());
                }
            }
        }
    }
}
