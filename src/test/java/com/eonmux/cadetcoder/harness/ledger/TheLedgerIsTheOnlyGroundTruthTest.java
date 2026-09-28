package com.eonmux.cadetcoder.harness.ledger;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.env.Environment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Everything the harness believes has to be answerable from the record of what actually happened.
 *
 * <p>A world model is a theory, a certificate is an argument about a theory, and a plan is a
 * consequence of one. None of them is evidence. The ledger is: one line per transition that really
 * occurred, appended and never rewritten, each line naming the hash of the line before it. That
 * chain is what makes "the model was certified against the ledger" mean something -- without it a
 * certificate would only say the model agreed with whatever the agent last chose to remember.</p>
 */
public class TheLedgerIsTheOnlyGroundTruthTest {

    @Rule
    public TemporaryFolder workspace = new TemporaryFolder();

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private Ledger freshLedger() throws IOException {
        return new Ledger(workspace.newFolder("ws").toPath().resolve("ledger.jsonl"));
    }

    private static Transition move(Ledger ledger, int episode, int from, int to) {
        return ledger.append(Transition.proposal(episode, map("pos", from), map("move", 1),
                                                 map("pos", to), map(), map()));
    }

    @Test
    public void anEmptyLedgerHeadsAtGenesis() throws IOException {
        Ledger ledger = freshLedger();

        assertThat(ledger.size()).isZero();
        assertThat(ledger.head()).isEqualTo(Ledger.GENESIS);
        assertThat(ledger.all()).isEmpty();
    }

    @Test
    public void everyTransitionNamesTheOneBeforeIt() throws IOException {
        Ledger ledger = freshLedger();

        Transition first  = move(ledger, 0, 0, 1);
        Transition second = move(ledger, 0, 1, 2);

        assertThat(first.prevHash()).isEqualTo(Ledger.GENESIS);
        assertThat(second.prevHash()).isEqualTo(first.hash());
        assertThat(ledger.head()).isEqualTo(second.hash());
        assertThat(first.index()).isZero();
        assertThat(second.index()).isEqualTo(1);
    }

    @Test
    public void aReloadedLedgerCarriesTheSameChain() throws IOException {
        Path path = workspace.newFolder("reload").toPath().resolve("ledger.jsonl");

        Ledger written = new Ledger(path);
        move(written, 0, 0, 1);
        move(written, 0, 1, 2);

        Ledger reloaded = new Ledger(path);

        assertThat(reloaded.size()).isEqualTo(2);
        assertThat(reloaded.head()).isEqualTo(written.head());
        assertThat(Json.canonical(reloaded.get(1).obsAfter())).isEqualTo("{\"pos\":2}");
    }

    /** The whole point of the chain: a rewritten past does not load. */
    @Test
    public void anEditedRecordIsRefused() throws IOException {
        Path path = workspace.newFolder("edited").toPath().resolve("ledger.jsonl");

        Ledger ledger = new Ledger(path);
        move(ledger, 0, 0, 1);
        move(ledger, 0, 1, 2);

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        lines.set(0, lines.get(0).replace("\"pos\":1", "\"pos\":9"));
        Files.write(path, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new Ledger(path))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("line 1");
    }

    @Test
    public void aRemovedRecordIsRefused() throws IOException {
        Path path = workspace.newFolder("removed").toPath().resolve("ledger.jsonl");

        Ledger ledger = new Ledger(path);
        move(ledger, 0, 0, 1);
        move(ledger, 0, 1, 2);
        move(ledger, 0, 2, 3);

        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        lines.remove(1);
        Files.write(path, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new Ledger(path))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("does not follow");
    }

