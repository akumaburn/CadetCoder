package com.eonmux.cadetcoder.logging;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.testing.Await;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * A credential must not reach the log through whichever writer was used.
 *
 * <p>{@code CommandRegistry} masks a command's arguments before recording them, "so a misused
 * {@code login <provider> <apikey>} can never persist the key to the debug/session logs". Eighteen
 * commands then log the SAME arguments a second time through
 * {@code LoggingCommandSupport.startCommandLogging}, which hands them to
 * {@code ObservabilityLogger.commandStart} verbatim -- and that writes them to the same debug log
 * the masked copy went to. A rule enforced by one writer and not by the one beside it is not a
 * rule, and {@code bash}, {@code write} and {@code chat} all carry free text a key can be sitting
 * in.</p>
 *
 * <p>So the masking happens where the writing does: at the single point each logger queues an
 * entry. No call site can then be the one that was not audited.</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class LoggedTextCarriesNoCredentialTest {

    /** Shaped like a real key so {@link com.eonmux.cadetcoder.security.SecretRedactor} recognises it. */
    private static final String SECRET = "sk-live-abcdef0123456789ABCDEF";

    @Mock private ConfigManager               mockConfigManager;
    @Mock private Configuration               mockConfiguration;
    @Mock private Configuration.LoggingConfig mockLoggingConfig;
    @Mock private Configuration.UiConfig      mockUiConfig;

    private Path                        testLogDir;
    private MockedStatic<ConfigManager> configManagerMock;

    @Before
    public void setUp() throws Exception {
        testLogDir = Paths.get(System.getProperty("java.io.tmpdir"),
                               "cadet-log-redaction-test-" + System.nanoTime());
        Files.createDirectories(testLogDir);

        configManagerMock = mockStatic(ConfigManager.class);
        configManagerMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
        when(mockConfigManager.getConfig()).thenReturn(mockConfiguration);
        when(mockConfiguration.getLogging()).thenReturn(mockLoggingConfig);
        when(mockConfiguration.getUi()).thenReturn(mockUiConfig);
        when(mockConfiguration.getBaseDir()).thenReturn(testLogDir.toString());

        when(mockLoggingConfig.isDebugEnabled()).thenReturn(true);
        when(mockLoggingConfig.isConsoleLoggingEnabled()).thenReturn(false);

        resetDebugLogger();
    }

    @After
    public void tearDown() throws Exception {
        DebugLogger instance = DebugLogger.getInstance();
        if (instance != null) {
            Method shutdown = DebugLogger.class.getDeclaredMethod("shutdown");
            shutdown.setAccessible(true);
            shutdown.invoke(instance);
        }
        resetDebugLogger();
        if (configManagerMock != null) {
            configManagerMock.close();
        }
        if (Files.exists(testLogDir)) {
            try (Stream<Path> paths = Files.walk(testLogDir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static void resetDebugLogger() throws Exception {
        Field instance = DebugLogger.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    /** @return everything written to the debug log so far, or empty when nothing has been. */
    private String debugLog() {
        Path debugDir = testLogDir.resolve("logs").resolve("debug");
        if (!Files.exists(debugDir)) {
            return "";
        }
        try (Stream<Path> files = Files.list(debugDir)) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.sorted().toList()) {
                all.append(Files.readString(file, StandardCharsets.UTF_8));
            }
            return all.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void awaitLogged(String marker) {
        Await.until("the debug log to carry " + marker, () -> debugLog().contains(marker));
    }

    /**
     * The path the eighteen commands take: {@code startCommandLogging(name, args)}.
     */
    @Test
    public void aKeyInACommandArgumentIsNotWrittenToTheDebugLog() {
        ObservabilityLogger.forComponent("RedactionProbeBash")
                           .commandStart("bash",
                                         new String[] {"curl -H \"Authorization: Bearer " + SECRET + "\" https://api"},
                                         null);

        awaitLogged("COMMAND_START: bash");
        assertThat(debugLog())
                .as("the same argument is masked by CommandRegistry; this writer must not undo that")
                .doesNotContain(SECRET)
                .doesNotContain("abcdef0123456789");
    }

    /** And every other writer, so the guarantee does not depend on which method was called. */
    @Test
    public void aKeyInAnyLoggedMessageIsNotWrittenToTheDebugLog() {
        DebugLogger.getInstance().info("RedactionProbeInfo", "resolved credential " + SECRET + " from env");

        awaitLogged("RedactionProbeInfo");
        assertThat(debugLog()).doesNotContain(SECRET);
    }

    @Test
    public void aKeyInACommandArgumentListIsNotWrittenToTheDebugLog() {
        DebugLogger.getInstance().logCommand("login", new String[] {"openai", SECRET});

        awaitLogged("COMMAND: login");
        assertThat(debugLog()).doesNotContain(SECRET);
    }

    /** Masking must not cost the log its usefulness. */
    @Test
    public void anOrdinaryMessageIsWrittenVerbatim() {
        ObservabilityLogger.forComponent("RedactionProbeRead")
                           .commandStart("read", new String[] {"src/main/java/Main.java", "--limit=20"}, null);

        awaitLogged("COMMAND_START: read");
        assertThat(debugLog())
                .contains("src/main/java/Main.java")
                .contains("--limit=20");
    }
}
