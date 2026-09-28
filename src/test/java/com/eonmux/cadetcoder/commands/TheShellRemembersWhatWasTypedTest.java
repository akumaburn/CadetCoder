package com.eonmux.cadetcoder.commands;

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
 * The shell's command history survives the things that end a shell.
 *
 * <h2>The defect</h2>
 *
 * <p>Every line typed was held in memory and the whole list was written over {@code history.log} in
 * one go, at the end, as one of the steps of a clean exit. Three things followed from that. A
 * session that did not exit cleanly -- a crash, a {@code kill}, a closed terminal -- left no trace
 * of itself, which is when a user most wants to see what they last ran. Two shells open at once
 * both loaded the file and both wrote it back, so whichever quit second silently threw away
 * everything the first had done. And the file was never bounded: each session read the whole of it
 * and wrote the whole of it back with more added, so it grew without limit and was read in full at
 * every start.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>A line is on disk as soon as it is typed, so nothing has to end tidily for it to be kept; a
 * second shell adds to the file rather than replacing it; and the file is held to a bounded number
 * of lines, keeping the most recent, so a machine that has run the shell for years starts as
 * quickly as one that has not.</p>
 */
public class TheShellRemembersWhatWasTypedTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path historyFile() {
        return folder.getRoot().toPath().resolve("history.log");
    }

    private List<String> onDisk() throws IOException {
        Path file = historyFile();
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }

    @Test
    public void aLineIsOnDiskAsSoonAsItIsTyped() throws IOException {
        CommandHistory history = new CommandHistory(historyFile());

        history.add("status");

        assertThat(onDisk())
                .as("nothing about the session has ended, and the line is already kept")
                .containsExactly("status");
    }

    @Test
    public void whatWasTypedBeforeIsThereWhenTheShellStartsAgain() throws IOException {
        CommandHistory first = new CommandHistory(historyFile());
        first.add("index");
        first.add("search retries");

        CommandHistory second = new CommandHistory(historyFile());

        assertThat(second.entries()).containsExactly("index", "search retries");
    }

    @Test
    public void aSecondShellAddsToTheFileRatherThanReplacingIt() throws IOException {
        CommandHistory first  = new CommandHistory(historyFile());
        first.add("one");
        CommandHistory second = new CommandHistory(historyFile());

        second.add("two");
        first.add("three");

        assertThat(onDisk())
                .as("whichever shell quits last used to decide what the other had done")
                .containsExactly("one", "two", "three");
    }

    @Test
    public void theFileDoesNotGrowWithoutLimit() throws IOException {
        CommandHistory history = new CommandHistory(historyFile());

        for (int typed = 0; typed < CommandHistory.KEPT_LINES + 250; typed++) {
            history.add("command-" + typed);
        }

        assertThat(onDisk()).hasSizeLessThanOrEqualTo(CommandHistory.KEPT_LINES);
    }

    @Test
    public void whatIsDroppedIsTheOldestAndWhatIsKeptIsTheMostRecent() throws IOException {
        CommandHistory history = new CommandHistory(historyFile());

        for (int typed = 0; typed < CommandHistory.KEPT_LINES + 250; typed++) {
            history.add("command-" + typed);
        }

        int last = CommandHistory.KEPT_LINES + 249;
        assertThat(onDisk())
                .contains("command-" + last)
                .doesNotContain("command-0");
    }

    @Test
    public void aFileThatIsAlreadyTooLongIsCutDownWhenItIsRead() throws IOException {
        StringBuilder oversized = new StringBuilder();
        for (int line = 0; line < CommandHistory.KEPT_LINES * 2; line++) {
            oversized.append("old-command-").append(line).append('\n');
        }
        Files.writeString(historyFile(), oversized.toString());

        CommandHistory history = new CommandHistory(historyFile());

        assertThat(history.entries()).hasSize(CommandHistory.KEPT_LINES);
        assertThat(history.entries().get(history.entries().size() - 1))
                .isEqualTo("old-command-" + (CommandHistory.KEPT_LINES * 2 - 1));
    }

    @Test
    public void aHistoryFileThatIsNotThereYetIsAnEmptyHistory() {
        assertThat(new CommandHistory(historyFile()).entries()).isEmpty();
    }

    @Test
    public void aDirectoryThatCannotBeWrittenDoesNotStopTheShellRemembering() throws IOException {
        Path unwritable = folder.getRoot().toPath().resolve("no-such-directory/history.log");

        CommandHistory history = new CommandHistory(unwritable);
        history.add("status");

        assertThat(history.entries())
                .as("the shell still navigates its own history when the file cannot be written")
                .containsExactly("status");
    }

    @Test
    public void whatIsInMemoryIsNotHandedOutForEditing() throws IOException {
        CommandHistory history = new CommandHistory(historyFile());
        history.add("status");

        List<String> entries = history.entries();

        assertThatThrownBy(() -> entries.add("something the shell never saw"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void aBlankLineIsNotRemembered() throws IOException {
        CommandHistory history = new CommandHistory(historyFile());

        history.add("   ");
        history.add(null);

        assertThat(history.entries()).isEmpty();
        assertThat(onDisk()).isEmpty();
    }
}
