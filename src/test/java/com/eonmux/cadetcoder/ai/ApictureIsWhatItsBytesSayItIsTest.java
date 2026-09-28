package com.eonmux.cadetcoder.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an image is read as, and what is refused before a request is spent on it.
 *
 * <h2>Why the type is not taken from the name</h2>
 *
 * <p>Every provider rejects a body whose declared media type does not match the bytes. A screenshot
 * saved as {@code shot.jpg} that is really a PNG is an ordinary thing to have on disk, and naming
 * it {@code image/jpeg} costs a 400 from all four of them. The bytes are the only honest source.</p>
 */
public class ApictureIsWhatItsBytesSayItIsTest {

    @TempDir
    Path folder;

    private static final byte[] PNG_HEAD =
            {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13};

    private Path write(String name, byte[] head, int padding) throws IOException {
        byte[] bytes = new byte[head.length + padding];
        System.arraycopy(head, 0, bytes, 0, head.length);
        Path file = folder.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void apngIsApngWhateverTheFileIsCalled() throws IOException {
        PromptImage image = PromptImage.of(write("screenshot.jpg", PNG_HEAD, 40));

        assertThat(image.mediaType()).isEqualTo("image/png");
        assertThat(image.name()).isEqualTo("screenshot.jpg");
    }

    @Test
    void theFourTypesTheProvidersTakeAreToldApart() {
        assertThat(PromptImage.mediaTypeOf(PNG_HEAD)).isEqualTo("image/png");
        assertThat(PromptImage.mediaTypeOf(new byte[] {
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0}))
                .isEqualTo("image/jpeg");
        assertThat(PromptImage.mediaTypeOf(
                new byte[] {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0}))
                .isEqualTo("image/gif");
        assertThat(PromptImage.mediaTypeOf(
                new byte[] {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'}))
                .isEqualTo("image/webp");
    }

    @Test
    void anythingElseIsNotApicture() {
        assertThat(PromptImage.looksLikeImage("class Foo {}".getBytes())).isFalse();
        assertThat(PromptImage.looksLikeImage(new byte[] {1, 2, 3})).isFalse();
        assertThat(PromptImage.looksLikeImage(null)).isFalse();
    }

    @Test
    void afileThatIsNotApictureIsRefusedRatherThanSent() throws IOException {
        Path notApicture = folder.resolve("notes.txt");
        Files.writeString(notApicture, "nothing to look at here");

        assertThatThrownBy(() -> PromptImage.of(notApicture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("notes.txt");
    }

    @Test
    void apictureTooLargeForTheProvidersIsRefusedHere() throws IOException {
        // Refused before the request, because the round trip that would refuse it costs the same as
        // one that worked and tells the user nothing they could not have been told immediately.
        Path huge = write("huge.png", PNG_HEAD, PromptImage.MAX_BYTES);

        assertThatThrownBy(() -> PromptImage.of(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MB");
    }

    /**
     * The refusal has to say how much too large the file is, or it cannot be acted on.
     *
     * <p>Both sizes were divided as integers, so a 5.4 MB screenshot was refused with "is 5 MB; the
     * limit is 5 MB" -- a sentence that reads as a bug in the tool rather than as a file to shrink,
     * and that gives no idea how much to shrink it by.</p>
     */
    @Test
    void theRefusalSaysHowMuchTooLargeTheFileIs() throws IOException {
        // Two thirds of a megabyte over the limit: the case integer division could not describe,
        // because it reported the file and the limit as the same whole number of megabytes.
        int overshoot = (int) (0.66 * 1024 * 1024);
        Path huge = write("huge.png", PNG_HEAD, PromptImage.MAX_BYTES + overshoot - PNG_HEAD.length);

        assertThatThrownBy(() -> PromptImage.of(huge))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is 4.4 MB")
                .hasMessageContaining("the limit is 3.8 MB");
    }

    /**
     * <h2>Why the file is measured against a smaller number than the providers publish</h2>
     *
     * <p>Every provider here states its image limit on the base64 string rather than on the file,
     * and base64 writes four characters for every three bytes. The limit was checked against the
     * file size, so a 5 MB screenshot passed here and arrived as 6.7 MB -- over the 5 MB that
     * Anthropic on Bedrock and on Google Cloud takes. The provider refused it, and the question
     * that came with it was lost.</p>
     */
    @Test
    void thelargestFileAllowedStillEncodesUnderWhatTheProvidersTake() {
        String encoded = Base64.getEncoder().encodeToString(new byte[PromptImage.MAX_BYTES]);

        assertThat(encoded.length()).isLessThanOrEqualTo(PromptImage.MAX_ENCODED_BYTES);
        assertThat(Base64.getEncoder().encodeToString(new byte[PromptImage.MAX_BYTES + 3]).length())
                .as("and it is the largest such file, not merely a small one")
                .isGreaterThan(PromptImage.MAX_ENCODED_BYTES);
    }

    @Test
    void thebytesAreEncodedOnceForEveryWireThatCarriesThem() throws IOException {
        PromptImage image = PromptImage.of(write("shot.png", PNG_HEAD, 8));

        assertThat(Base64.getDecoder().decode(image.base64())).hasSize(PNG_HEAD.length + 8);
        assertThat(image.byteCount()).isEqualTo(PNG_HEAD.length + 8);
        assertThat(image.dataUrl()).isEqualTo("data:image/png;base64," + image.base64());
        assertThat(image.bedrockFormat()).isEqualTo("png");
    }
}
