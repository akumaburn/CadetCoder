package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.testing.Await;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The session log is on by default, and everything a command prints goes into it.
 *
 * <p>{@code OutputRouter} hands every line of command output to {@link
 * SessionLogger#logCommandOutput}, so whatever {@code bash} echoed, or a provider quoted back in an
 * error, is written to a file that outlives the session and is not owner-restricted. Two callers
 * had been audited -- the LLM request/response bodies and headers, and the line the user typed --
 * and each masked its own copy. Everything else arrived verbatim.</p>
 *
 * <p>The masking therefore happens at the single point an entry is queued, which every caller
 * passes through, instead of beside whichever caller was looked at most recently.</p>
 */
public class SessionLogCarriesNoCredentialTest {

    private static final String SECRET = "sk-live-fedcba9876543210FEDCBA";

    private static String sessionLog() {
        StringBuilder all = new StringBuilder();
        for (Path file : new Path[] {SessionLogger.getInstance().getSessionLogFile(),
                                     SessionLogger.getInstance().getStructuredLogFile()}) {
            if (file != null && Files.exists(file)) {
                try {
                    all.append(Files.readString(file, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    // An unreadable half-written line is not the answer; keep what was read.
                }
            }
        }
        return all.toString();
    }

    private static void awaitLogged(String marker) {
        assertThat(SessionLogger.getInstance().getSessionLogFile())
                .as("session logging is on by default; with no log file this test proves nothing")
                .isNotNull();
        Await.until("the session log to carry " + marker, () -> sessionLog().contains(marker));
    }

    @Test
    public void aKeyEchoedByACommandIsNotWrittenToTheSessionLog() {
        String marker = "session-redaction-output-" + UUID.randomUUID();

        SessionLogger.getInstance().logCommandOutput(marker + " OPENAI_API_KEY=" + SECRET);

        awaitLogged(marker);
        assertThat(sessionLog())
                .as("every line a command prints reaches this file; a key among them must not")
                .doesNotContain(SECRET)
                .doesNotContain("fedcba9876543210");
    }

    @Test
    public void aKeyInASessionEventIsNotWrittenToTheSessionLog() {
        String marker = "session-redaction-event-" + UUID.randomUUID();

        SessionLogger.getInstance().logSessionEvent(marker, "restored token " + SECRET);

        awaitLogged(marker);
        assertThat(sessionLog()).doesNotContain(SECRET);
    }

    @Test
    public void aKeyQuotedByAnExceptionIsNotWrittenToTheSessionLog() {
        String marker = "session-redaction-error-" + UUID.randomUUID();

        SessionLogger.getInstance().logError(marker, "request failed",
                                             new IllegalStateException("rejected key " + SECRET));

        awaitLogged(marker);
        assertThat(sessionLog())
                .as("a provider that quotes the key back does it in the message of the failure")
                .doesNotContain(SECRET);
    }

    /** Masking must not cost the log its usefulness. */
    @Test
    public void ordinaryOutputIsWrittenVerbatim() {
        String marker = "session-redaction-plain-" + UUID.randomUUID();

        SessionLogger.getInstance().logCommandOutput(marker + " Tests run: 12, Failures: 0");

        awaitLogged(marker);
        assertThat(sessionLog()).contains("Tests run: 12, Failures: 0");
    }
}
