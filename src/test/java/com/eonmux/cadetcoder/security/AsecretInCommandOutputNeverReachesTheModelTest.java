package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.ui.CapturedRun;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Credentials printed by a command, on their way to the model and to the terminal.
 *
 * <h2>The defect</h2>
 *
 * <p>{@link SecretRedactor} was wired into every logger and into nothing else. A command's captured
 * output went to the model through {@code CommandOutputBudget.forPrompt} and to the console through
 * {@code UnifiedOutput.println}, and neither redacted. So a command the security rules allow --
 * {@code read} of a config file, {@code bash env}, a provider echoing a key back in a 401 body --
 * put the key verbatim into the prompt, into the provider's request, and onto the screen.</p>
 *
 * <p>The rule belongs where the output is collected rather than at each place that reads it. Both
 * agent paths capture through {@link CapturedRun}: the classic loop at
 * {@code ActionRun.dispatch} and the harness at {@code CommandEnvironment.act}. A rule applied
 * there covers the model, the console echo, the debug record and a worker's transcript at once, and
 * there is no second reader to forget.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That capturing redacts, that it does so for every credential shape the redactor knows rather
 * than for one of them, that the exit code and ordinary text are untouched, and that redaction
 * happens before anything measures the output, so what is counted and cut is what will be sent.</p>
 */
public class AsecretInCommandOutputNeverReachesTheModelTest {

    /** A command that prints {@code text} and succeeds. */
    private static CapturedRun.Result printing(String text) {
        return CapturedRun.of(() -> {
            System.out.println(text);
            return 0;
        });
    }

    @Test
    public void akeyPrintedByACommandIsNotHandedBack() {
        CapturedRun.Result ran = printing("ai.apiKey=sk-abcdefghijklmnopqrstuvwxyz012345");

        assertThat(ran.output()).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
        assertThat(ran.output()).contains("***");
    }

    @Test
    public void everyShapeTheRedactorKnowsIsCovered() {
        // One test rather than one per provider: they reach the same rule, and a fix that covered
        // the shape someone happened to test would leave the rest in the clear.
        String[] secrets = {
                "sk-abcdefghijklmnopqrstuvwxyz012345",
                "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                "AIzaSyABCDEFGHIJKLMNOPQRSTUVWXYZ0123456",
                "gsk_abcdefghijklmnopqrstuvwxyz0123456789",
                "xai-abcdefghijklmnopqrstuvwxyz0123456789",
                "AKIAIOSFODNN7EXAMPLE",
                "user_" + "a".repeat(88),
        };
        for (String secret : secrets) {
            assertThat(printing("the value is " + secret).output())
                    .as("a command printing " + secret.substring(0, 6) + "...")
                    .doesNotContain(secret);
        }
    }

    @Test
    public void aBearerHeaderEchoedBackByAProviderIsNotHandedBack() {
        // The case that actually happens: a 401 body quotes the request's own Authorization header.
        CapturedRun.Result ran = printing("401 {\"error\":\"bad Authorization: Bearer abc123def456\"}");

        assertThat(ran.output()).doesNotContain("abc123def456");
    }

    @Test
    public void ordinaryOutputIsUntouched() {
        CapturedRun.Result ran = printing("src/main/java/Main.java  1200 bytes");

        assertThat(ran.output()).contains("src/main/java/Main.java  1200 bytes");
    }

    @Test
    public void theExitCodeIsNotAffected() {
        CapturedRun.Result ran = CapturedRun.of(() -> {
            System.out.println("sk-abcdefghijklmnopqrstuvwxyz012345");
            return 3;
        });

        assertThat(ran.exitCode()).isEqualTo(3);
    }

    @Test
    public void redactionHappensBeforeAnythingMeasuresTheOutput() {
        // The budget counts characters and cuts at a limit. Redacting afterwards would mean the cut
        // was made against text that is not what gets sent, and a secret sitting past the cut would
        // escape the redactor entirely on the path that keeps the whole output -- the debug record.
        CapturedRun.Result ran = printing("sk-abcdefghijklmnopqrstuvwxyz012345");

        assertThat(ran.output())
                .as("what CapturedRun hands back is already safe, so every reader of it is")
                .doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
    }
}
