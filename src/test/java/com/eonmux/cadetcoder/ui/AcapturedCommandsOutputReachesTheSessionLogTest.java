package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.logging.SessionLogger;

import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The session log records what a command printed, on the plain command line as well as in the shell.
 *
 * <p><b>The defect</b>: {@link UnifiedOutput} states the invariant it works to -- a line is caught
 * in exactly one place and recorded there, by the router while the shell is drawing the screen and
 * by these methods when it is not. The second half was not true. Outside the shell, a line taken by
 * the calling thread's collector was answered with {@code true} and went no further, so it reached
 * the model and the caller and never reached {@link SessionLogger#logCommandOutput}. Every command
 * a non-TUI run captured -- which is all of them -- was recorded as having started and ended with
 * nothing in between, and the file that exists to say what happened said the least about the runs
 * least able to show it any other way.</p>
 *
 * <p>{@code OutputCapture}'s stream bridge already does this for a raw {@code System.out} write a
 * collector takes. The two halves have to agree, or a line is either lost or recorded twice.</p>
 */
public class AcapturedCommandsOutputReachesTheSessionLogTest {

    @Test
    public void alineTakenByAcollectorIsStillWrittenToTheSessionLog() {
        assertThat(OutputRouter.getInstance().isRouting())
                .as("this case is about the path taken when the shell is NOT drawing the screen")
                .isFalse();

        try (MockedStatic<SessionLogger> loggers = mockStatic(SessionLogger.class)) {
            SessionLogger logger = mock(SessionLogger.class);
            loggers.when(SessionLogger::getInstance).thenReturn(logger);

            OutputCapture.collect(() -> UnifiedOutput.println("a captured line"));

            verify(logger).logCommandOutput(contains("a captured line"));
        }
    }

    /** Errors, fragments and formatted output go through the same door and are recorded too. */
    @Test
    public void everyWayOfPrintingIsRecordedRatherThanOnlyPrintln() {
        try (MockedStatic<SessionLogger> loggers = mockStatic(SessionLogger.class)) {
            SessionLogger logger = mock(SessionLogger.class);
            loggers.when(SessionLogger::getInstance).thenReturn(logger);

            OutputCapture.collect(() -> {
                UnifiedOutput.printlnErr("could not read pom.xml");
                UnifiedOutput.printf("%d of %d%n", 2, 5);
            });

            verify(logger).logCommandOutput(contains("could not read pom.xml"));
            verify(logger).logCommandOutput(contains("2 of 5"));
        }
    }

    /**
     * Nothing is recorded twice. A line nobody is collecting goes to the console, and the console is
     * where the router records it when there is one; recording it here as well would put every line
     * of an uncaptured run in the log twice.
     */
    @Test
    public void alineNobodyIsCollectingIsLeftToWhoeverPrintsIt() {
        try (MockedStatic<SessionLogger> loggers = mockStatic(SessionLogger.class)) {
            SessionLogger logger = mock(SessionLogger.class);
            loggers.when(SessionLogger::getInstance).thenReturn(logger);

            UnifiedOutput.println("");

            verify(logger, never()).logCommandOutput(org.mockito.ArgumentMatchers.anyString());
        }
    }
}
