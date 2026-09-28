package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import org.junit.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the model-facing catalog and the command registry from drifting apart.
 *
 * <p>{@link CommandCatalog} is a hand-written constant and the registry is discovered by reflection,
 * so nothing but this test connects them. Both directions of drift are silent and both cost the
 * model turns: a command the catalog omits is a capability that effectively does not exist (this is
 * how {@code search} went unusable while {@code index} was advertised as building an index "for
 * faster search"), and a command the catalog invents is a tool call that can only ever fail.</p>
 */
public class CommandCatalogCoverageTest {

    /** Matches the leading {@code - <name>} of a catalog entry, ignoring continuation lines. */
    private static final Pattern ENTRY = Pattern.compile("^- ([a-z]+)\\b");

    /**
     * Registered commands deliberately kept out of the catalog, each with the reason.
     *
     * <p>Every entry here is a decision, not an oversight. Adding a command to this map is how a
     * new command is declared human-only; leaving it out of both this map and the catalog fails the
     * test, which is the point -- the default for a new command is that somebody has to choose.</p>
     */
    private static final Map<String, String> WITHHELD = Map.ofEntries(
            Map.entry("agent", "starts a model loop of its own, and everything that reads the "
                               + "catalog is already inside one, so ModelDispatch refuses it every "
                               + "time; 'workers' is the delegation the model actually has"),
            Map.entry("chat", "the model is already in a chat; the harness routes unmatched input here"),
            Map.entry("clear", "clears the person's console, their view of what the model did"),
            Map.entry("compact", "manages the conversation's own history, which the harness owns"),
            Map.entry("config", "changes the user's settings"),
            Map.entry("copilot", "interactive authentication flow"),
            Map.entry("execute", "runs a script file; CommandAliases maps the model's 'execute' to bash on purpose"),
            Map.entry("help", "the catalog is the model's help"),
            Map.entry("login", "interactive credential entry"),
            Map.entry("loop", "starts a model loop of its own, once per pass, and "
                              + "everything that reads the catalog is already inside one"),
            Map.entry("loopfresh", "the same, and refused for the same reason"),
            Map.entry("models", "selects the model that is running the loop"),
            Map.entry("plan", "toggles a UI mode"),
            Map.entry("prompt", "manages prompt templates, a user-facing concern"),
            Map.entry("push", "publishes to a remote and is not reversible"),
            Map.entry("quit", "ends the user's session"),
            Map.entry("runs", "reads the harness records, which are kept outside what a run "
                              + "observes; an agent able to read its own ledger mid-run "
                              + "would be observing its own writing"),
            Map.entry("session", "manages saved sessions, a user-facing concern"),
            Map.entry("shell", "starts the interactive REPL"),
            Map.entry("theme", "appearance only"),
            Map.entry("ubermode", "decides whether a run is allowed to stop when the model says it "
                                  + "is finished; a model able to turn that off would be deciding "
                                  + "for itself whether its own claim needs checking"),
            Map.entry("undo", "discards uncommitted work"));

    private static Set<String> advertised() {
        Set<String> names = new TreeSet<>();
        for (String line : CommandCatalog.coreCommands().split("\n")) {
            Matcher matcher = ENTRY.matcher(line.trim());
            if (matcher.find()) {
                names.add(matcher.group(1));
            }
        }
        return names;
    }

    private static Set<String> registered() {
        return new TreeSet<>(new CommandRegistry().getCommands().keySet());
    }

    @Test
    public void everyRegisteredCommandIsEitherOfferedToTheModelOrDeliberatelyWithheld() {
        Set<String> unclassified = new TreeSet<>(registered());
        unclassified.removeAll(advertised());
        unclassified.removeAll(WITHHELD.keySet());

        assertThat(unclassified)
                .as("these commands exist but the model is never told about them, so it can never "
                    + "use them; either add them to CommandCatalog or record why they are "
                    + "human-only in this test's WITHHELD map")
                .isEmpty();
    }

    @Test
    public void everyCommandTheCatalogAdvertisesIsActuallyRegistered() {
        Set<String> phantom = new TreeSet<>(advertised());
        phantom.removeAll(registered());

        assertThat(phantom)
                .as("the catalog offers these to the model but nothing is registered under that "
                    + "name, so every call the model makes to one of them fails")
                .isEmpty();
    }

    @Test
    public void nothingIsBothAdvertisedAndRecordedAsWithheld() {
        Set<String> contradictory = new TreeSet<>(advertised());
        contradictory.retainAll(WITHHELD.keySet());

        assertThat(contradictory)
                .as("WITHHELD claims these are kept from the model, but the catalog advertises "
                    + "them; the stale half of the pair is a lie about what the model can do")
                .isEmpty();
    }

    /** The gap that prompted this test: a working, index-backed search the model could not reach. */
    @Test
    public void theModelIsToldItCanSearchByMeaning() {
        assertThat(advertised()).contains("search");
        assertThat(CommandCatalog.coreCommands()).contains("grep");
    }

    /**
     * Nothing the model is offered is a command the model is guaranteed to be refused.
     *
     * <p><b>The defect</b>: the catalog advertised {@code agent <task>}, and every reader of the
     * catalog is itself a model loop -- the chat loop, the agent loop, the agent harness --
     * so {@link ModelDispatch} refused it every single time it was called. It was not a capability
     * with an edge case; it was a line of the briefing whose only possible outcome was a wasted
     * turn and a refusal. Delegation is what {@code workers} is for, and that is advertised.</p>
     */
    @Test
    public void nothingAdvertisedIsRefusedEveryTimeItIsCalled() {
        Set<String> alwaysRefused = new TreeSet<>();
        for (String name : advertised()) {
            if (ModelDispatch.startsALoop(name)) {
                alwaysRefused.add(name);
            }
        }

        assertThat(alwaysRefused)
                .as("the catalog is only ever read by a model that is already inside a run, so a "
                    + "command that starts a loop of its own is refused on every call; offering it "
                    + "costs the model a turn and teaches it nothing")
                .isEmpty();
    }

    @Test
    public void aWorkerSeesTheSameCatalogMinusTheAbilityToSpawnWorkers() {
        assertThat(CommandCatalog.workerCommands()).contains("- search");
        assertThat(CommandCatalog.workerCommands()).doesNotContain("- workers");
    }
}
