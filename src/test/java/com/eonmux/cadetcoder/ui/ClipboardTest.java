package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class ClipboardTest {

    /** ESC (0x1B) and BEL (0x07) framing of an OSC-52 sequence. */
    private static final String ESC = "\u001b";
    private static final String BEL = "";
    private static final String PREFIX = ESC + "]52;c;";

    @Test
    public void osc52ReturnsEmptyForNull() {
        assertThat(Clipboard.osc52(null)).isEmpty();
    }

    @Test
    public void osc52ReturnsEmptyForEmpty() {
        assertThat(Clipboard.osc52("")).isEmpty();
    }

    @Test
    public void osc52EncodesSimpleAsciiExactly() {
        String expected = PREFIX
                + Base64.getEncoder().encodeToString("hi".getBytes(StandardCharsets.UTF_8))
                + BEL;
        assertThat(Clipboard.osc52("hi")).isEqualTo(expected);
    }

    @Test
    public void osc52IsFramedByEscPrefixAndBelSuffix() {
        String sequence = Clipboard.osc52("anything");
        assertThat(sequence).startsWith(PREFIX);
        assertThat(sequence).endsWith(BEL);
    }

    @Test
    public void osc52RoundTripsAsciiPayload() {
        assertRoundTrip("hi");
    }

    @Test
    public void osc52RoundTripsMultiByteUnicode() {
        // café→✓ exercises 2-byte (é), 3-byte (→, ✓) UTF-8 sequences.
        assertRoundTrip("café→✓");
    }

    @Test
    public void hasSystemClipboardNeverThrows() {
        assertThatCode(Clipboard::hasSystemClipboard).doesNotThrowAnyException();
    }

    @Test
    public void systemCopyNeverThrowsForNull() {
        assertThatCode(() -> Clipboard.systemCopy(null)).doesNotThrowAnyException();
    }

    @Test
    public void systemCopyNeverThrowsForEmpty() {
        assertThatCode(() -> Clipboard.systemCopy("")).doesNotThrowAnyException();
    }

    @Test
    public void systemCopyNeverThrowsForText() {
        assertThatCode(() -> Clipboard.systemCopy("café→✓")).doesNotThrowAnyException();
    }

    /**
     * Strips the OSC-52 framing, base64-decodes the payload, and asserts the original UTF-8
     * string comes back unchanged.
     */
    private static void assertRoundTrip(String original) {
        String sequence = Clipboard.osc52(original);
        assertThat(sequence).startsWith(PREFIX).endsWith(BEL);

        String payload = sequence.substring(PREFIX.length(), sequence.length() - BEL.length());
        byte[] decoded = Base64.getDecoder().decode(payload);
        assertThat(new String(decoded, StandardCharsets.UTF_8)).isEqualTo(original);
    }
}
