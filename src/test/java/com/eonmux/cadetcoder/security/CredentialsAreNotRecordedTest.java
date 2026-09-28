package com.eonmux.cadetcoder.security;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A credential typed at the prompt must not outlive the keystroke.
 *
 * <p>{@code config.json} is written {@code rw-------} on purpose. The session log,
 * {@code history.log} and the saved transcript are not, and all three recorded the command line
 * verbatim -- so {@code config ai.apiKey sk-...} put the key in three files more readable than the
 * one it was being stored in. {@code config} masked the key when it echoed it back, which covered
 * the terminal and nothing that persists.</p>
 */
public class CredentialsAreNotRecordedTest {

    @Test
    public void theValueOfACredentialSettingIsMasked() {
        assertThat(SecretRedactor.redactCommandLine("config ai.apiKey sk-abcdef0123456789"))
                .doesNotContain("abcdef0123456789")
                .as("the setting name is what makes the line readable later, and is not the secret")
                .contains("ai.apiKey");
    }

    @Test
    public void aKeyWithNoRecognisableShapeIsStillMaskedWhenTheSettingNamesIt() {
        assertThat(SecretRedactor.redactCommandLine("config ai.apiKey plain-looking-secret-value"))
                .as("masking on the setting name works without having to recognise the value")
                .doesNotContain("plain-looking-secret-value");
    }

    @Test
    public void loginMasksEverythingAfterTheProvider() {
        String recorded = SecretRedactor.redactCommandLine("login openai sk-abcdef0123456789");

        assertThat(recorded).contains("openai").doesNotContain("abcdef0123456789");
    }

    @Test
    public void theSlashPrefixedFormTypedInTheShellIsTreatedTheSame() {
        assertThat(SecretRedactor.redactCommandLine("/config ai.apiKey sk-abcdef0123456789"))
                .doesNotContain("abcdef0123456789");
    }

    /** The name-based rules cannot cover a key typed somewhere nobody anticipated. */
    @Test
    public void aKeyShapeIsMaskedWhereverItAppears() {
        assertThat(SecretRedactor.redactCommandLine("bash curl -H \"Authorization: Bearer abcdefghij\""))
                .doesNotContain("abcdefghij");
        assertThat(SecretRedactor.redactCommandLine("chat my key is sk-abcdef0123456789 by the way"))
                .doesNotContain("abcdef0123456789");
    }

    @Test
    public void anOrdinaryCommandIsRecordedAsTyped() {
        assertThat(SecretRedactor.redactCommandLine("read src/main/java/Main.java"))
                .isEqualTo("read src/main/java/Main.java");
        assertThat(SecretRedactor.redactCommandLine("config ui.colorTheme dracula"))
                .as("a non-credential setting must stay legible in the history")
                .contains("dracula");
    }

    @Test
    public void argumentRedactionMatchesTheCommandLineForm() {
        assertThat(SecretRedactor.redactArguments("config",
                new String[] {"ai.apiKey", "sk-abcdef0123456789"}))
                .containsExactly("ai.apiKey", SecretRedactor.REDACTED);

        assertThat(SecretRedactor.redactArguments("read", new String[] {"Main.java"}))
                .containsExactly("Main.java");
    }

    @Test
    public void nothingBlowsUpOnEmptyOrNullInput() {
        assertThat(SecretRedactor.redactCommandLine(null)).isNull();
        assertThat(SecretRedactor.redactCommandLine("   ")).isEqualTo("   ");
        assertThat(SecretRedactor.redactArguments("config", new String[0])).isEmpty();
        assertThat(SecretRedactor.redactArguments(null, null)).isNull();
    }
}
