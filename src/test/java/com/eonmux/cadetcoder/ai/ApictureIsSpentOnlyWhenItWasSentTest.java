package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.net.ImageMediaTypes;
import com.eonmux.cadetcoder.net.LLMBackend;
import com.eonmux.cadetcoder.net.OpenAIBackend;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What the turn's attachment is spent on, and who is told when it is not sent.
 *
 * <h2>The defects</h2>
 *
 * <p>{@link PromptAttachments} holds what a turn brought until a request carries it, and its
 * contract is that the attachment is spent once <i>delivered</i>, not once read -- a request that
 * failed has shown the model nothing, so the retry still carries the picture. But every client drops
 * images the wire cannot carry or the model cannot read, inside {@code complete}, where the caller
 * could not see it. {@code AIManager} spent the attachment on the strength of having attached it, so
 * a picture the client had just thrown away was recorded as shown to the model, and the one request
 * of the turn that was going to carry it had gone.</p>
 *
 * <p>{@link ImageChannel} then said so only once per provider, model and reason for the life of the
 * process, so the second screenshot dropped by the same model -- and every one after it -- was
 * dropped in silence.</p>
 *
 * <p>And the legacy {@link APIClient} asked the catalogue about "openai" whatever endpoint it had
 * settled on, so a private endpoint serving a model id that collides with an OpenAI text-only one
 * had its pictures dropped on the strength of what a different provider's model of that name cannot
 * do.</p>
 */
class ApictureIsSpentOnlyWhenItWasSentTest {

    @TempDir
    Path folder;

    private List<PromptImage> pictures;

    @BeforeEach
    void attachApicture() throws IOException {
        pictures = List.of(pictureNamed("first.png"), pictureNamed("second.png"));
        ImageChannel.resetForTesting();
        PromptAttachments.clear();
    }

    @AfterEach
    void clearWhatWasAttached() {
        ImageChannel.resetForTesting();
        PromptAttachments.clear();
        System.clearProperty("cadet.test.mode");
    }

    private PromptImage pictureNamed(String name) throws IOException {
        byte[] png = new byte[24];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                         png, 0, 8);
        Path file = folder.resolve(name);
        Files.write(file, png);
        return PromptImage.of(file);
    }

    // ---------------------------------------------------------- what the turn is charged for

    @Test
    void apictureTheClientSentIsSpent() throws Exception {
        PromptAttachments.attach(pictures);

        completeThrough(new Wire(true));

        assertThat(PromptAttachments.pending())
                .as("the model has seen them, and the conversation carries what it said about them")
                .isEmpty();
    }

    @Test
    void apictureTheClientDroppedIsStillOwed() throws Exception {
        PromptAttachments.attach(pictures);

        completeThrough(new Wire(false));

        assertThat(PromptAttachments.pending())
                .as("nothing was shown to anything, so the turn still owes the picture")
                .isEqualTo(pictures);
    }

    /** Runs one completion through a client of the test's choosing. */
    private void completeThrough(AIClient client) throws Exception {
        try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
            sessions.when(SessionManager::getInstance).thenReturn(mock(SessionManager.class));
            AIManager.getInstance().completeMeasured(client, new PromptData("sys", "hi"));
        }
    }

    // ---------------------------------------------------------- what the user is told

    @Test
    void asecondPictureDroppedForTheSameReasonIsAnewThingToSay() {
        PromptData first  = new PromptData("sys", "what is this")
                .withImages(List.of(pictures.get(0)));
        PromptData second = new PromptData("sys", "and this")
                .withImages(List.of(pictures.get(1)));

        TestOutputCapture console = new TestOutputCapture();
        try {
            ImageChannel.fit(first, TEXT_ONLY, "acme", "acme-1");
            ImageChannel.fit(second, TEXT_ONLY, "acme", "acme-1");
        } finally {
            console.restore();
        }

        assertThat(console.getAllOutput()).contains("first.png").contains("second.png");
    }

    @Test
    void theSamePictureDroppedTwiceIsNotSaidTwice() {
        PromptData request = new PromptData("sys", "what is this")
                .withImages(List.of(pictures.get(0)));

        ImageChannel.fit(request, TEXT_ONLY, "acme", "acme-1");
        TestOutputCapture console = new TestOutputCapture();
        try {
            ImageChannel.fit(request, TEXT_ONLY, "acme", "acme-1");
        } finally {
            console.restore();
        }

        assertThat(console.getAllOutput())
                .as("a retry of the same request is the same sentence")
                .doesNotContain("first.png");
    }

    // ---------------------------------------------------------- whose catalogue entry is read

    @Test
    void acustomEndpointIsNotOpenAiJustBecauseItSpeaksTheSameProtocol() {
        System.setProperty("cadet.test.mode", "true");

        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class);
             MockedConstruction<OpenAIBackend> backends = mockConstruction(
                     OpenAIBackend.class, (wire, context) ->
                             when(wire.imageMediaTypes())
                                     .thenReturn(ImageMediaTypes.PNG_JPEG_GIF_WEBP))) {

            ConfigManager          manager  = mock(ConfigManager.class);
            Configuration          config   = mock(Configuration.class);
            Configuration.AiConfig aiConfig = mock(Configuration.AiConfig.class);
            configs.when(ConfigManager::getInstance).thenReturn(manager);
            when(manager.getConfig()).thenReturn(config);
            when(config.getAi()).thenReturn(aiConfig);
            when(aiConfig.getLocalEndpoint()).thenReturn("http://my-own-server:8080/v1");
            // An id OpenAI also uses, for a model of OpenAI's that reads no pictures. Which says
            // nothing whatsoever about the model this endpoint has loaded under that name.
            when(aiConfig.getLocalModel()).thenReturn("gpt-3.5-turbo");
            when(aiConfig.getApiKey()).thenReturn("a-key");

            APIClient client = new APIClient();

            assertThat(ImageChannel.modelReadsImages("openai", "gpt-3.5-turbo"))
                    .as("the collision this test is about: OpenAI's model of that name reads none")
                    .isFalse();
            assertThat(client.deliversImages(
                    new PromptData("sys", "what is this").withImages(pictures)))
                    .as("this endpoint has never said which provider it is, so it answers for itself")
                    .isTrue();
        }
    }

    /** A wire that says it cannot carry an image, so the catalogue is never even reached. */
    private static final LLMBackend TEXT_ONLY = new LLMBackend() {
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
            return "text-only";
        }

        @Override
        public Set<String> imageMediaTypes() {
            return ImageMediaTypes.NONE;
        }

        @Override
        public String getApiEndpoint() {
            return "https://example.invalid";
        }
    };

    /** A client that answers, and says whether it really sent what it was given. */
    private static final class Wire implements AIClient {

        private final boolean delivers;

        private Wire(boolean delivers) {
            this.delivers = delivers;
        }

        @Override
        public String complete(PromptData promptData, Map<String, Object> parameters) {
            return "an answer";
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
        public boolean deliversImages(PromptData promptData) {
            return delivers && promptData.hasImages();
        }
    }
}
