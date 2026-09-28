package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.agents.WorkerResult;
import com.eonmux.cadetcoder.agents.WorkerTask;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run's summary counts against what was started, not against what came back.
 *
 * <p><b>The defect</b>: the denominator was the size of the results list, so a run of five workers
 * that lost two reported "3 of 3 succeeded" and exited zero. The two that never reported were the
 * whole thing worth knowing, and the summary was arithmetically incapable of mentioning them.</p>
 */
class AtallyIsOutOfWhatWasStartedTest {

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
    }

    private static WorkerResult worker(int index, WorkerResult.Status status) {
        return new WorkerResult(new WorkerTask(index, "task " + index, "do it"), status,
                                List.of("said something"), 10, null);
    }

    private static int summarise(List<WorkerResult> results, int started) throws Exception {
        Method method = WorkersCommand.class.getDeclaredMethod(
                "summarise", List.class, int.class, long.class);
        method.setAccessible(true);
        return (int) method.invoke(null, results, started, 1500L);
    }

    @Test
    void workersThatNeverReportedAreCountedAsNotSucceeded() throws Exception {
        List<WorkerResult> cameBack = List.of(worker(1, WorkerResult.Status.COMPLETED),
                                              worker(2, WorkerResult.Status.COMPLETED),
                                              worker(3, WorkerResult.Status.COMPLETED));

        int exitCode = summarise(cameBack, 5);

        assertThat(output.getAllOutput()).contains("3 of 5 succeeded");
        assertThat(exitCode)
                .as("two workers are unaccounted for; that is not a clean run")
                .isEqualTo(1);
    }

    @Test
    void arunWhereEverybodySucceededSaysSoAndExitsZero() throws Exception {
        List<WorkerResult> cameBack = List.of(worker(1, WorkerResult.Status.COMPLETED),
                                              worker(2, WorkerResult.Status.COMPLETED));

        int exitCode = summarise(cameBack, 2);

        assertThat(output.getAllOutput()).contains("2 of 2 succeeded");
        assertThat(exitCode).isZero();
    }

    @Test
    void afailedWorkerIsCountedAgainstTheRun() throws Exception {
        List<WorkerResult> cameBack = List.of(worker(1, WorkerResult.Status.COMPLETED),
                                              worker(2, WorkerResult.Status.FAILED));

        int exitCode = summarise(cameBack, 2);

        assertThat(output.getAllOutput()).contains("1 of 2 succeeded");
        assertThat(exitCode).isEqualTo(1);
    }
}
