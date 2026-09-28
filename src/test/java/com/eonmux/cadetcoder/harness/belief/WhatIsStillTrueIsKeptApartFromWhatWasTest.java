package com.eonmux.cadetcoder.harness.belief;

import com.eonmux.cadetcoder.harness.Json;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The agent's durable claims, kept as records rather than as a notes file.
 *
 * <p>An agent that writes what it has learned into one free-form document ends up re-reading forty
 * kilobytes of "(old)", "(superseded)" and "(STALE)" to find the three sentences that are still
 * true, and it re-reads them on every turn. Worse, nothing in that document distinguishes a claim
 * the ledger supports from one the agent talked itself into. Here every claim is a record with a
 * status, the ledger indices behind it, and -- when it died -- what killed it, because a refuted
 * hypothesis is evidence too.</p>
 *
 * <p>The store is event sourced onto an append-only file, so a claim's history survives the agent's
 * context being compacted away. It is deliberately not hash chained: these are notes, not evidence.
 * The ledger is the only thing that has to be tamper evident.</p>
 */
public class WhatIsStillTrueIsKeptApartFromWhatWasTest {

    @Rule
    public TemporaryFolder workspace = new TemporaryFolder();

    private Path file;

    private BeliefStore freshStore() throws IOException {
        if (file == null) {
            file = workspace.newFolder("ws").toPath().resolve("beliefs.jsonl");
        }
        return new BeliefStore(file);
    }

    @Test
    public void aClaimIsKeptWithTheLedgerIndicesBehindIt() throws IOException {
        BeliefStore store = freshStore();

        Belief claim = store.claim("the door at 3,4 is locked", 12, List.of(7, 9), List.of("map"));

        assertThat(claim.id()).isEqualTo("B1");
        assertThat(claim.status()).isEqualTo(BeliefStatus.ACTIVE);
        assertThat(claim.evidence()).containsExactly(7, 9);
        assertThat(claim.tags()).containsExactly("map");
        assertThat(claim.ledgerLength()).isEqualTo(12);
        assertThat(store.byStatus(BeliefStatus.ACTIVE)).containsExactly(claim);
    }

    @Test
    public void aStoreIsRebuiltFromItsOwnLogRatherThanFromWhatTheAgentRemembers() throws IOException {
        BeliefStore store = freshStore();
        store.claim("the door at 3,4 is locked", 12, List.of(7), List.of());
        Belief question = store.question("what opens it", 12, List.of());
        store.resolve(question.id(), "the brass key", List.of(20));

        BeliefStore reopened = freshStore();

        assertThat(reopened.render()).isEqualTo(store.render());
        assertThat(reopened.get("B2").answer()).isEqualTo("the brass key");
        assertThat(reopened.get("B2").status()).isEqualTo(BeliefStatus.RESOLVED);
    }

    @Test
    public void aRefutedClaimLeavesTheActiveSetAndSaysWhatKilledIt() throws IOException {
        BeliefStore store = freshStore();
        Belief      claim = store.claim("walls stop movement", 4, List.of(1), List.of());

        Belief dead = store.refute(claim.id(), List.of(30, 31), "step 30 walked through one");

        assertThat(dead.status()).isEqualTo(BeliefStatus.REFUTED);
        assertThat(dead.refutedBy()).containsExactly(30, 31);
        assertThat(dead.reason()).isEqualTo("step 30 walked through one");
        assertThat(store.byStatus(BeliefStatus.ACTIVE)).isEmpty();
        assertThat(store.render()).contains("REFUTED").contains("walked through one");
    }

    @Test
    public void aQuestionIsNotAClaimUntilSomethingAnswersIt() throws IOException {
        BeliefStore store    = freshStore();
        Belief      question = store.question("does the lever open the gate", 4, List.of("gate"));

        assertThat(question.status()).isEqualTo(BeliefStatus.OPEN);
        assertThat(store.byStatus(BeliefStatus.ACTIVE)).isEmpty();
        assertThat(store.render()).contains("Open questions (1)");

        Belief answered = store.resolve(question.id(), "no, the plate does", List.of(41));

        assertThat(answered.status()).isEqualTo(BeliefStatus.RESOLVED);
        assertThat(answered.answer()).isEqualTo("no, the plate does");
        assertThat(answered.evidence()).containsExactly(41);
        assertThat(store.render()).contains("Open questions (0)")
                                  .contains("does the lever open the gate -> no, the plate does");
    }

    @Test
    public void aBetterClaimRetiresTheOneItReplacesAndBothSayWhichWasWhich() throws IOException {
        BeliefStore store = freshStore();
        Belief      first = store.claim("the gate needs a key", 4, List.of(1), List.of());

        Belief second = store.claim("the gate needs a key and a plate", 40, List.of(38),
                                    List.of(), first.id());

        assertThat(second.supersedes()).isEqualTo(first.id());
        assertThat(store.get(first.id()).status()).isEqualTo(BeliefStatus.SUPERSEDED);
        assertThat(store.get(first.id()).supersededBy()).isEqualTo(second.id());
        assertThat(store.byStatus(BeliefStatus.ACTIVE)).containsExactly(second);
        assertThat(store.render()).contains("superseded by " + second.id());
    }

