package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --help} is answered by every command, before the command is reached.
 *
 * <p><b>The defect</b>: only the three picocli-parsed commands answered it. Everything else treated
 * it as an argument, so the first thing anyone types at an unfamiliar command did something else:
 * {@code chat --help} sent the two words to the model and billed the call, {@code websearch --help}
 * searched the web for them, {@code bash --help} offered to run {@code --help} as a shell command,
 * {@code plan --help} opened an interactive planning session, and {@code theme --help} answered
 * "Unknown action". Each command owns one description of its own usage already -- the one
 * {@code help &lt;command&gt;} prints -- so the answer existed everywhere and was reachable almost
 * nowhere.</p>
 *
 * <p>Answered in the registry rather than in each command: it is the single point every invocation
 * passes through, from the command line and from the shell's prompt alike, so a command added later
 * answers it without having to remember to.</p>
 */
public class EveryCommandAnswersWhenAskedHowItIsUsedTest {

    /**
     * Commands whose usage can be asked for in this test without side effects even when the
     * question is NOT intercepted -- so the test fails on the defect rather than hanging on a
     * prompt, opening an interactive mode, or calling a provider.
     */
    private static final String[] SAFE_TO_ASK = {
            "todoread", "theme", "models", "session", "compact", "runs", "config", "timer"
    };

    private CommandRegistry   registry;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        registry      = new CommandRegistry();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    @Test
    public void everyCommandHasAusageToAnswerWith() {
        List<String> silent = new ArrayList<>();
        for (Map.Entry<String, CommandRegistry.Command> entry : registry.getCommands().entrySet()) {
            String usage = entry.getValue().getUsage();
            if (usage == null || usage.trim().isEmpty()) {
                silent.add(entry.getKey());
            }
        }

        assertThat(silent).as("commands with nothing to say when asked how they are used").isEmpty();
    }

    @Test
    public void askingHowAcommandIsUsedPrintsItsUsageAndNothingElseHappens() {
        for (String name : SAFE_TO_ASK) {
            outputCapture.stopCapture();
            outputCapture = new TestOutputCapture();
            outputCapture.startCapture();

            int exitCode = registry.executeCommand(name, new String[] {"--help"}, false);
            String output = outputCapture.getAllOutput();

            assertThat(exitCode).as("%s --help", name).isEqualTo(0);
            assertThat(output).as("%s --help prints its own usage", name).contains(name);
            assertThat(output).as("%s --help is not an unknown argument", name)
                              .doesNotContain("Unknown");
        }
    }

    @Test
    public void theShortSpellingIsAnsweredTheSameWay() {
        int exitCode = registry.executeCommand("theme", new String[] {"-h"}, false);

        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("theme");
        assertThat(outputCapture.getAllOutput()).doesNotContain("Unknown");
    }

    /** Only as the FIRST argument: further along it is the command's own to interpret. */
    @Test
    public void aLaterArgumentIsTheCommandsOwnBusiness() {
        int exitCode = registry.executeCommand("runs", new String[] {"show", "--help"}, false);

        assertThat(exitCode)
                .as("this asks to see a run named '--help', and there is none")
                .isEqualTo(1);
        assertThat(outputCapture.getAllOutput())
                .as("the command saw the argument rather than the registry swallowing it")
                .contains("--help");
    }
}
