package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.net.ImageMediaTypes;
import com.eonmux.cadetcoder.net.LLMBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to an attached image when the wire or the model cannot take one.
 *
 * <h2>Why the request is checked here rather than by the provider</h2>
 *
 * <p>A wire with no image part would send the text alone, and the model would answer about a
 * picture it was never shown -- a wrong answer with nothing in it to say so. A model that refuses
 * images fails the whole request, so a question that would have been answered from its text is lost
 * instead. Dropping the picture and saying so leaves the text answered and the reason on screen.</p>
 *
 * <h2>Why an unknown model is still asked</h2>
 *
 * <p>The catalogue is fetched from models.dev and cached, so it is sometimes stale and never
 * complete. A private deployment, a gateway alias or a model released last week is not in it, and
 * refusing those would make this the reason a working setup stopped sending pictures.</p>
 */
public class ApictureIsNotSentWhereItCannotBeReadTest {

    @TempDir
    Path folder;

    private PromptData withPicture;

    /** A wire that takes the four formats three of the four APIs document. */
    private static final LLMBackend TAKES_IMAGES = new Stub(ImageMediaTypes.PNG_JPEG_GIF_WEBP);

    /** A wire with nowhere to put an image at all. */
    private static final LLMBackend TEXT_ONLY = new Stub(ImageMediaTypes.NONE);

    /** A wire shaped like Gemini's, which takes HEIC and HEIF and takes no GIF. */
    private static final LLMBackend NO_GIF = new Stub(ImageMediaTypes.GEMINI);

