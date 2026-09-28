package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.testing.Await;
import com.eonmux.cadetcoder.ui.ThemedOutputFormatter;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rotating log file keeps no credential either.
 *
 * <p>CadetCoder persists to three files, and the masking rule was enforced at two of them. The
 * third is this one -- {@code logs/cadet.log}, named by {@code logging.logFile}, which is set by
 * default and so is written on every run. {@code ThemedOutputFormatter} copies every error, warning
 * and success line the user is shown into it, and {@code ObservabilityLogger} copies every failure
 * into it beside the debug log, so it receives the same text that the other two were taught to
 * mask.</p>
 */
public class RotatingLogCarriesNoCredentialTest {

    /**
     * A different key every run, because the log file is append-only and outlives the run.
     *
     * <p>A constant would be found in the lines an EARLIER run wrote, so the test would keep
     * failing after the defect was fixed -- and, worse, a shared constant makes the same assertion
     * pass for the wrong reason once the stale lines are cleaned away.</p>
     */
    private final String secret = "sk-live-" + UUID.randomUUID().toString().replace("-", "");

    private static Path configuredLogFile() {
        Configuration config = ConfigManager.getInstance().getConfig();
        Path declared = Paths.get(config.getLogging().getLogFile());
        return declared.isAbsolute() ? declared : Paths.get(config.getBaseDir()).resolve(declared);
    }

    /**
     * Reads the log once a trailing marker has reached it.
     *
     * <p>The writer is a background thread, so asserting on the file before the entry lands would
     * pass whether or not the masking works. A marker logged after the entry under test is on the
     * far side of a single-threaded queue: once it is visible, so is everything before it.</p>
     */
    private String logContentsAfter(String marker) throws IOException {
        CadetLogger.getLogger("RotatingLogCarriesNoCredentialTest").infoToFile(marker);

        Path logFile = configuredLogFile();
        Await.until("the marker to reach " + logFile,
                () -> readOrEmpty(logFile).contains(marker));
        return Files.readString(logFile, StandardCharsets.UTF_8);
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    @Test
    public void aKeyInARecordedFailureIsNotWrittenToTheRotatingLog() throws Exception {
        CadetLogger.getLogger("RotatingLogCarriesNoCredentialTest")
                   .errorToFile("provider rejected key " + secret);

        assertThat(logContentsAfter("rotating-log-failure-" + UUID.randomUUID()))
                .as("the log file outlives the session and is readable by anyone who can read the "
                    + "home directory; a key must not survive in it")
                .doesNotContain(secret);
    }

    @Test
    public void aKeyQuotedByAnExceptionIsNotWrittenToTheRotatingLog() throws Exception {
        ObservabilityLogger.forComponent("RotatingLogCarriesNoCredentialTest")
                           .errorQuietly("send_request", "the provider refused the request",
                                         new IOException("401 for Authorization: Bearer " + secret));

        assertThat(logContentsAfter("rotating-log-exception-" + UUID.randomUUID()))
                .as("a failure records the exception verbatim, and an exception is where a rejected "
                    + "credential is most likely to be quoted back")
                .doesNotContain(secret);
    }

    @Test
    public void aKeyInALineTheUserIsShownIsNotWrittenToTheRotatingLog() throws Exception {
        ThemedOutputFormatter.printError("Error: invalid api key " + secret);

        assertThat(logContentsAfter("rotating-log-printed-" + UUID.randomUUID()))
                .as("every error line the formatter prints is copied into the log file, so the "
                    + "masking cannot depend on which logger a command reached for")
                .doesNotContain(secret);
    }

    /** Masking that ate ordinary text would make the log useless. */
    @Test
    public void anOrdinaryLineIsStillWrittenVerbatim() throws Exception {
        String message = "resolved 42 files under src/main/java, run " + UUID.randomUUID();
        CadetLogger.getLogger("RotatingLogCarriesNoCredentialTest").infoToFile(message);

        assertThat(logContentsAfter("rotating-log-control-" + UUID.randomUUID()))
                .contains(message);
    }
}
