package com.eonmux.cadetcoder.error;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;

import java.io.IOException;
import java.nio.file.FileSystemException;

import static com.eonmux.cadetcoder.testsupport.ConsoleAssertions.assertError;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a user is told when an exception escapes a command's own error handling.
 */
public class ErrorHandlerTest {

    private TestOutputCapture outputCapture;
    private ErrorHandler      errorHandler;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        resetSingleton();
        errorHandler = ErrorHandler.getInstance();
    }

    private void resetSingleton() {
        try {
            java.lang.reflect.Field instance = ErrorHandler.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        resetSingleton();
    }

    @Test
    public void testGetInstance_ReturnsSingleton() {
        assertThat(ErrorHandler.getInstance()).isSameAs(ErrorHandler.getInstance());
    }

    @Test
    public void theMessageIsReportedOnce() {
        errorHandler.handleException(new Exception("Generic error"));

        String output = outputCapture.getAllOutput();
        assertError(output, "Generic error");
        assertThat(countOccurrences(output, "Generic error"))
                .as("one failure, one line about it")
                .isEqualTo(1);
    }

    @Test
    public void nothingIsSaidAboutRecoveriesThatCannotHelp() {
        errorHandler.handleException(new IOException("Test IO error"));

        String output = outputCapture.getAllOutput();
        assertError(output, "Test IO error");
        assertThat(output)
                .doesNotContain("Attempting to reload configuration")
                .doesNotContain("Configuration reloaded")
                .doesNotContain("Unhandled exception type");
    }

    @Test
    public void anIllegalStateIsNotAnsweredByResettingTheAiClient() {
        errorHandler.handleException(new IllegalStateException("ContextEngine has been closed"));

        String output = outputCapture.getAllOutput();
        assertError(output, "ContextEngine has been closed");
        assertThat(output)
                .doesNotContain("Attempting to reset AI client")
                .doesNotContain("AI client reset");
    }

    /**
     * The reason the {@code IOException} recovery had to go: it re-read the configuration from
     * disk, and the flags the user passed on the command line live only in memory.
     */
    @Test
    public void runtimeConfigurationSurvivesAnException() {
        ConfigManager manager = ConfigManager.getInstance();
        boolean       before  = manager.getConfig().getSecurity().isReadOnlyMode();
        manager.getConfig().getSecurity().setReadOnlyMode(true);
        try {
            errorHandler.handleException(new IOException("unrelated failure while reading a file"));

            assertThat(manager.getConfig().getSecurity().isReadOnlyMode())
                    .as("--read-only must not be revoked by an unrelated error")
                    .isTrue();
        } finally {
            manager.getConfig().getSecurity().setReadOnlyMode(before);
        }
    }

    @Test
    public void aMessagelessExceptionStillSaysWhatHappened() {
        errorHandler.handleException(new NullPointerException());

        assertError(outputCapture.getAllOutput(), "The command failed with NullPointerException.");
    }

    @Test
    public void aBareTokenMessageIsGivenItsType() {
        // Lucene and the java.nio file-system exceptions report a path and nothing else, which
        // arrived as a line naming a lock file with no indication of what had gone wrong with it.
        String line = ErrorHandler.describe(new FileSystemException("/project/.cadet/index/write.lock"));

        assertThat(line).isEqualTo("FileSystemException: /project/.cadet/index/write.lock");
    }

    @Test
    public void anOrdinarySentenceIsLeftAlone() {
        assertThat(ErrorHandler.describe(new IOException("Failed to push: remote rejected")))
                .isEqualTo("Failed to push: remote rejected");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int at    = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