    @BeforeEach
    void attachApicture() throws IOException {
        byte[] png = new byte[24];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                         png, 0, 8);
        Path file = folder.resolve("shot.png");
        Files.write(file, png);
        withPicture = new PromptData("sys", "what is this")
                .withImages(List.of(PromptImage.of(file)));
        ImageChannel.resetForTesting();
        PromptAttachments.clear();
    }

    @AfterEach
    void clearWhatWasAttached() {
        ImageChannel.resetForTesting();
        PromptAttachments.clear();
    }

    @Test
    void apictureSurvivesAwireThatCanCarryItAndAmodelNobodyHasHeardOf() {
        PromptData fitted = ImageChannel.fit(withPicture, TAKES_IMAGES, "acme", "acme-private-1");

        assertThat(fitted).isSameAs(withPicture);
        assertThat(fitted.hasImages()).isTrue();
    }

    @Test
    void awireWithNowhereToPutApictureLosesItAndKeepsTheQuestion() {
        PromptData fitted = ImageChannel.fit(withPicture, TEXT_ONLY, "local", "llama-3");

        assertThat(fitted.hasImages()).isFalse();
        assertThat(fitted.getUserPrompt()).isEqualTo("what is this");
        assertThat(fitted.getSystemPrompt()).isEqualTo("sys");
    }

    @Test
    void atextOnlyRequestIsHandedBackUntouched() {
        PromptData text = new PromptData("sys", "hello");

        assertThat(ImageChannel.fit(text, TEXT_ONLY, "local", "llama-3")).isSameAs(text);
        assertThat(ImageChannel.fit(null, TEXT_ONLY, "local", "llama-3")).isNull();
    }

    @Test
    void amodelThatDoesNotReadPicturesLosesItEvenOnAwireThatCouldCarryIt() {
        // Support is per model, not per provider: the same OpenAI account serves models that read
        // a picture and models that answer 400 for one.
        PromptData fitted = ImageChannel.fit(withPicture, TAKES_IMAGES, "openai", "gpt-3.5-turbo");

        assertThat(fitted.hasImages()).isFalse();
        assertThat(fitted.getUserPrompt()).isEqualTo("what is this");
        assertThat(ImageChannel.modelReadsImages("openai", "gpt-3.5-turbo")).isFalse();
    }

    @Test
    void amodelTheCatalogueDoesNotKnowIsAllowedToTry() {
        assertThat(ImageChannel.modelReadsImages("acme", "not-in-the-catalogue")).isTrue();
        assertThat(ImageChannel.modelReadsImages(null, null)).isTrue();
    }

    // --------------------------------------------------- a format this wire does not document

    /**
     * <h2>Why a format the wire does not take is dropped here</h2>
     *
     * <p>The four wires do not take the same formats. Gemini documents PNG, JPEG, WebP, HEIC and
     * HEIF, and no GIF; the other three document GIF and neither HEIC nor HEIF. A GIF was read,
     * attached and sent to Gemini, which refused the whole call, so dropping an animation on the
     * prompt ended the turn with a protocol error rather than an answer to the question typed
     * beside it.</p>
     */
    @Test
    void aformatThewireDoesNotTakeIsDroppedAndTheQuestionIsStillAsked() throws IOException {
        PromptData withAgif = new PromptData("sys", "what is this")
                .withImages(List.of(gifNamed("loop.gif")));

        PromptData fitted = ImageChannel.fit(withAgif, NO_GIF, "google", "gemini-3-pro");

        assertThat(fitted.hasImages()).isFalse();
        assertThat(fitted.getUserPrompt()).isEqualTo("what is this");
        assertThat(fitted.getSystemPrompt()).isEqualTo("sys");
    }

    @Test
    void onlyThePictureTheWireCannotReadIsDropped() throws IOException {
        PromptData both = new PromptData("sys", "what is this")
                .withImages(List.of(withPicture.getImages().get(0), gifNamed("loop.gif")));

        PromptData fitted = ImageChannel.fit(both, NO_GIF, "google", "gemini-3-pro");

        assertThat(fitted.getImages()).hasSize(1);
        assertThat(fitted.getImages().get(0).mediaType()).isEqualTo("image/png");
    }

    @Test
    void awireThatTakesTheFormatIsHandedTheRequestUntouched() throws IOException {
        PromptData withAgif = new PromptData("sys", "what is this")
                .withImages(List.of(gifNamed("loop.gif")));

        assertThat(ImageChannel.fit(withAgif, TAKES_IMAGES, "acme", "acme-private-1"))
                .isSameAs(withAgif);
    }

    /**
     * @param name the file to write
     * @return a GIF, which three of the four wires take and Gemini does not
     */
    private PromptImage gifNamed(String name) throws IOException {
        byte[] gif = new byte[24];
        System.arraycopy(new byte[] {'G', 'I', 'F', '8', '9', 'a'}, 0, gif, 0, 6);
        Path file = folder.resolve(name);
        Files.write(file, gif);
        return PromptImage.of(file);
    }

    // ------------------------------------------------------------------ what the turn brought

    @Test
    void whatTheTurnBroughtIsHeldUntilArequestCarriesIt() throws IOException {
        List<PromptImage> pictures = withPicture.getImages();

        PromptAttachments.attach(pictures);
        assertThat(PromptAttachments.pending()).isEqualTo(pictures);

        // Read without being spent: a request that failed has shown the model nothing, and the
        // retry has to carry the picture too.
        assertThat(PromptAttachments.pending()).isEqualTo(pictures);

        PromptAttachments.delivered();
        assertThat(PromptAttachments.pending()).isEmpty();
    }

    @Test
    void aturnThatBroughtNothingHoldsNothing() {
        PromptAttachments.attach(null);
        assertThat(PromptAttachments.pending()).isEmpty();

        PromptAttachments.attach(List.of());
        assertThat(PromptAttachments.pending()).isEmpty();
    }

    /** A backend that does nothing but name the formats it takes. */
    private static final class Stub implements LLMBackend {

        private final Set<String> takesImages;

        private Stub(Set<String> takesImages) {
            this.takesImages = takesImages;
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            return "";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public Set<String> imageMediaTypes() {
            return takesImages;
        }

        @Override
        public String getApiEndpoint() {
            return "https://example.invalid";
        }
    }
}
