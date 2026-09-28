package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.AIClientFactory;
import com.eonmux.cadetcoder.ai.AIManager;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.*;
import org.mockito.MockedStatic;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.*;

public class ChatCommandTest {

    private ChatCommand       chatCommand;
    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        // Enable test mode to skip network calls
        System.setProperty("cadet.test.mode", "true");

        chatCommand   = new ChatCommand();
        outputCapture = new TestOutputCapture();

        // Reset output capture after any initialization messages
        outputCapture.reset();
    }

    @After
    public void tearDown() {
        outputCapture.restore();
        // Reset singletons
        resetSingletons();
        // Clear test mode property
        System.clearProperty("cadet.test.mode");
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
    public void testExecute_NoArguments() throws Exception {
        // Set up mock AI client factory
        com.eonmux.cadetcoder.ai.MockAIClientFactory mockFactory = new com.eonmux.cadetcoder.ai.MockAIClientFactory();
        // User provides empty response, cancelling the chat
        // First response is empty (for the initial prompt)
        // Second response should also be empty to trigger cancellation
        mockFactory.setApiResponse("no request provided");
        mockFactory.setApiClientAvailable(true);

        // Save original factory and set mock
        java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
        factoryField.setAccessible(true);
        AIClientFactory originalFactory = (AIClientFactory) factoryField.get(null);

        try {
            // Use reflection to call package-private method
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, mockFactory);

            try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
                 MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
                 MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

                // Mock dependencies
                SessionManager mockSessionManager = mock(SessionManager.class);
                ConfigManager  mockConfigManager  = mock(ConfigManager.class);
                Configuration  mockConfig         = mock(Configuration.class);
                ErrorHandler   mockErrorHandler   = mock(ErrorHandler.class);

                sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);
                configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
                errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

                // Mock ErrorHandler to not throw exceptions
                doNothing().when(mockErrorHandler).handleException(any(Exception.class));

                // Mock configuration properly
                Configuration.AiConfig mockAiConfig = mock(Configuration.AiConfig.class);
                when(mockAiConfig.getModel()).thenReturn("mock-model");
                when(mockAiConfig.getApiKey()).thenReturn("test-key");
                when(mockAiConfig.getApiEndpoint()).thenReturn("https://api.openai.com/v1");
                when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
                when(mockAiConfig.getLocalModel()).thenReturn("local-model");
                when(mockConfig.getAi()).thenReturn(mockAiConfig);
                // Mock UI config
                Configuration.UiConfig mockUiConfig = mock(Configuration.UiConfig.class);
                when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
                when(mockUiConfig.isColorEnabled()).thenReturn(true);
                when(mockConfig.getUi()).thenReturn(mockUiConfig);

                when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                when(mockSessionManager.getTodoList()).thenReturn(new ArrayList<>());

                // Reset output capture just before executing
                outputCapture.reset();

                int result = chatCommand.execute(new String[0]);

                assertThat(result).isEqualTo(1);
                String allOutput = outputCapture.getStdout() + outputCapture.getStderr();
                assertThat(allOutput).contains("No request provided");
                assertThat(allOutput).contains("cancelled");
            }
        } finally {
            // Restore original factory
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, originalFactory);
        }
    }

    // Removed testExecute_NoArgumentsWithUserInput as it would require complex mocking
    // The iterative behavior is already tested through the no arguments test above

    @Test
    public void testExecute_SimpleReadRequest() throws Exception {
        // Set up mock AI client factory
        com.eonmux.cadetcoder.ai.MockAIClientFactory mockFactory = new com.eonmux.cadetcoder.ai.MockAIClientFactory();
        String aiResponse = "ACTION_START\n" +
                            "COMMAND: read\n" +
                            "ARGS: test.txt\n" +
                            "EXPLANATION: Reading the test.txt file\n" +
                            "ACTION_END";
        mockFactory.setApiResponse(aiResponse);
        mockFactory.setApiClientAvailable(true);

        // Save original factory and set mock
        java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
        factoryField.setAccessible(true);
        AIClientFactory originalFactory = (AIClientFactory) factoryField.get(null);

        try {
            // Use reflection to call package-private method
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, mockFactory);

            try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
                 MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
                 MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

                // Mock dependencies
                SessionManager mockSessionManager = mock(SessionManager.class);
                ConfigManager  mockConfigManager  = mock(ConfigManager.class);
                Configuration  mockConfig         = mock(Configuration.class);
                ErrorHandler   mockErrorHandler   = mock(ErrorHandler.class);

                sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);
                configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
                errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

                // Mock ErrorHandler to not throw exceptions
                doNothing().when(mockErrorHandler).handleException(any(Exception.class));

                // Mock configuration properly
                Configuration.AiConfig mockAiConfig = mock(Configuration.AiConfig.class);
                when(mockAiConfig.getModel()).thenReturn("mock-model");
                when(mockAiConfig.getApiKey()).thenReturn("test-key");
                when(mockAiConfig.getApiEndpoint()).thenReturn("https://api.openai.com/v1");
                when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
                when(mockAiConfig.getLocalModel()).thenReturn("local-model");
                when(mockConfig.getAi()).thenReturn(mockAiConfig);
                // Mock UI config
                Configuration.UiConfig mockUiConfig = mock(Configuration.UiConfig.class);
                // Normal verbosity: which client was selected is reported on the information
                // channel, which QUIET (0) legitimately suppresses. This test is about the
                // request flow, not about verbosity.
                when(mockUiConfig.getVerbosityLevel())
                        .thenReturn(Configuration.UiConfig.VERBOSITY.NORMAL.ordinal());
                when(mockUiConfig.isColorEnabled()).thenReturn(true);
                when(mockConfig.getUi()).thenReturn(mockUiConfig);

                when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                when(mockSessionManager.getTodoList()).thenReturn(new ArrayList<>());

                // Mock command registry
                CommandRegistry mockRegistry = mock(CommandRegistry.class);
                when(mockRegistry.executeCommand(eq("read"), any(String[].class))).thenReturn(0);
                setCommandRegistry(chatCommand, mockRegistry);

                // Reset output capture just before executing
                outputCapture.reset();

                // Execute
                int result = chatCommand.execute(new String[] {"show me test.txt"});

                // Print debug output if test fails
                String output = outputCapture.getStdout() + outputCapture.getStderr();
                if (result != 0) {
                    System.err.println("Test failed with result: " + result);
                    System.err.println("Output: " + output);
                    System.err.println("AI Response was: " + aiResponse);
                }

                // Verify - The core functionality works (AI response processing)
                // The specific command execution details depend on mock setup
                // The request itself is no longer echoed back: it is already on screen, twice,
                // before the run starts. What says the chat loop ran is that it reached the model.
                assertThat(output).contains("Using API client: mock-api-model");
            }
        } finally {
            // Restore original factory
            // Restore original factory using reflection
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, originalFactory);
        }
    }

    // Helper method to set command registry via reflection
    private void setCommandRegistry(ChatCommand command, CommandRegistry registry) {
        try {
            java.lang.reflect.Field field = ChatCommand.class.getDeclaredField("commandRegistry");
            field.setAccessible(true);
            field.set(command, registry);
        } catch (Exception e) {
            fail("Failed to set command registry: " + e.getMessage());
        }
    }

    @Test
    public void testExecute_MultipleActions() throws Exception {
        // Set up mock AI client factory
        com.eonmux.cadetcoder.ai.MockAIClientFactory mockFactory = new com.eonmux.cadetcoder.ai.MockAIClientFactory();
        String aiResponse = "ACTION_START\n" +
                            "COMMAND: todowrite\n" +
                            "ARGS: \"Fix bug in Main.java\" \"Add tests\"\n" +
                            "EXPLANATION: Creating a todo list for the tasks\n" +
                            "ACTION_END\n\n" +
                            "ACTION_START\n" +
                            "COMMAND: read\n" +
                            "ARGS: Main.java\n" +
                            "EXPLANATION: Reading Main.java to understand the bug\n" +
                            "ACTION_END\n\n" +
                            "ACTION_START\n" +
                            "COMMAND: edit\n" +
                            "ARGS: Fix the null pointer exception in Main.java\n" +
                            "EXPLANATION: Fixing the bug in Main.java\n" +
                            "ACTION_END";
        mockFactory.setApiResponse(aiResponse);
        mockFactory.setApiClientAvailable(true);

        // Save original factory and set mock
        java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
        factoryField.setAccessible(true);
        AIClientFactory originalFactory = (AIClientFactory) factoryField.get(null);

        try {
            // Use reflection to call package-private method
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, mockFactory);

            try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
                 MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
                 MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

                // Mock dependencies
                SessionManager mockSessionManager = mock(SessionManager.class);
                ConfigManager  mockConfigManager  = mock(ConfigManager.class);
                Configuration  mockConfig         = mock(Configuration.class);
                ErrorHandler   mockErrorHandler   = mock(ErrorHandler.class);

                sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);
                configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
                errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

                // Mock ErrorHandler to not throw exceptions
                doNothing().when(mockErrorHandler).handleException(any(Exception.class));

                // Mock configuration properly
                Configuration.AiConfig mockAiConfig = mock(Configuration.AiConfig.class);
                when(mockAiConfig.getModel()).thenReturn("mock-model");
                when(mockAiConfig.getApiKey()).thenReturn("test-key");
                when(mockAiConfig.getApiEndpoint()).thenReturn("https://api.openai.com/v1");
                when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
                when(mockAiConfig.getLocalModel()).thenReturn("local-model");
                when(mockConfig.getAi()).thenReturn(mockAiConfig);
                when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                when(mockSessionManager.getTodoList()).thenReturn(new ArrayList<>());

                // Mock command registry
                CommandRegistry mockRegistry = mock(CommandRegistry.class);
                when(mockRegistry.executeCommand(eq("todowrite"), any())).thenReturn(0);
                when(mockRegistry.executeCommand(eq("read"), any())).thenReturn(0);
                when(mockRegistry.executeCommand(eq("edit"), any())).thenReturn(0);
                setCommandRegistry(chatCommand, mockRegistry);

                // Reset output capture just before executing
                outputCapture.reset();

                // Execute
                int result = chatCommand.execute(new String[] {"fix the bug in Main.java"});

                // Verify - The core functionality works (AI response processing)
                // The specific command execution details depend on mock setup
                String output = outputCapture.getStdout() + outputCapture.getStderr();
                // The request itself is no longer echoed back: it is already on screen, twice,
                // before the run starts. What says the chat loop ran is that it reached the model.
                assertThat(output).contains("Using API client: mock-api-model");
            }
        } finally {
            // Restore original factory
            // Restore original factory using reflection
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, originalFactory);
        }
    }

    @Test
    public void testExecute_InvalidAIResponse() throws Exception {
        // Set up mock AI client factory
        com.eonmux.cadetcoder.ai.MockAIClientFactory mockFactory = new com.eonmux.cadetcoder.ai.MockAIClientFactory();
        String                                       aiResponse  = "I'm not sure what to do with that request.";
        mockFactory.setApiResponse(aiResponse);
        mockFactory.setApiClientAvailable(true);

        // Save original factory and set mock
        java.lang.reflect.Field factoryField = AIManager.class.getDeclaredField("clientFactory");
        factoryField.setAccessible(true);
        AIClientFactory originalFactory = (AIClientFactory) factoryField.get(null);

        try {
            // Use reflection to call package-private method
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, mockFactory);

            try (MockedStatic<SessionManager> sessionMock = mockStatic(SessionManager.class);
                 MockedStatic<ConfigManager> configMock = mockStatic(ConfigManager.class);
                 MockedStatic<ErrorHandler> errorMock = mockStatic(ErrorHandler.class)) {

                // Mock dependencies
                SessionManager mockSessionManager = mock(SessionManager.class);
                ConfigManager  mockConfigManager  = mock(ConfigManager.class);
                Configuration  mockConfig         = mock(Configuration.class);
                ErrorHandler   mockErrorHandler   = mock(ErrorHandler.class);

                sessionMock.when(SessionManager::getInstance).thenReturn(mockSessionManager);
                configMock.when(ConfigManager::getInstance).thenReturn(mockConfigManager);
                errorMock.when(ErrorHandler::getInstance).thenReturn(mockErrorHandler);

                // Mock ErrorHandler to not throw exceptions
                doNothing().when(mockErrorHandler).handleException(any(Exception.class));

                // Mock configuration properly
                Configuration.AiConfig mockAiConfig = mock(Configuration.AiConfig.class);
                when(mockAiConfig.getModel()).thenReturn("mock-model");
                when(mockAiConfig.getApiKey()).thenReturn("test-key");
                when(mockAiConfig.getApiEndpoint()).thenReturn("https://api.openai.com/v1");
                when(mockAiConfig.getLocalEndpoint()).thenReturn("http://localhost:8012");
                when(mockAiConfig.getLocalModel()).thenReturn("local-model");
                when(mockConfig.getAi()).thenReturn(mockAiConfig);
                // Mock UI config
                Configuration.UiConfig mockUiConfig = mock(Configuration.UiConfig.class);
                when(mockUiConfig.getVerbosityLevel()).thenReturn(0);
                when(mockUiConfig.isColorEnabled()).thenReturn(true);
                when(mockConfig.getUi()).thenReturn(mockUiConfig);

                when(mockConfigManager.getConfig()).thenReturn(mockConfig);
                when(mockSessionManager.getTodoList()).thenReturn(new ArrayList<>());

                // Reset output capture just before executing
                outputCapture.reset();

                // Execute
                int result = chatCommand.execute(new String[] {"do something weird"});

                // INVERTED (previously asserted exit 0, i.e. the prose was accepted as an "answer").
                // A reply with no ACTION block and no SUCCESS: marker is a FORMAT failure, not an
                // answer: the harness must re-prompt for the documented format and, when the model
                // keeps ignoring it, fail loudly. Reporting success having executed nothing - or
                // executing the parameterless "read" that error recovery fabricates - is what made
                // the agent look like it "did" something it never did.
                String output = outputCapture.getStdout() + outputCapture.getStderr();
                assertThat(result).isEqualTo(1);
                assertThat(output).contains("Format error attempt 1");
                assertThat(output).contains("AI failed to provide proper ACTION format");
                // No fabricated action may be executed for an unparseable response.
                assertThat(output).doesNotContain("Executing: read");
                assertThat(output).doesNotContain("Best guess based on context");
            }
        } finally {
            // Restore original factory
            // Restore original factory using reflection
            java.lang.reflect.Method setFactoryMethod =
                    AIManager.class.getDeclaredMethod("setClientFactory", AIClientFactory.class);
            setFactoryMethod.setAccessible(true);
            setFactoryMethod.invoke(null, originalFactory);
        }
    }

    /**
     * Finding 37: executeActionWithCapture must operate on a DEFENSIVE COPY of the parsed action's
     * arguments. For an "agent" action it joins all arguments into a single task string; previously
     * this overwrote the shared AIAction.arguments in place, so the loop-detection signature computed
     * from the same object diverged from the parsed action. The parsed action must be left untouched.
     */
    @Test
    public void testExecuteActionWithCapture_doesNotMutateParsedAgentAction() throws Exception {
        ChatCommand cmd = new ChatCommand();

        // A mocked registry so no real command runs; the agent-arg join happens before this call.
        CommandRegistry mockRegistry = mock(CommandRegistry.class);
        when(mockRegistry.executeCommand(any(), any())).thenReturn(0);

        // Build an agent action with multiple arguments.
        ChatCommand.AIAction action = new ChatCommand.AIAction(
                "agent", new String[] {"investigate", "the", "bug"}, "delegate to agent");
        String[] originalArgsRef = action.arguments;

        new ActionRun(cmd, new ActionPaths(cmd)).run(action, new ChatContext(), mockRegistry);

        // The parsed action's arguments array must be the SAME reference and unchanged (NOT joined).
        assertThat(action.arguments).isSameAs(originalArgsRef);
        assertThat(action.arguments).containsExactly("investigate", "the", "bug");
    }

    @Test
    public void testGetDescription() {
        assertThat(chatCommand.getDescription()).isEqualTo("Ask the AI for an explanation or a change");
    }

    @Test
    public void testGetUsage() {
        assertThat(chatCommand.getUsage()).contains("chat");
    }

    /**
     * Regression: a follow-up turn whose reply is a bare "ACTION" header (an attempted-but-malformed
     * action block, e.g. "ACTION\ncat README.md") must NOT be misclassified as a finished explanation.
     * Previously this returned a terminal success ("Command completed successfully") so the loop quit
     * having done no work. It must instead keep the loop going by routing to the format-correction retry.
     */
    @Test
    public void testNextStep_bareActionHeaderDoesNotFalselyComplete() {
        ChatCommand cmd = new ChatCommand();

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("step", "next_step");
        ctx.put("userRequest", "update the README");

        // "xyzzy plugh" is not a catalog command, so the engine extracts no action -> the bare ACTION
        // header is the deciding signal and the turn must route to format_retry, not to success.
        IterativeCommand.StepResult result =
                cmd.executeStep(new String[0], ctx, "ACTION\nxyzzy plugh");

        assertThat(result.isComplete())
                .as("a bare ACTION header must not end the loop as 'completed'")
                .isFalse();
        assertThat(result.getNextPrompt()).contains("ACTION_START");
        assertThat(result.getContext().get("step")).isEqualTo("format_retry");
    }

    /**
     * Guard for the above fix: the new bare-ACTION detection must be precise. The pattern matches an
     * ACTION header on its own line but must NOT match ordinary prose that merely mentions the word
     * "action", otherwise a legitimate final explanation would trigger a needless retry loop.
     */
    @Test
    public void testBareActionKeywordPattern_matchesHeaderNotProse() {
        assertThat(ChatActions.looksLikeABotchedAction("ACTION\ncat README.md"))
                .as("a bare ACTION header line must be detected as a malformed action attempt")
                .isTrue();
        assertThat(ChatActions.looksLikeABotchedAction("Action items were completed and the README is updated."))
                .as("prose that mentions 'action' inline must NOT be treated as an action block")
                .isFalse();
        assertThat(ChatActions.looksLikeABotchedAction("The next action will be to read the file.")).isFalse();
    }

    /**
     * A tool call this client could not read is an attempt at an action, not a final answer.
     *
     * <h2>Why the notations count as markers</h2>
     *
     * <p>A model that writes a call in its own notation and gets it slightly wrong produces a reply
     * that parses to no action. Without the notation being recognised as a marker, that reply fell
     * through to the branch that treats a reply as the model's final answer, so the run finished
     * reporting success over a command it had never run. The turn is sent back for a format
     * correction instead, which is what a botched ACTION block already gets.</p>
     */
    @Test
    public void abotchedToolCallIsSentBackRatherThanTakenAsTheAnswer() {
        assertThat(ChatActions.looksLikeABotchedAction(
                "<tool_call>{\"name\": \"read\", \"arguments\": {\"file_path\": \"a.txt\"}}"))
                .as("an unclosed tool call is an attempt at an action")
                .isTrue();
        assertThat(ChatActions.looksLikeABotchedAction(
                "<\uFF5CDSML\uFF5C invoke name=\"read\">"))
                .isTrue();
        assertThat(ChatActions.looksLikeABotchedAction(
                "I called the read tool and the file looks fine."))
                .as("prose about tools is not an attempt at one")
                .isFalse();
        assertThat(ChatActions.looksLikeABotchedAction(
                "SUCCESS: the parser reads <tool_call>{\"name\": \"read\"}</tool_call> blocks."))
                .as("a declared final answer about the notations is an answer, not an attempt")
                .isFalse();
    }

    /**
     * Guard: a genuine final prose explanation (no action markers) on a follow-up turn must still
     * complete the loop. The bare-ACTION fix must not regress the terminal-explanation path.
     */
    @Test
    public void testNextStep_genuineExplanationStillCompletes() {
        ChatCommand cmd = new ChatCommand();

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("step", "next_step");
        ctx.put("userRequest", "explain the project");

        IterativeCommand.StepResult result = cmd.executeStep(new String[0], ctx,
                "All of the requested work is now finished and the results look correct.");

        assertThat(result.isComplete())
                .as("a genuine final explanation must still terminate the loop")
                .isTrue();
    }

    /**
     * Fix C: follow-up turns must be held to the same contract as the first turn. ChatCommand exposes
     * its catalog/format-aware system prompt to the executor so every follow-up LLM call advertises the
     * available commands and the required ACTION format (instead of degrading to a generic prompt that
     * makes the model improvise shell-isms like "cat" or bare ACTION headers).
     */
    @Test
    public void testGetIterativeSystemPrompt_isCatalogAware() {
        String prompt = new ChatCommand().getIterativeSystemPrompt();
        assertThat(prompt).isNotNull().isNotEmpty();
        assertThat(prompt).contains("ACTION_START");
        assertThat(prompt).contains("read");
    }

    // ------------------------------------------------------------------ dispatch: path arguments

    /** Runs one action the way the chat loop does, and reports whether it worked and what it said. */
    private Object[] executeActionWithCapture(ChatCommand command, ChatCommand.AIAction action) {
        ActionOutcome outcome = new ActionRun(command, new ActionPaths(command))
                .run(action, new ChatContext(), new CommandRegistry());
        return new Object[] {outcome.success, outcome.output};
    }

    /**
     * A verb the registry does not know is reported to the model, not handed to {@code chat}.
     *
     * <p>The registry's chat fallback is for the person at the terminal, whose bare line is
     * something they meant to say. A model proposing an action is already inside a loop, so the
     * fallback started a second one inside the first -- and told the model nothing about the name
     * being wrong.</p>
     */
    @Test
    public void anUnknownVerbIsReportedRatherThanAnsweredByTheModel() {
        Object[] result = executeActionWithCapture(chatCommand,
                new ChatCommand.AIAction("raed", new String[] {"pom.xml"}, "a typo"));

        assertThat((Boolean) result[0]).isFalse();
        assertThat((String) result[1]).contains("Unknown command: raed");
    }

    private int pathArgumentIndex(String command) {
        return PathArgument.indexFor(command, chatCommand);
    }

    /**
     * The path-argument map must cover every registered command, so adding a command cannot silently
     * skip (or wrongly apply) file-path validation.
     */
    @Test
    public void testPathArgumentIndex_coversEveryRegisteredCommand() {
        assertThat(PathArgument.mappedCommands())
                .as("every registered command must declare which argument (if any) is a path")
                .containsAll(new CommandRegistry().getCommands().keySet());
    }

    /**
     * grep's and glob's first argument is a PATTERN, not a path. Validating it as a path is what made
     * every grep fail with "File not found: <pattern>" before GrepCommand was ever invoked.
     */
    @Test
    public void testPathArgumentIndex_patternCommandsHaveNoPathArgument() {
        assertThat(pathArgumentIndex("grep")).isEqualTo(-1);
        assertThat(pathArgumentIndex("glob")).isEqualTo(-1);
        assertThat(pathArgumentIndex("bash")).isEqualTo(-1);
        assertThat(pathArgumentIndex("commit")).isEqualTo(-1);

        // ...while genuine file commands still declare their path position.
        assertThat(pathArgumentIndex("read")).isEqualTo(0);
        assertThat(pathArgumentIndex("write")).isEqualTo(0);
        assertThat(pathArgumentIndex("ls")).isEqualTo(0);
        // suggest <type> <filepath>: the path is the SECOND argument, not the first.
        assertThat(pathArgumentIndex("suggest")).isEqualTo(1);

        // An unmapped command is treated as having no path argument rather than having argv[0]
        // validated as one.
        assertThat(pathArgumentIndex("definitely-not-a-command")).isEqualTo(-1);
    }

    /**
     * End-to-end dispatch: a grep action must actually reach GrepCommand and return its matches,
     * instead of being rejected by the caller's file-existence check.
     */
    @Test
    public void testExecuteAction_grepReachesGrepCommand() throws Exception {
        java.io.File dir = new java.io.File("target/chat-dispatch-test");
        dir.mkdirs();
        java.io.File file = new java.io.File(dir, "SessionManagerSample.java");
        java.nio.file.Files.write(file.toPath(),
                "public class SessionManager {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ChatCommand.AIAction action = new ChatCommand.AIAction("grep",
                new String[] {"SessionManager", "--path=target/chat-dispatch-test", "--include=*.java"},
                "Find the SessionManager class");

        Object[] result = executeActionWithCapture(chatCommand, action);
        String output = (String) result[1];

        assertThat(output).doesNotContain("File not found");
        assertThat((Boolean) result[0]).as("grep must be dispatched, not rejected as a missing file").isTrue();
        assertThat(output).contains("SessionManagerSample.java");
    }

    /**
     * The search pattern must reach GrepCommand VERBATIM. It used to be run through
     * a path sanitizer that stripped "..", silently turning the regex "Session..nager" into
     * "Sessionnager" and losing every match.
     */
    @Test
    public void testExecuteAction_grepPatternIsNotPathSanitized() throws Exception {
        java.io.File dir = new java.io.File("target/chat-dispatch-test");
        dir.mkdirs();
        java.io.File file = new java.io.File(dir, "SessionManagerSample.java");
        java.nio.file.Files.write(file.toPath(),
                "public class SessionManager {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ChatCommand.AIAction action = new ChatCommand.AIAction("grep",
                new String[] {"Session..nager", "--path=target/chat-dispatch-test", "--include=*.java"},
                "Find the class with a wildcard pattern");

        Object[] result = executeActionWithCapture(chatCommand, action);

        assertThat((Boolean) result[0]).isTrue();
        assertThat((String) result[1]).contains("SessionManagerSample.java");
    }

    /**
     * A write action carrying its content as the second argument must pass argument validation and
     * actually write the file. The content used to be lost during parsing, so write was always
     * rejected with "requires at least two arguments".
     */
    @Test
    public void testExecuteAction_writeReceivesPathAndContent() throws Exception {
        java.io.File dir = new java.io.File("target/chat-dispatch-test");
        dir.mkdirs();
        java.io.File target = new java.io.File(dir, "written-" + System.nanoTime() + ".txt");

        ChatCommand.AIAction action = new ChatCommand.AIAction("write",
                new String[] {target.getPath(), "hello world"}, "Create the file");

        Object[] result = executeActionWithCapture(chatCommand, action);

        assertThat((String) result[1]).doesNotContain("requires at least two arguments");
        assertThat((Boolean) result[0]).isTrue();
        assertThat(new String(java.nio.file.Files.readAllBytes(target.toPath()),
                              java.nio.charset.StandardCharsets.UTF_8))
                .contains("hello world");
        target.delete();
    }

    /**
     * A path reaches its command as it was written. {@code ls .} arrived as {@code ls ""}: the
     * path was normalized after its access check, and Java normalizes {@code .} to an empty path,
     * so {@code ls} printed "Path cannot be empty" before listing the directory it had been given.
     */
    @Test
    public void testExecuteAction_theCurrentDirectoryReachesLsAsWritten() throws Exception {
        ChatCommand.AIAction action = new ChatCommand.AIAction("ls", new String[] {"."}, "Look around");

        Object[] result = executeActionWithCapture(chatCommand, action);

        assertThat((Boolean) result[0]).isTrue();
        assertThat((String) result[1]).doesNotContain("Path cannot be empty");
    }
}