    @Test
    public void replacingSomethingThatIsNotThereWritesNothingAtAll() throws IOException {
        BeliefStore store = freshStore();
        store.claim("the gate needs a key", 4, List.of(1), List.of());

        assertThatThrownBy(() -> store.claim("something better", 5, List.of(), List.of(), "B99"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("B99");

        assertThat(store.size())
                .as("a refused replacement must not leave the new claim behind")
                .isEqualTo(1);
        assertThat(freshStore().size()).isEqualTo(1);
    }

    @Test
    public void evidenceAccumulatesWithoutRepeatingWhatIsAlreadyThere() throws IOException {
        BeliefStore store = freshStore();
        Belief      claim = store.claim("the plate is pressure sensitive", 4, List.of(1), List.of());

        Belief supported = store.support(claim.id(), List.of(1, 5, 5, 9));

        assertThat(supported.evidence()).containsExactly(1, 5, 9);
    }

    @Test
    public void anActiveClaimWithNothingBehindItIsCalledOut() throws IOException {
        BeliefStore store = freshStore();
        store.claim("the maze is probably 8 by 8", 4, List.of(), List.of());

        assertThat(store.render())
                .as("a claim the ledger does not support is the one worth doubting")
                .contains("NO EVIDENCE");
    }

    @Test
    public void theViewPutsWhatIsStillTrueFirstAndTheGraveyardLast() throws IOException {
        BeliefStore store = freshStore();
        store.claim("walls stop movement", 4, List.of(1), List.of());
        store.question("what opens the gate", 4, List.of());
        Belief wrong = store.claim("the gate is always open", 4, List.of(2), List.of());
        store.refute(wrong.id(), List.of(9), "it was shut at step 9");

        String view = store.render();

        assertThat(view.indexOf("## Active")).isLessThan(view.indexOf("## Open questions"));
        assertThat(view.indexOf("## Open questions")).isLessThan(view.indexOf("## Graveyard"));
        assertThat(view).contains("## Active (1)").contains("## Graveyard (1");
    }

    @Test
    public void theGraveyardIsCappedSoTheViewStaysReadable() throws IOException {
        BeliefStore store = freshStore();
        for (int i = 0; i < BeliefStore.GRAVEYARD_SHOWN + 5; i++) {
            Belief wrong = store.claim("wrong guess " + i, i, List.of(i), List.of());
            store.refute(wrong.id(), List.of(i), "no");
        }

        String view = store.render();

        assertThat(view).contains("## Graveyard (" + (BeliefStore.GRAVEYARD_SHOWN + 5) + ";");
        assertThat(view).doesNotContain("wrong guess 0");
        assertThat(view).contains("wrong guess " + (BeliefStore.GRAVEYARD_SHOWN + 4));
    }

    @Test
    public void onlyAnOpenQuestionCanBeAnswered() throws IOException {
        BeliefStore store = freshStore();
        Belief      claim = store.claim("walls stop movement", 4, List.of(1), List.of());

        assertThatThrownBy(() -> store.resolve(claim.id(), "yes", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    public void onlyALiveClaimCanBeRefuted() throws IOException {
        BeliefStore store = freshStore();
        Belief      claim = store.claim("walls stop movement", 4, List.of(1), List.of());
        store.refute(claim.id(), List.of(9), "no");

        assertThatThrownBy(() -> store.refute(claim.id(), List.of(10), "no again"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refuted");
    }

    @Test
    public void aClaimHasToSaySomething() throws IOException {
        BeliefStore store = freshStore();

        assertThatThrownBy(() -> store.claim("   ", 4, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.question("", 4, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void aBeliefNobodyHasIsNamedRatherThanIgnored() throws IOException {
        BeliefStore store = freshStore();

        assertThatThrownBy(() -> store.support("B7", List.of(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("B7");
        assertThat(store.get("B7")).isNull();
    }

    @Test
    public void aTornLogIsReportedRatherThanPartlyLoaded() throws IOException {
        BeliefStore store = freshStore();
        store.claim("walls stop movement", 4, List.of(1), List.of());
        Files.writeString(file, "{not json" + System.lineSeparator(), StandardCharsets.UTF_8,
                          java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(this::freshStore)
                .isInstanceOf(BeliefStoreException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    public void anEventOfAKindNothingKnowsIsReportedRatherThanSkipped() throws IOException {
        BeliefStore store = freshStore();
        store.claim("walls stop movement", 4, List.of(1), List.of());
        Files.writeString(file, "{\"kind\":\"invent\",\"id\":\"B1\"}" + System.lineSeparator(),
                          StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(this::freshStore)
                .isInstanceOf(BeliefStoreException.class)
                .hasMessageContaining("invent");
    }

    @Test
    public void anIdenticalIdIsNeverIssuedTwice() throws IOException {
        BeliefStore store = freshStore();
        store.claim("one", 1, List.of(), List.of());
        Files.writeString(file, Json.canonical(java.util.Map.of(
                                  "kind", "add", "id", "B2", "text", "written by hand",
                                  "status", "active", "ledger_length", 1,
                                  "evidence", List.of(), "tags", List.of(), "at", 0))
                                + System.lineSeparator(),
                          StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        BeliefStore reopened = freshStore();
        Belief      next     = reopened.claim("two", 2, List.of(), List.of());

        assertThat(next.id()).isNotEqualTo("B2");
        assertThat(reopened.get("B2").text()).isEqualTo("written by hand");
    }

    @Test
    public void beliefsReadBackAsValuesTheHarnessCanKeep() throws IOException {
        BeliefStore store = freshStore();
        store.claim("walls stop movement", 4, List.of(1), List.of("map"));

        Object value = store.toValue();

        assertThat(Json.at(value, "0.id")).isEqualTo("B1");
        assertThat(Json.at(value, "0.status")).isEqualTo("active");
        assertThat(Json.at(value, "0.text")).isEqualTo("walls stop movement");
        assertThat(Json.at(value, "0.evidence.0")).isEqualTo(1);
    }
}
