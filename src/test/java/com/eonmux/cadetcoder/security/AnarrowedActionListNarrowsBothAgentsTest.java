package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ai.parsing.ParsedAction;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;
import com.eonmux.cadetcoder.ai.parsing.SecurityValidator;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.cadet.CommandEnvironment;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What {@code security.allowedActions} covers.
 *
 * <h2>The defect</h2>
 *
 * <p>The setting names the only commands an agent may run. It was read in one place,
 * {@code SecurityValidator.validateAction}, which screens the classic {@code chat} loop. The default
 * {@code agent} harness never consults that validator: it reads an action, builds a
 * {@code CommandInvocation}, and runs it. So a user who narrowed the list to {@code read} and
 * {@code search} narrowed nothing at all on the path the tool runs by default, and {@code bash} went
 * through untouched.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That the rule lives in one class and both paths ask it the same question, so the answer cannot
 * differ between them. An empty list still means "no list", which is the shipped default and permits
 * everything.</p>
 */
public class AnarrowedActionListNarrowsBothAgentsTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Runs {@code body} with {@code security.allowedActions} set to {@code allowed}. */
    private static void withAllowed(String[] allowed, Runnable body) {
        Configuration config = new Configuration();
        config.getSecurity().setAllowedActions(allowed);
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            body.run();
        }
    }

    @Test
    public void anEmptyListPermitsEverything() {
        withAllowed(new String[]{}, () -> {
            assertThat(AllowedActions.permits("bash")).isTrue();
            assertThat(AllowedActions.permits("write")).isTrue();
            assertThat(AllowedActions.reasonToRefuse("bash")).isNull();
        });
    }

    @Test
    public void anUnreadableListMeansNoList() {
        // A configuration that answers null, rather than an empty array, must not refuse everything.
        // The shipped default permits everything, so that is what an absent answer means here too.
        Configuration.SecurityConfig security = mock(Configuration.SecurityConfig.class);
        when(security.getAllowedActions()).thenReturn(null);
        Configuration config = mock(Configuration.class);
        when(config.getSecurity()).thenReturn(security);
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);

        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            assertThat(AllowedActions.permits("bash")).isTrue();
        }
    }

    @Test
    public void anarrowedListPermitsOnlyWhatItNames() {
        withAllowed(new String[]{"read", "search"}, () -> {
            assertThat(AllowedActions.permits("read")).isTrue();
            assertThat(AllowedActions.permits("search")).isTrue();
            assertThat(AllowedActions.permits("bash")).isFalse();
            assertThat(AllowedActions.permits("write")).isFalse();
        });
    }

    @Test
    public void theNameIsMatchedWithoutRegardToCaseOrSurroundingSpace() {
        withAllowed(new String[]{"Read"}, () -> {
            assertThat(AllowedActions.permits("read")).isTrue();
            assertThat(AllowedActions.permits("  READ  ")).isTrue();
        });
    }

    @Test
    public void arefusalNamesTheActionAndTheSetting() {
        withAllowed(new String[]{"read"}, () ->
                assertThat(AllowedActions.reasonToRefuse("bash"))
                        .contains("bash")
                        .contains("security.allowedActions"));
    }

    @Test
    public void anActionNamingNothingIsRefusedRatherThanPermitted() {
        withAllowed(new String[]{"read"}, () -> {
            assertThat(AllowedActions.permits(null)).isFalse();
            assertThat(AllowedActions.permits("")).isFalse();
        });
    }

    @Test
    public void theClassicLoopAsksTheSameRule() {
        // SecurityValidator keeps its refusal, and it now comes from the shared rule rather than
        // from a second copy of the list-matching code.
        withAllowed(new String[]{"read"}, () -> {
            ParsedAction action = new ParsedAction.Builder("bash")
                    .addParameter("command", "ls -la")
                    .setReasoning("Look around")
                    .build();
            SecurityValidator.ValidationResult verdict = new SecurityValidator().validateAction(
                    action,
                    new ParsingContext.Builder("do some work")
                            .workingDirectory(System.getProperty("user.dir"))
                            .build());

            assertThat(verdict.isValid()).isFalse();
            assertThat(verdict.getViolations())
                    .anySatisfy(entry -> assertThat(entry).contains("security.allowedActions"));
        });
    }

    @Test
    public void theAgentHarnessRefusesWhatTheListLeavesOut() throws Exception {
        AtomicInteger   ran      = new AtomicInteger();
        CommandRegistry commands = new CommandRegistry();
        commands.register("bash", counting(ran));

        withAllowed(new String[]{"read"}, () -> {
            StepOutcome happened = world(commands).act(action("bash rm -rf src"));

            assertThat(ran).as("the command must not run at all").hasValue(0);
            assertThat(Json.at(happened.observation(), "exit")).isEqualTo(1);
            assertThat((String) Json.at(happened.observation(), "output"))
                    .contains("security.allowedActions");
            assertThat(happened.isTerminal())
                    .as("a refusal costs a step; it does not end the run")
                    .isFalse();
        });
    }

    @Test
    public void theAgentHarnessStillRunsWhatTheListNames() throws Exception {
        AtomicInteger   ran      = new AtomicInteger();
        CommandRegistry commands = new CommandRegistry();
        commands.register("read", counting(ran));

        withAllowed(new String[]{"read"}, () -> {
            world(commands).act(action("read pom.xml"));

            assertThat(ran).hasValue(1);
        });
    }

    @Test
    public void theAgentHarnessIsUnchangedWhenNoListIsSet() throws Exception {
        AtomicInteger   ran      = new AtomicInteger();
        CommandRegistry commands = new CommandRegistry();
        commands.register("bash", counting(ran));

        withAllowed(new String[]{}, () -> {
            world(commands).act(action("bash ls"));

            assertThat(ran).hasValue(1);
        });
    }

    /** An environment over an empty workspace. */
    private CommandEnvironment world(CommandRegistry commands) {
        return new CommandEnvironment(commands, folder.getRoot().toPath(), "do some work", null);
    }

    /** An action in the shape the model writes. */
    private static Map<String, Object> action(String line) {
        Map<String, Object> asked = new LinkedHashMap<>();
        asked.put("command", line);
        return asked;
    }

    /** A command that records having run and succeeds. */
    private static CommandRegistry.Command counting(AtomicInteger ran) {
        return new CommandRegistry.Command() {
            @Override
            public int execute(String[] args) {
                ran.incrementAndGet();
                return 0;
            }

            @Override
            public String getUsage() {
                return "counting";
            }
        };
    }
}
