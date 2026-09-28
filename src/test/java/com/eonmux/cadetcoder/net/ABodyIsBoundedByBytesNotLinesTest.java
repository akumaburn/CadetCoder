package com.eonmux.cadetcoder.net;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A response body is bounded by what was taken from the socket, not by what came out of the
 * decoder.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code websearch} capped its body by checking the accumulated length after each
 * {@code readLine()}. The check cannot run until the line is complete, so a server answering with a
 * megabyte and no newline in it was read whole and the cap never got a turn -- exactly the
 * unbounded read it was written to prevent. {@code webfetch} counted bytes on the raw stream and
 * did not have the hole.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>The ceiling holds whether or not the body contains newlines; a body that fits is not reported
 * as cut; a body cut exactly at the ceiling is; and the cut never lands inside a multi-byte
 * character, whatever charset the server declared.</p>
 */
public class ABodyIsBoundedByBytesNotLinesTest {

    private static final int CEILING = 64;

    private static String read(byte[] body, Charset charset, StringBuilder out) throws Exception {
        BoundedBody.readInto(new ByteArrayInputStream(body), charset, CEILING, out);
        return out.toString();
    }

    @Test
    public void aBodyWithNoNewlineInItIsStillCutAtTheCeiling() throws Exception {
        byte[] oneLongLine = "x".repeat(CEILING * 10).getBytes(StandardCharsets.UTF_8);
        StringBuilder out  = new StringBuilder();

        boolean cut = BoundedBody.readInto(new ByteArrayInputStream(oneLongLine),
                                           StandardCharsets.UTF_8, CEILING, out);

        assertThat(cut).isTrue();
        assertThat(out.length())
                .as("a line-at-a-time cap read all of this; a byte cap must not")
                .isEqualTo(CEILING);
    }

    @Test
    public void aBodyThatFitsComesBackWholeAndIsNotReportedAsCut() throws Exception {
        StringBuilder out = new StringBuilder();

        boolean cut = BoundedBody.readInto(
                new ByteArrayInputStream("small enough\nwith lines\n".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8, CEILING, out);

        assertThat(cut).isFalse();
        assertThat(out.toString()).isEqualTo("small enough\nwith lines\n");
    }

    @Test
    public void aBodyThatEndsExactlyAtTheCeilingIsNotCalledCut() throws Exception {
        StringBuilder out = new StringBuilder();

        boolean cut = BoundedBody.readInto(
                new ByteArrayInputStream("y".repeat(CEILING).getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8, CEILING, out);

        assertThat(cut)
                .as("nothing was lost, so nothing should be reported as lost")
                .isFalse();
        assertThat(out.length()).isEqualTo(CEILING);
    }

    @Test
    public void theCutNeverLandsInsideAMultiByteCharacter() throws Exception {
        // Three bytes each, so the ceiling of 64 falls one byte into the 22nd character.
        byte[] body = "€".repeat(100).getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();

        BoundedBody.readInto(new ByteArrayInputStream(body), StandardCharsets.UTF_8, CEILING, out);

        assertThat(out.toString())
                .as("a half-decoded character would show as a replacement mark")
                .doesNotContain("�");
        assertThat(out.length()).isEqualTo(CEILING / 3);
    }

    @Test
    public void theDeclaredCharsetIsWhatTheBodyIsDecodedAs() throws Exception {
        StringBuilder out = new StringBuilder();
        byte[] latin1    = "café".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(read(latin1, BoundedBody.charsetOf("text/html; charset=ISO-8859-1"), out))
                .isEqualTo("café");
    }

    @Test
    public void aHeaderThatNamesNoUsableCharsetMeansUtf8() {
        for (String header : new String[] {null, "text/html", "text/html; charset=",
                                           "text/html; charset=not-a-charset",
                                           "text/html; charset=\"\""}) {
            assertThat(BoundedBody.charsetOf(header))
                    .as("%s gives nothing to decode with, so the safe default stands", header)
                    .isEqualTo(StandardCharsets.UTF_8);
        }
    }

    @Test
    public void aQuotedCharsetNameIsStillACharsetName() {
        assertThat(BoundedBody.charsetOf("text/html; charset=\"utf-8\""))
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(BoundedBody.charsetOf("text/plain;CHARSET=US-ASCII"))
                .isEqualTo(StandardCharsets.US_ASCII);
    }

    @Test
    public void aBoundedReadNeedsAllThreeOfItsParts() {
        InputStream anything = new ByteArrayInputStream(new byte[0]);

        assertThat(catchThrowable(
                () -> BoundedBody.readInto(null, StandardCharsets.UTF_8, CEILING, new StringBuilder())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(
                () -> BoundedBody.readInto(anything, null, CEILING, new StringBuilder())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(
                () -> BoundedBody.readInto(anything, StandardCharsets.UTF_8, CEILING, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
