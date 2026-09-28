package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.ContextWindow;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A model's input window can be given by hand, and it belongs to that model.
 *
 * <h2>The defect</h2>
 *
 * <p>The only way to state a window was {@code ai.contextTokens}, one number for every model. A
 * local model the catalog does not describe was assumed to take 65,536 tokens, and the setting
 * that corrected it went on applying after a switch to a model with a window ten times larger. So
 * a window is now recorded for a provider and model, where the picker asks for it when nothing is
 * published, and {@code models context} sets it for the model in use.</p>
 *
 * <h2>Why every model is listed</h2>
 *
 * <p>The picker stopped at forty models and printed "... and 41 more", and the models it left out
 * could only be picked by knowing their ids. A list the user chooses from is shown whole.</p>
 */
public class AmodelsWindowCanBeGivenByHandTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String                     originalBaseDir;
    private MockedStatic<OutputRouter> routers;
    private OutputRouter               router;
    private TestOutputCapture          output;
    private HttpServer                 server;

    @Before
    public void setUp() throws Exception {
        originalBaseDir = Configuration.defaultBaseDir;
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.cadet";
        resetConfig();
        router = mock(OutputRouter.class);
        when(router.commandPrefix()).thenReturn("/");
        routers = mockStatic(OutputRouter.class);
        routers.when(OutputRouter::getInstance).thenReturn(router);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() throws Exception {
        output.stopCapture();
        if (server != null) {
            server.stop(0);
        }
        routers.close();
        Configuration.defaultBaseDir = originalBaseDir;
        resetConfig();
    }

    private static void resetConfig() throws Exception {
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    private static Configuration.AiConfig ai() {
        return ConfigManager.getInstance().getConfig().getAi();
    }

    private static String savedFile() throws Exception {
        return Files.readString(Path.of(Configuration.defaultBaseDir, "config.json"));
    }

    /** A local server listing {@code count} models, as an OpenAI-compatible server does. */
    private String aServerListing(int count) throws Exception {
        StringBuilder body = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 1; i <= count; i++) {
            body.append(i > 1 ? "," : "").append("{\"id\":\"model-").append(i).append("\"}");
        }
        byte[] bytes = body.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/models", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void awindowGivenForAmodelIsTheOneUsedForIt() {
        ai().setProvider("local");
        ai().setModel("qwen3-coder");
        ai().setContextTokens(8_192);
        ai().getModelContextTokens().put(ContextWindow.keyFor("local", "qwen3-coder"), 32_768);

        ContextWindow.Window window = ContextWindow.current();

        assertThat(window.tokens())
                .as("the window given for this model is more specific than the one for all")
                .isEqualTo(32_768);
        assertThat(window.source()).contains("models context");
    }

    @Test
    public void awindowGivenForOneModelDoesNotFollowAswitchToAnother() {
        ai().setProvider("local");
        ai().setModel("another-model");
        ai().getModelContextTokens().put(ContextWindow.keyFor("local", "qwen3-coder"), 32_768);

        assertThat(ContextWindow.current().tokens()).isNotEqualTo(32_768);
    }

    @Test
    public void modelsContextSetsAndClearsTheWindowOfTheModelInUse() throws Exception {
        ai().setProvider("local");
        ai().setModel("qwen3-coder");

        assertThat(new ModelsCommand().execute(new String[] {"context", "32768"})).isZero();
        assertThat(ContextWindow.current().tokens()).isEqualTo(32_768);
        assertThat(savedFile()).contains("local/qwen3-coder").contains("32768");

        assertThat(new ModelsCommand().execute(new String[] {"context", "clear"})).isZero();
        assertThat(ai().getModelContextTokens()).isEmpty();
    }

    @Test
    public void awindowThatIsNotApositiveNumberIsRefused() {
        ai().setProvider("local");
        ai().setModel("qwen3-coder");

        assertThat(new ModelsCommand().execute(new String[] {"context", "lots"})).isEqualTo(1);
        assertThat(new ModelsCommand().execute(new String[] {"context", "0"})).isEqualTo(1);
        assertThat(ai().getModelContextTokens()).isEmpty();
    }

    @Test
    public void amodelAndItsWindowCanBeGivenOnOneLine() {
        int exit = new ModelsCommand().execute(
                new String[] {"use", "local", "qwen3-coder", "--context=40960"});

        assertThat(exit).isZero();
        assertThat(ai().getModel()).isEqualTo("qwen3-coder");
        assertThat(ai().getModelContextTokens())
                .containsEntry(ContextWindow.keyFor("local", "qwen3-coder"), 40_960);
        verify(router, never()).getUserInput(anyString());
    }

    @Test
    public void thepickerListsEveryModelAndAsksTheWindowOfOneNothingDescribes() throws Exception {
        String address = aServerListing(81);
        when(router.getUserInput(anyString())).thenReturn(address, "81", "131072");

        int exit = new ModelsCommand().execute(new String[] {"use", "local"});

        assertThat(exit).isZero();
        assertThat(output.getAllOutput())
                .contains("model-1 ").contains("model-41 ").contains("model-81")
                .doesNotContain("more.");
        verify(router).getUserInput(contains("any model id"));
        verify(router).getUserInput(contains("context"));
        assertThat(ai().getModel()).isEqualTo("model-81");
        assertThat(ai().getModelContextTokens())
                .containsEntry(ContextWindow.keyFor("local", "model-81"), 131_072);
    }

    @Test
    public void enterAtTheWindowQuestionKeepsWhatIsAssumed() throws Exception {
        String address = aServerListing(3);
        when(router.getUserInput(anyString())).thenReturn(address, "my-own-model", "");

        int exit = new ModelsCommand().execute(new String[] {"use", "local"});

        assertThat(exit).isZero();
        assertThat(ai().getModel()).as("a model id typed by hand is used as given")
                                   .isEqualTo("my-own-model");
        assertThat(ai().getModelContextTokens()).isEmpty();
    }
}
