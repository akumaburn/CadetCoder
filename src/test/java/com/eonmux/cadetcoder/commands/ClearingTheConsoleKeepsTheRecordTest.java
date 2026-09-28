package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.session.TranscriptEntry;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /clear} empties the console and nothing else.
 *
 * <h2>What it must not touch</h2>
 *
 * <p>The session keeps two records of what happened: the session log, which the shell writes
 * line by line and never reads back, and the scrollback saved with the session, which is replayed
 * when the session is resumed. Emptying the transcript -- what Ctrl+L used to do -- emptied the
 * second, so a session resumed after a clear showed nothing from before it. Clearing is about the
 * screen: the regions are hidden from the console and kept for the save.</p>
 *
 * <h2>Why the model cannot use it</h2>
 *
 * <p>The console is the person's view of what the model did. A model able to clear it could
 * remove the record of its own work from in front of the person watching it.</p>
 */
class ClearingTheConsoleKeepsTheRecordTest {

    private MockedStatic<OutputRouter> routers;
    private OutputRouter               router;

    @BeforeEach
    void setUp() {
        router = mock(OutputRouter.class);
        when(router.commandPrefix()).thenReturn("/");
        routers = mockStatic(OutputRouter.class);
        routers.when(OutputRouter::getInstance).thenReturn(router);
    }

    @AfterEach
    void tearDown() {
        routers.close();
    }

    private static ShellTranscript withTwoCommands() {
        ShellTranscript transcript = new ShellTranscript(64, 500);
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "/status");
        transcript.append("On branch master\n");
        transcript.beginSegment(ShellTranscript.Kind.COMMAND, "/read notes.txt");
        transcript.append("the notes\npartial line");
        return transcript;
    }

    @Test
    void aclearedTranscriptShowsNothingButStillSavesEverything() {
        ShellTranscript transcript = withTwoCommands();

        transcript.clearView();

        assertThat(transcript.snapshot()).isEmpty();
        assertThat(transcript.segmentIds()).isEmpty();
        assertThat(transcript.segmentCount()).isZero();
        assertThat(transcript.totalLines()).isZero();
        List<TranscriptEntry> saved = transcript.persistable(64, 5000);
        assertThat(saved).extracting(TranscriptEntry::getTitle)
                         .containsExactly("/status", "/read notes.txt");
        assertThat(saved.get(1).getLines())
                .as("a line still arriving when the console was cleared is kept, not lost")
                .containsExactly("the notes", "partial line");
    }

    @Test
    void outputAfterAclearIsShownInAregionOfItsOwn() {
        ShellTranscript transcript = withTwoCommands();
        transcript.clearView();

        transcript.append("after the clear\n");

        List<ShellTranscript.Snapshot> shown = transcript.snapshot();
        assertThat(shown).hasSize(1);
        assertThat(shown.get(0).lines()).containsExactly("after the clear");
        assertThat(transcript.persistable(64, 5000)).hasSize(3);
    }

    @Test
    void theClearCommandAsksTheShellToClearItsConsole() {
        when(router.requestConsoleClear()).thenReturn(true);

        int exit = new ClearCommand().execute(new String[0]);

        assertThat(exit).isZero();
        verify(router).requestConsoleClear();
    }

    @Test
    void outsideTheShellThereIsNoConsoleToClear() {
        when(router.requestConsoleClear()).thenReturn(false);

        assertThat(new ClearCommand().execute(new String[0])).isZero();
    }

    @Test
    void themodelCannotClearThePersonsConsole() {
        when(router.requestConsoleClear()).thenReturn(true);

        int exit = ModelDispatch.run("clear", () -> new ClearCommand().execute(new String[0]));

        assertThat(exit).isNotZero();
        verify(router, never()).requestConsoleClear();
    }
}
