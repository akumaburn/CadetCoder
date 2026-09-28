package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing is announced before it has been decided.
 *
 * <p><b>The defect</b>: {@code quit} printed "Exiting CadetCoder." and only then asked whether it
 * was allowed to exit. On a non-interactive host the answer is no -- the sentinel goes back and the
 * caller decides -- so the user was told the tool was exiting by a run that carried straight on.</p>
 */
class AnAnnouncementFollowsTheDecisionTest {

    private TestOutputCapture output;
    private String            wasInteractive;

    @BeforeEach
    void setUp() {
        wasInteractive = System.getProperty("cadet.interactive");
        output         = new TestOutputCapture();
        output.startCapture();
    }

    @AfterEach
    void tearDown() {
        output.stopCapture();
        if (wasInteractive == null) {
            System.clearProperty("cadet.interactive");
        } else {
            System.setProperty("cadet.interactive", wasInteractive);
        }
    }

    @Test
    void ahostThatIsNotExitingIsNotToldThatItIs() {
        System.setProperty("cadet.interactive", "false");

        int exitCode = new QuitCommand().execute(new String[] {});

        assertThat(exitCode).isEqualTo(QuitCommand.EXIT_REQUESTED);
        assertThat(output.getAllOutput())
                .as("nothing here is exiting; the caller was handed the decision")
                .doesNotContain("Exiting CadetCoder");
    }

    @Test
    void amodelAskingToStopEndsItsRunAndSaysSo() {
        System.setProperty("cadet.interactive", "true");
        int exitCode = ModelDispatch.run("quit", () -> new QuitCommand().execute(new String[] {}));

        assertThat(exitCode).isEqualTo(QuitCommand.EXIT_REQUESTED);

        assertThat(output.getAllOutput())
                .contains("ending this run rather than the session")
                .doesNotContain("Exiting CadetCoder");
    }
}
