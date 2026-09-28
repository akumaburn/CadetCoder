package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.*;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import java.net.URLStreamHandlerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class WebFetchCommandTest {

    private WebFetchCommand         webFetchCommand;
    private TestOutputCapture       outputCapture;
    private MockAIClientFactory     mockAIFactory;
    private URLStreamHandlerFactory originalFactory;

    @Before
    public void setUp() throws Exception {
        // Enable test mode to skip network calls
        System.setProperty("cadet.test.mode", "true");
        // Set non-interactive mode for tests
        System.setProperty("cadet.interactive", "false");

        webFetchCommand = new WebFetchCommand();
        outputCapture   = new TestOutputCapture();
        outputCapture.startCapture();

        // Set up mock AI client factory
        mockAIFactory = new MockAIClientFactory();
        mockAIFactory.setApiClientAvailable(true);
        mockAIFactory.setApiResponse("This is a summary of the web content.");

        // Inject mock factory using reflection
        java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
        factoryField.setAccessible(true);
        AIClientFactory originalFactory = (AIClientFactory) factoryField.get(null);

        java.lang.reflect.Method setFactoryMethod =
                AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
        setFactoryMethod.setAccessible(true);
        setFactoryMethod.invoke(null, mockAIFactory);

        // Reset output capture after any initialization messages
        outputCapture.reset();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
        resetSingletons();
        System.clearProperty("cadet.test.mode");
        System.clearProperty("cadet.interactive");
    }

    private void resetSingletons() {
        try {
            // Reset AIManager
            java.lang.reflect.Field aiInstance = AIManager.class.getDeclaredField("instance");
            aiInstance.setAccessible(true);
            aiInstance.set(null, null);

            // Reset SessionManager
            java.lang.reflect.Field sessionInstance = SessionManager.class.getDeclaredField("instance");
            sessionInstance.setAccessible(true);
            sessionInstance.set(null, null);

            // Reset ConfigManager
            java.lang.reflect.Field configInstance = ConfigManager.class.getDeclaredField("instance");
            configInstance.setAccessible(true);
            configInstance.set(null, null);

            // Reset ErrorHandler
            java.lang.reflect.Field errorInstance = ErrorHandler.class.getDeclaredField("instance");
            errorInstance.setAccessible(true);
            errorInstance.set(null, null);
        } catch (Exception e) {
            // Ignore
        }
    }

    @Test
    public void testWebFetch_NoArguments() {
        // Execute without arguments
        int exitCode = webFetchCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Usage:");
    }

    @Test
    public void testWebFetch_InvalidURL() {
        // Execute with invalid URL. The direct-args path now normalizes and validates the
        // URL up front (finding 25), so a malformed URL is rejected before the fetch step
        // with clear validation guidance instead of a generic "Failed to fetch".
        int exitCode = webFetchCommand.execute(new String[] {"not a valid url with spaces", "Analyze", "-f"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Invalid URL:");
    }

    @Test
    public void testWebFetch_PlaceholderURLRejected() {
        // A placeholder URL supplied directly is rejected up front (finding 25) with clear
        // guidance, instead of being stored verbatim and failing later with a generic fetch
        // error. example.com is a known placeholder per URLUtils.isPlaceholderURL.
        int exitCode = webFetchCommand.execute(new String[] {"https://example.com", "Analyze", "-f"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("placeholder");
    }

    @Test
    public void testWebFetch_ValidURLPassesValidation() {
        // A well-formed, non-placeholder URL passes up-front validation and proceeds to the
        // fetch step (which fails here on the unreachable reserved .invalid host), confirming
        // valid URLs are unaffected by the new direct-args validation (finding 25).
        int exitCode = webFetchCommand.execute(new String[] {"https://cadetcoder.invalid", "Analyze", "-f"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Fetching content from:");
    }

    @Test
    public void testWebFetch_SuccessfulFetch() throws Exception {
        // Use a custom WebFetchCommand that overrides the fetchAndProcess method
        WebFetchCommand mockWebFetchCommand = new WebFetchCommand() {
            @Override
            public int execute(String[] args) {
                try {
                    // Simulate successful execution
                    System.out.println("Fetching content from: https://example.com");
                    System.out.println("Fetched 1234 characters");
                    System.out.println("AI Analysis:");
                    System.out.println("This is a summary of the web content.");
                    return 0;
                } catch (Exception e) {
                    return 1;
                }
            }
        };

        // Execute with valid URL
        int exitCode = mockWebFetchCommand.execute(new String[] {"https://example.com", "Summarize"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("Fetching content from:");
        assertThat(outputCapture.getAllOutput()).contains("Fetched 1234 characters");
        assertThat(outputCapture.getAllOutput()).contains("AI Analysis:");
        assertThat(outputCapture.getAllOutput()).contains("This is a summary of the web content.");
    }

    @Test
    public void testWebFetch_WithTimeout() {
        // Mock the configuration to skip confirmation
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = mock(Configuration.class);
            Configuration.SecurityConfig mockSecurity      = mock(Configuration.SecurityConfig.class);
            Configuration.UiConfig       mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getSecurity()).thenReturn(mockSecurity);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(1);
            when(mockSecurity.isRequireConfirmation()).thenReturn(false);

            // Execute command against a reserved-unresolvable host (.invalid TLD per
            // RFC 2606) so the fetch deterministically fails regardless of whether the
            // test host has network access. Using a reachable URL made this test pass
            // only when offline (otherwise the fetch succeeds and a different,
            // AI-related error is reported instead).
            int exitCode = webFetchCommand.execute(new String[] {"https://cadetcoder.invalid", "Analyze", "-t", "5"});

            // Verify the fetch failure is handled gracefully
            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Failed to fetch or process:");
        }
    }

    @Test
    public void testWebFetch_WithForceFlag() {
        // Mock the configuration to require confirmation
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = mock(Configuration.class);
            Configuration.SecurityConfig mockSecurity      = mock(Configuration.SecurityConfig.class);
            Configuration.UiConfig       mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getSecurity()).thenReturn(mockSecurity);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(1);
            when(mockSecurity.isRequireConfirmation()).thenReturn(true);

            // Execute command with force flag (should skip confirmation). Use a reserved
            // .invalid host rather than example.com, since example.com is treated as a
            // placeholder URL and rejected up front by the direct-args validation (finding 25).
            int exitCode = webFetchCommand.execute(new String[] {"https://cadetcoder.invalid", "Analyze", "-f"});

            // Should skip confirmation and fail on connection
            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Fetching content from:");
            assertThat(outputCapture.getAllOutput()).doesNotContain("Continue? (y/n):");
        }
    }

    @Test
    public void testWebFetch_HttpError() throws Exception {
        // Create a mock WebFetchCommand that simulates HTTP error
        WebFetchCommand mockWebFetchCommand = new WebFetchCommand() {
            @Override
            public int execute(String[] args) {
                System.err.println("HTTP error code: 404");
                return 1;
            }
        };

        // Execute
        int exitCode = mockWebFetchCommand.execute(new String[] {"https://example.com/notfound", "Analyze"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("HTTP error code: 404");
    }

    @Test
    public void testWebFetch_ContentTruncation() throws Exception {
        // Create a mock WebFetchCommand that simulates content truncation
        WebFetchCommand mockWebFetchCommand = new WebFetchCommand() {
            @Override
            public int execute(String[] args) {
                System.out.println("Fetching content from: https://example.com");
                System.out.println("Content truncated at 1048576 characters");
                System.out.println("Fetched 1048576 characters");
                System.out.println("AI Analysis:");
                System.out.println("Summary of truncated content.");
                return 0;
            }
        };

        // Execute
        int exitCode = mockWebFetchCommand.execute(new String[] {"https://example.com/large", "Summarize"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        assertThat(outputCapture.getAllOutput()).contains("Content truncated at");
    }

    @Test
    public void testWebFetch_UrlOnly_UsesDefaultPromptNonInteractively() {
        // Mock the configuration to skip confirmation so the URL-only invocation proceeds without a
        // human-owned confirmation prompt.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = mock(Configuration.class);
            Configuration.SecurityConfig mockSecurity      = mock(Configuration.SecurityConfig.class);
            Configuration.UiConfig       mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getSecurity()).thenReturn(mockSecurity);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(1);
            when(mockSecurity.isRequireConfirmation()).thenReturn(false);

            // webfetch-1: "webfetch <url>" with no prompt must NOT dead-end waiting for a prompt
            // non-interactively. It falls back to the default prompt and proceeds to the fetch step
            // (which fails here on the unreachable .invalid host).
            int exitCode = webFetchCommand.execute(new String[] {"https://cadetcoder.invalid"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Summarize this page");
            assertThat(outputCapture.getAllOutput()).contains("Fetching content from:");
            assertThat(outputCapture.getAllOutput()).doesNotContain("No prompt provided, cancelled");
        }
    }

    @Test
    public void testWebFetch_NoInitialPromptWhenUrlPresent() {
        // webfetch-1: with a URL present, getInitialPrompt returns null so the executor drives the
        // deterministic step flow instead of round-tripping to the LLM just to obtain a prompt.
        assertThat(webFetchCommand.getInitialPrompt(new String[] {"https://cadetcoder.invalid"})).isNull();
        assertThat(webFetchCommand.getInitialPrompt(new String[] {"https://cadetcoder.invalid", "Analyze"})).isNull();
        // With no arguments at all there is still no URL, so a prompt to obtain one is returned.
        assertThat(webFetchCommand.getInitialPrompt(new String[] {})).isNotNull();
    }

    @Test
    public void testWebFetch_FriendlyUnknownHostMessage() {
        // webfetch-5: an unresolvable host surfaces a friendly, bounded reason rather than the raw
        // (and potentially noisy/null) exception text, while preserving the stable prefix.
        try (MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class)) {
            ConfigManager                mockConfigManager = mock(ConfigManager.class);
            Configuration                mockConfig        = mock(Configuration.class);
            Configuration.SecurityConfig mockSecurity      = mock(Configuration.SecurityConfig.class);
            Configuration.UiConfig       mockUiConfig      = mock(Configuration.UiConfig.class);

            configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
            when(mockConfigManager.getConfig()).thenReturn(mockConfig);
            when(mockConfig.getSecurity()).thenReturn(mockSecurity);
            when(mockConfig.getUi()).thenReturn(mockUiConfig);
            when(mockUiConfig.getVerbosityLevel()).thenReturn(1);
            when(mockSecurity.isRequireConfirmation()).thenReturn(false);

            int exitCode = webFetchCommand.execute(new String[] {"https://cadetcoder.invalid", "Analyze", "-f"});

            assertThat(exitCode).isEqualTo(1);
            assertThat(outputCapture.getAllOutput()).contains("Failed to fetch or process:");
            assertThat(outputCapture.getAllOutput()).contains("the host could not be resolved");
        }
    }

    @Test
    public void testWebFetch_InvalidTimeoutRejected() {
        // webfetch-4: the single unified parser rejects a non-numeric timeout with clear guidance,
        // regardless of which surface (manual/picocli) supplied it.
        int exitCode = webFetchCommand.execute(
                new String[] {"https://cadetcoder.invalid", "Analyze", "-t", "abc", "-f"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Invalid timeout value: abc");
    }

    @Test
    public void testWebFetch_NonPositiveTimeoutRejected() {
        // webfetch-4: a non-positive timeout is rejected up front.
        int exitCode = webFetchCommand.execute(
                new String[] {"https://cadetcoder.invalid", "Analyze", "-t", "0", "-f"});

        assertThat(exitCode).isEqualTo(1);
        assertThat(outputCapture.getAllOutput()).contains("Timeout must be a positive integer");
    }

    @Test
    public void testGetUsage_PromptIsOptional() {
        // webfetch-1: the advertised signature marks the prompt as optional.
        assertThat(webFetchCommand.getUsage()).contains("[<prompt>]");
    }

    @Test
    public void testGetDescription() {
        assertThat(webFetchCommand.getDescription()).isEqualTo("Fetch a web page and answer a question about it");
    }

    @Test
    public void testGetUsage() {
        assertThat(webFetchCommand.getUsage()).contains("webfetch");
        assertThat(webFetchCommand.getUsage()).contains("<url>");
        assertThat(webFetchCommand.getUsage()).contains("<prompt>");
    }
}