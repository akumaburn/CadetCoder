package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Choosing a local model asks where its server is.
 *
 * <h2>The defect</h2>
 *
 * <p>The {@code local} connector is an OpenAI-compatible server -- llama-server, LM Studio, Ollama
 * -- and its address defaulted to {@code http://localhost:8012/v1}. {@code models select},
 * {@code models use local} and {@code login local} went straight to the model list, fetched from
 * that address, and nothing asked for another. A server on another machine on the network could
 * only be reached by knowing to type {@code config set ai.providerOptions.local.baseURL}.</p>
 *
 * <h2>What is asked</h2>
 *
 * <p>Where the server runs, as {@code host:port}, a bare host or a full URL, with Enter keeping
 * the address in use. A part left out is taken from that address, so {@code 192.168.1.20} means
 * the same port and path on another machine. The model list is then fetched from the address
 * given, and the address is saved with the model chosen.</p>
 */
public class AlocalServerCanBeOnAnotherMachineTest {

    private static final String DEFAULT = "http://localhost:8012/v1";

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private String                     originalBaseDir;
    private MockedStatic<OutputRouter> routers;
    private OutputRouter               router;

    @Before
    public void setUp() throws Exception {
        originalBaseDir = Configuration.defaultBaseDir;
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.cadet";
        resetConfig();
        router = mock(OutputRouter.class);
        when(router.commandPrefix()).thenReturn("/");
        routers = mockStatic(OutputRouter.class);
        routers.when(OutputRouter::getInstance).thenReturn(router);
    }

    @After
    public void tearDown() throws Exception {
        routers.close();
        Configuration.defaultBaseDir = originalBaseDir;
        resetConfig();
    }

    private static void resetConfig() throws Exception {
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** A server address on this machine that nothing answers, so a listing fails at once. */
    private static String anAddressNothingAnswers() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static Configuration.AiConfig ai() {
        return ConfigManager.getInstance().getConfig().getAi();
    }

    private static String savedFile() throws Exception {
        return Files.readString(Path.of(Configuration.defaultBaseDir, "config.json"));
    }

    @Test
    public void anaddressIsReadAsHostPortOrUrlWithTheRestTakenFromTheCurrentOne() {
        assertThat(ServerAddress.read("192.168.1.20:8080", DEFAULT))
                .isEqualTo("http://192.168.1.20:8080/v1");
        assertThat(ServerAddress.read("192.168.1.20", DEFAULT))
                .isEqualTo("http://192.168.1.20:8012/v1");
        assertThat(ServerAddress.read("gpu-box.lan:1234", DEFAULT))
                .isEqualTo("http://gpu-box.lan:1234/v1");
        assertThat(ServerAddress.read("https://models.example.lan/api/v1", DEFAULT))
                .isEqualTo("https://models.example.lan/api/v1");
        assertThat(ServerAddress.read("http://10.0.0.5:11434/v1/", DEFAULT))
                .isEqualTo("http://10.0.0.5:11434/v1");
        assertThat(ServerAddress.read("[fe80::1]:8080", DEFAULT))
                .isEqualTo("http://[fe80::1]:8080/v1");
    }

    @Test
    public void anaddressThatCannotBeReachedIsRefusedWithTheReason() {
        assertThatThrownBy(() -> ServerAddress.read("192.168.1.20:99999", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("port");
        assertThatThrownBy(() -> ServerAddress.read("192.168.1.20:abc", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServerAddress.read("ftp://192.168.1.20", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("http");
        assertThatThrownBy(() -> ServerAddress.read(":8080", DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void onlyAserverThatDefaultsToThisMachineIsAskedWhereItIs() {
        assertThat(ServerAddress.asksWhere(ConnectorSupport.connector("local"))).isTrue();
        assertThat(ServerAddress.asksWhere(ConnectorSupport.connector("openai"))).isFalse();
    }

    @Test
    public void usingTheLocalConnectorAsksWhereItIsAndSavesTheAnswerWithTheModel() throws Exception {
        String address = anAddressNothingAnswers();
        when(router.getUserInput(anyString())).thenReturn(address, "qwen3-coder");

        int exit = new ModelsCommand().execute(new String[] {"use", "local"});

        assertThat(exit).isZero();
        verify(router).getUserInput(contains("Where"));
        assertThat(ai().getProvider()).isEqualTo("local");
        assertThat(ai().getModel()).isEqualTo("qwen3-coder");
        assertThat(ai().getProviderOptions().get("local"))
                .containsEntry("baseURL", "http://" + address + "/v1");
        assertThat(savedFile()).contains("http://" + address + "/v1");
    }

    @Test
    public void enterKeepsTheAddressAlreadyInUse() throws Exception {
        String address = "http://" + anAddressNothingAnswers() + "/v1";
        ai().getProviderOptions().put("local", new java.util.LinkedHashMap<>(Map.of("baseURL", address)));
        when(router.getUserInput(anyString())).thenReturn("", "qwen3-coder");

        int exit = new ModelsCommand().execute(new String[] {"use", "local"});

        assertThat(exit).isZero();
        verify(router).getUserInput(contains(address));
        assertThat(ai().getProviderOptions().get("local")).containsEntry("baseURL", address);
    }

    @Test
    public void anaddressThatCannotBeReadChangesNothing() throws Exception {
        String before = ai().getProvider();
        when(router.getUserInput(anyString())).thenReturn("192.168.1.20:99999");

        int exit = new ModelsCommand().execute(new String[] {"use", "local"});

        assertThat(exit).isEqualTo(1);
        assertThat(ai().getProvider()).isEqualTo(before);
        assertThat(ai().getProviderOptions()).doesNotContainKey("local");
        verify(router, never()).getUserInput(contains("model"));
    }

    @Test
    public void namingTheModelOnTheCommandLineAsksNothing() {
        int exit = new ModelsCommand().execute(new String[] {"use", "local", "qwen3-coder"});

        assertThat(exit).isZero();
        verify(router, never()).getUserInput(anyString());
    }
}
