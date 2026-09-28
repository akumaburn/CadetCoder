package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.logging.SessionLogger;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Output collected for a worker is still written to the session log.
 *
 * <p><b>The defect</b>: the session log is written by {@link OutputRouter}, and the thread-aware
 * bridge is installed in front of it, permanently, the first time anything is captured. A capturing
 * thread's output was taken by the sink and returned before it could reach the router -- so from the
 * first captured run onwards a worker's entire transcript was absent from the session log, while the
 * same lines printed by a non-capturing thread were still recorded. Nothing reported the loss.</p>
 */
class AcapturedLineIsStillOnTheRecordTest {

    @Test
    void whatAworkerPrintsWhileBeingCapturedIsWrittenToTheSessionLog() {
        OutputCapture.installStreamBridge();

        List<String> logged = new ArrayList<>();
        try (MockedStatic<SessionLogger> loggers = mockStatic(SessionLogger.class)) {
            SessionLogger recorder = mock(SessionLogger.class);
            org.mockito.Mockito.doAnswer(call -> logged.add(call.getArgument(0)))
                               .when(recorder).logCommandOutput(anyString());
            loggers.when(SessionLogger::getInstance).thenReturn(recorder);

            CapturedRun.Result result = CapturedRun.of(() -> {
                System.out.print("what the worker said" + System.lineSeparator());
                return 0;
            });

            assertThat(result.output()).contains("what the worker said");
        }

        assertThat(String.join("", logged))
                .as("the capture took it; that is not a reason for the record not to have it")
                .contains("what the worker said");
    }

    @Test
    void alineIsNotWrittenToTheRecordTwice() {
        OutputCapture.installStreamBridge();

        List<String> logged = new ArrayList<>();
        try (MockedStatic<SessionLogger> loggers = mockStatic(SessionLogger.class)) {
            SessionLogger recorder = mock(SessionLogger.class);
            org.mockito.Mockito.doAnswer(call -> logged.add(call.getArgument(0)))
                               .when(recorder).logCommandOutput(anyString());
            loggers.when(SessionLogger::getInstance).thenReturn(recorder);

            CapturedRun.of(() -> {
                System.out.print("said once" + System.lineSeparator());
                return 0;
            });
        }

        long times = logged.stream().filter(entry -> entry.contains("said once")).count();
        assertThat(times).isEqualTo(1);
    }
}
