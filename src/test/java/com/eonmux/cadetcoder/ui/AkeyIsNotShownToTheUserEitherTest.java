package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A credential quoted back by a provider does not reach the screen.
 *
 * <p><b>The defect</b>: every file sink was taught to mask secrets -- the rotating log, the debug
 * log, the session log -- and the console was not. The case the masking exists for is a provider's
 * own 401 body, which echoes the key it rejected, quoted into the error that reports it. It was
 * printed in full; and under the interactive shell the same text goes on into the transcript that
 * is saved to {@code session.json}.</p>
 */
class AkeyIsNotShownToTheUserEitherTest {

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        output = new TestOutputCapture();
    }

    @AfterEach
    void tearDown() {
        output.restore();
    }

    @Test
    void anErrorQuotingAkeyIsShownWithoutIt() {
        ThemedOutputFormatter.printError(
                "Provider rejected the request: {\"error\":\"invalid key sk-abcdefghijklmnopqrstuvwxyz012345\"}");

        String shown = output.getAllOutput();
        assertThat(shown).doesNotContain("sk-abcdefghijklmnopqrstuvwxyz012345");
        assertThat(shown)
                .as("the message still has to say what went wrong")
                .contains("Provider rejected the request");
    }

    @Test
    void awarningQuotingAbearerTokenIsShownWithoutIt() {
        ThemedOutputFormatter.printWarning("retrying with header Authorization: Bearer sk-livekey1234567890abcdef");

        assertThat(output.getAllOutput()).doesNotContain("sk-livekey1234567890abcdef");
    }

    @Test
    void anOrdinaryMessageIsUntouched() {
        ThemedOutputFormatter.printError("File not found: src/main/java/Foo.java");

        assertThat(output.getAllOutput()).contains("File not found: src/main/java/Foo.java");
    }
}
