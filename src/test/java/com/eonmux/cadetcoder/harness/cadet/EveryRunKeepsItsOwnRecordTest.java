package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.tools.ToolSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A run's ledger is the only evidence there is that anything it did really happened, and it is worth
 * nothing if a later run writes over it.
 *
 * <p>The obvious naming -- one directory per project, or one per second -- loses exactly the runs
 * worth comparing: the second attempt at a task overwrites the first, and two runs started together
 * append into each other's ledger, which breaks the hash chain that certification depends on. These
 * tests fix the record inside the project, unique per run, present before anything writes to it, and
 * invisible to the agent looking at its own workspace -- a record that showed up in the observation
 * would make every run's own writing look like the world changing under it.</p>
 */
class EveryRunKeepsItsOwnRecordTest {

    /** How many records to make at once when asking whether two runs can collide. */
    private static final int AT_ONCE = 50;

    @Test
    void theRecordOfARunIsKeptInsideTheProjectItWasRunAgainst(@TempDir Path project) {
        RunRecord record = RunRecord.under(project);

        assertThat(record.directory()).startsWith(project.resolve(RunRecord.RUNS));
    }

    @Test
    void aRecordIsThereToBeWrittenToBeforeTheRunStarts(@TempDir Path project) throws IOException {
        RunRecord record = RunRecord.under(project);

        assertThat(Files.isDirectory(record.directory())).isTrue();
        Files.writeString(record.ledger(), "{}");
        assertThat(Files.readString(record.ledger())).isEqualTo("{}");
    }

    @Test
    void twoRunsStartedInTheSameSecondDoNotShareARecord(@TempDir Path project) {
        Set<Path> kept = new LinkedHashSet<>();
        for (int made = 0; made < AT_ONCE; made++) {
            kept.add(RunRecord.under(project).directory());
        }

        assertThat(kept).hasSize(AT_ONCE);
    }

    @Test
    void whatARunEstablishesIsFoundUnderTheNamesTheRestOfTheSystemAlreadyUses(
            @TempDir Path project) {
        RunRecord record = RunRecord.under(project);

        assertThat(record.ledger()).isEqualTo(record.directory().resolve(ToolSession.LEDGER_NAME));
        assertThat(record.beliefs()).isEqualTo(record.directory().resolve(ToolSession.BELIEFS_NAME));
        assertThat(record.notes()).isEqualTo(record.directory().resolve(ToolSession.NOTES_NAME));
    }

    @Test
    void aRecordIsNamedAfterTheMomentTheRunBegan(@TempDir Path project) {
        String name = RunRecord.under(project).name();

        assertThat(name).matches("\\d{8}-\\d{6}-.+");
    }

    @Test
    void theRecordIsNoPartOfTheWorkspaceTheAgentIsShown(@TempDir Path project) throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        WorkspaceTree before = WorkspaceTree.of(project);

        RunRecord record = RunRecord.under(project);
        Files.writeString(record.ledger(), "{\"kind\":\"reset\"}");

        WorkspaceTree after = WorkspaceTree.of(project);
        assertThat(after.paths()).containsExactlyElementsOf(before.paths());
        assertThat(after.digest()).isEqualTo(before.digest());
    }

    @Test
    void aProjectThatIsNotThereIsRefusedRatherThanConjuredIntoExistence(@TempDir Path project) {
        Path missing = project.resolve("nowhere");

        assertThatThrownBy(() -> RunRecord.under(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nowhere");
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void aRecordWithoutADirectoryIsRefusedWhereItIsMadeRatherThanWhereItIsUsed() {
        assertThatThrownBy(() -> new RunRecord(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
