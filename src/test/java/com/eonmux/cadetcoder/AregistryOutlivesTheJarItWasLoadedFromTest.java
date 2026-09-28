package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.commands.ModelDispatch;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.reflections.Reflections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

/**
 * Every registry has every command, however long the program has been running.
 *
 * <h2>The defect</h2>
 *
 * <p>Each registry found its commands by scanning the classpath again, and every loop pass builds
 * a registry of its own. The jar the program ran from was replaced by a rebuild during a
 * {@code loopfresh} run. The classes already loaded went on working, so pass 1 finished; the scan
 * for pass 2 read a jar that was no longer there, found nothing, and raised nothing. Every command
 * the model asked for from then on was "Unknown command" -- {@code ls}, {@code read},
 * {@code help} -- and twelve passes spent themselves guessing at a tool set that was empty. The
 * commands are now found once, when the program starts, and a registry built later is made from
 * that list.</p>
 *
 * <h2>Why the model is not sent to /help</h2>
 *
 * <p>An unknown command told the model to run {@code /help} and that anything else "is sent to the
 * AI". Neither is true of a model's action, and the model spent its next turns running
 * {@code help} and {@code /help}. It is given the names it can use instead.</p>
 */
class AregistryOutlivesTheJarItWasLoadedFromTest {

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

    @Test
    void aregistryBuiltAfterTheClasspathCannotBeScannedHasEveryCommand() {
        int known = new CommandRegistry().getCommands().size();

        try (MockedConstruction<Reflections> unreadable = mockConstruction(Reflections.class)) {
            CommandRegistry later = new CommandRegistry();

            assertThat(later.getCommands()).hasSize(known).containsKeys("ls", "read", "multiedit");
        }
    }

    @Test
    void amodelAskingForAnUnknownCommandIsGivenTheNamesItCanUse() {
        CommandRegistry registry = new CommandRegistry();

        int exit = ModelDispatch.run("frobnicate",
                () -> registry.executeCommand("frobnicate", new String[] {"."}, false));

        assertThat(exit).isNotZero();
        assertThat(output.getAllOutput())
                .contains("Unknown command: frobnicate")
                .contains("read").contains("grep")
                .doesNotContain("sent to the AI")
                .doesNotContain("help' to list");
    }

    @Test
    void apersonAskingForAnUnknownCommandIsStillSentToHelp() {
        new CommandRegistry().executeCommand("frobnicate", new String[0], false);

        assertThat(output.getAllOutput()).contains("help' to list every command");
    }
}