    @Test
    public void aTruncatedLineIsRefusedRatherThanSkipped() throws IOException {
        Path path = workspace.newFolder("torn").toPath().resolve("ledger.jsonl");

        Ledger ledger = new Ledger(path);
        move(ledger, 0, 0, 1);
        Files.writeString(path, Files.readString(path, StandardCharsets.UTF_8) + "{\"index\":1,",
                          StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new Ledger(path))
                .isInstanceOf(LedgerException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    public void resetsAreCountedApartFromRealActions() throws IOException {
        Ledger ledger = freshLedger();

        ledger.append(Transition.proposal(0, null, Environment.RESET_ACTION,
                                          map("pos", 0), map(), map()));
        move(ledger, 0, 0, 1);
        ledger.append(Transition.proposal(1, map("pos", 1), Environment.RESET_ACTION,
                                          map("pos", 0), map(), map()));
        move(ledger, 1, 0, 1);
        move(ledger, 1, 1, 2);

        LedgerStats stats = ledger.stats();

        assertThat(stats.transitions()).isEqualTo(5);
        assertThat(stats.resets()).isEqualTo(2);
        assertThat(stats.realActions())
                .as("a reset costs nothing in the world; only real actions are spent")
                .isEqualTo(3);
        assertThat(stats.episodes()).isEqualTo(2);
    }

    @Test
    public void reachingTheGoalIsCounted() throws IOException {
        Ledger ledger = freshLedger();

        move(ledger, 0, 0, 1);
        ledger.append(Transition.proposal(0, map("pos", 1), map("move", 1), map("pos", 2),
                                          map("goal", true), map()));

        assertThat(ledger.stats().goals()).isEqualTo(1);
    }

    /**
     * A prediction is only evidence once its outcome is written down beside it.
     *
     * <p>Comparing the stored prediction with the recorded observation later would work only while
     * every prediction is an exact observation. Predictions here may also be named
     * constraints, which no later comparison can re-evaluate, so the gate records the verdict it
     * reached at the moment it checked.</p>
     */
    @Test
    public void predictionOutcomesAreRecordedNotRederived() throws IOException {
        Ledger ledger = freshLedger();

        ledger.append(Transition.proposal(0, map("pos", 0), map("move", 1), map("pos", 1),
                                          map(), map())
                              .predicting("m1", map("pos", 1), true));
        ledger.append(Transition.proposal(0, map("pos", 1), map("move", 1), map("pos", 2),
                                          map(), map())
                              .predicting("m1", "rc == 0", false));
        move(ledger, 0, 2, 3);

        LedgerStats stats = ledger.stats();

        assertThat(stats.liveChecked()).isEqualTo(2);
        assertThat(stats.liveMispredicted()).isEqualTo(1);
        assertThat(ledger.get(2).predictionHeld())
                .as("an unchecked step is not a correct one")
                .isNull();
    }

    @Test
    public void tailReturnsTheMostRecentTransitionsInOrder() throws IOException {
        Ledger ledger = freshLedger();
        for (int i = 0; i < 5; i++) {
            move(ledger, 0, i, i + 1);
        }

        List<Transition> tail = ledger.tail(2);

        assertThat(tail).hasSize(2);
        assertThat(tail.get(0).index()).isEqualTo(3);
        assertThat(tail.get(1).index()).isEqualTo(4);
        assertThat(ledger.tail(99)).hasSize(5);
    }

    @Test
    public void anIndexOutsideTheLedgerIsRefused() throws IOException {
        Ledger ledger = freshLedger();
        move(ledger, 0, 0, 1);

        assertThatThrownBy(() -> ledger.get(1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    /**
     * A recorded transition refuses to change rather than quietly handing out a copy.
     *
     * <p>Copying on every read would hide the attempt; a record whose hash covers its contents has
     * no honest way to let those contents be edited afterwards.</p>
     */
    @Test
    public void aRecordedTransitionCannotBeChanged() throws IOException {
        Ledger ledger = freshLedger();

        @SuppressWarnings ("unchecked")
        Map<String, Object> recorded = (Map<String, Object>) move(ledger, 0, 0, 1).obsAfter();

        assertThatThrownBy(() -> recorded.put("pos", 99))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(Json.canonical(ledger.get(0).obsAfter())).isEqualTo("{\"pos\":1}");
    }

    @Test
    public void theWorkspaceDirectoryIsCreatedOnDemand() throws IOException {
        Path nested = workspace.newFolder("root").toPath().resolve("a/b/ledger.jsonl");

        Ledger ledger = new Ledger(nested);
        move(ledger, 0, 0, 1);

        assertThat(Files.exists(nested)).isTrue();
    }
}
