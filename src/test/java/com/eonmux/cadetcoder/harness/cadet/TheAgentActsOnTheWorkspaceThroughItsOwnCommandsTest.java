package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.env.Reversibility;
import com.eonmux.cadetcoder.harness.env.StepOutcome;
import com.eonmux.cadetcoder.commands.CommandOutputBudget;
import com.eonmux.cadetcoder.commands.ModelDispatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The world the agent acts on is this project, reached through the same commands a person types.
 *
 * <h2>The defect these lock out</h2>
 *
 * <p>An environment is only useful to a model if its observations have one shape. An observation
 * that carries {@code output} after a command and omits it before is two shapes, and a model written
 * for one is contradicted by the other through no fault of its own -- so the first observation of
 * every episode has the same keys as every later one.</p>
 *
 * <p>The other half is what the world does with nonsense. A model that emits an action the
 * environment cannot read has made a mistake worth one wasted step; an environment that throws over
 * it has ended a run that had budget left. Nonsense is answered, not fatal.</p>
 */
class TheAgentActsOnTheWorkspaceThroughItsOwnCommandsTest {

    /** What the task looks like in a describe(); any sentence would do. */
    private static final String TASK = "make the failing test in ExampleTest pass";

    /** Small enough that a test can overrun it without building a hundred thousand characters. */
    private static final String SHORT_LIMIT = "64";

    private String restoreLimit;

    @BeforeEach
    void rememberOutputLimit() {
        restoreLimit = System.getProperty(CommandOutputBudget.PROPERTY);
    }

    @AfterEach
    void restoreOutputLimit() {
        if (restoreLimit == null) {
            System.clearProperty(CommandOutputBudget.PROPERTY);
        } else {
            System.setProperty(CommandOutputBudget.PROPERTY, restoreLimit);
        }
    }

    @Test
    void whatACommandPrintedIsWhatTheAgentSees(@TempDir Path workspace) {
        CommandRegistry commands = registryWith("probe", saying("three files, all green", 0));

        StepOutcome happened = environment(commands, workspace, null).act(action("probe"));

        assertThat(Json.at(happened.observation(), "command")).isEqualTo("probe");
        assertThat(Json.at(happened.observation(), "exit")).isEqualTo(0);
        assertThat((String) Json.at(happened.observation(), "output"))
                .contains("three files, all green");
    }

    @Test
    void aCommandThatFailedSaysSoWithItsOwnExitCode(@TempDir Path workspace) {
        CommandRegistry commands = registryWith("stumble", saying("could not open it", 2));

        StepOutcome happened = environment(commands, workspace, null).act(action("stumble"));

        assertThat(Json.at(happened.observation(), "exit")).isEqualTo(2);
        assertThat(happened.isGoal()).isFalse();
        assertThat(happened.isTerminal()).isFalse();
    }

    @Test
    void anActionThatNamesNoCommandIsAnsweredRatherThanEndingTheRun(@TempDir Path workspace) {
        CommandEnvironment world = environment(new CommandRegistry(), workspace, null);

        StepOutcome happened = world.act(new LinkedHashMap<String, Object>());

        assertThat(Json.at(happened.observation(), "command")).isEqualTo("");
        assertThat(Json.at(happened.observation(), "exit")).isEqualTo(1);
        assertThat((String) Json.at(happened.observation(), "output")).isNotEmpty();
        assertThat(happened.isTerminal()).isFalse();
    }

    @Test
    void aWholeCommandLineIsSplitTheWayAShellWouldSplitIt(@TempDir Path workspace) {
        AtomicInteger      seen = new AtomicInteger();
        String[][]         got  = new String[1][];
        CommandRegistry commands = registryWith("probe", command(args -> {
            got[0] = args;
            seen.incrementAndGet();
            return 0;
        }));

        environment(commands, workspace, null).act(action("probe \"two words\" -n"));

        assertThat(seen).hasValue(1);
        assertThat(got[0]).containsExactly("two words", "-n");
    }

    @Test
    void theFirstObservationOfAnEpisodeHasTheSameShapeAsEveryLaterOne(@TempDir Path workspace) {
        CommandRegistry   commands = registryWith("probe", saying("done", 0));
        CommandEnvironment world   = environment(commands, workspace, null);

        Object before = world.reset();
        Object after  = world.act(action("probe")).observation();

        assertThat(keysOf(before)).isEqualTo(keysOf(after));
        assertThat(Json.at(before, "command")).isEqualTo("");
        assertThat(Json.at(before, "exit")).isEqualTo(0);
        assertThat(Json.at(before, "output")).isEqualTo("");
    }

    @Test
    void theWorkspaceComesBackWithEveryObservationSoAChangeCannotGoUnnoticed(
            @TempDir Path workspace) throws IOException {
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");
        CommandRegistry commands = registryWith("touchfile", command(args -> {
            try {
                Files.writeString(workspace.resolve("added.txt"), "new");
                return 0;
            } catch (IOException e) {
                return 1;
            }
        }));
        CommandEnvironment world = environment(commands, workspace, null);

        Object before = world.observe();
        Object after  = world.act(action("touchfile")).observation();

        assertThat(Json.at(before, "tree_total")).isEqualTo(1);
        assertThat(Json.at(after, "tree_total")).isEqualTo(2);
        assertThat(Json.at(after, "tree_hash")).isNotEqualTo(Json.at(before, "tree_hash"));
        assertThat(tree(after)).contains("added.txt");
    }

    @Test
    void aResetIsARegroundingPointRatherThanAWorkspaceThrownAway(@TempDir Path workspace)
            throws IOException {
        Files.writeString(workspace.resolve("written-earlier.txt"), "still here");
        CommandEnvironment world = environment(new CommandRegistry(), workspace, null);

        assertThat(Json.equal(world.reset(), world.observe())).isTrue();
        assertThat(tree(world.observe())).contains("written-earlier.txt");
    }

    @Test
    void theActionSpaceIsOpenEndedSoAModelHasToSupplyItsOwn(@TempDir Path workspace) {
        CommandEnvironment world = environment(new CommandRegistry(), workspace, null);

        assertThat(world.actionSpace(world.observe())).isNull();
    }

    @Test
    void theGateAndTheWorldReadAnActionTheSameWay(@TempDir Path workspace) {
        CommandEnvironment world = environment(new CommandRegistry(), workspace, null);

        assertThat(world.reversibility(action("read pom.xml"))).isEqualTo(Reversibility.REVERSIBLE);
        assertThat(world.reversibility(action("read", List.of("pom.xml"))))
                .isEqualTo(Reversibility.REVERSIBLE);
        assertThat(world.reversibility(action("bash rm -rf src")))
                .isEqualTo(Reversibility.IRREVERSIBLE);
    }

    @Test
    void theGoalIsRaisedOnlyWhenTheCheckTheUserGaveActuallyPasses(@TempDir Path workspace) {
        AtomicInteger verdict = new AtomicInteger(1);
        CommandRegistry commands = registryWith("probe", saying("worked on it", 0));
        commands.register("verify", command(args -> verdict.get()));
        CommandEnvironment world = environment(commands, workspace, "verify");

        assertThat(world.act(action("probe")).isGoal()).isFalse();
        verdict.set(0);
        assertThat(world.act(action("probe")).isGoal()).isTrue();
    }

    @Test
    void whatTheGoalCheckPrintedIsDiagnosticRatherThanSomethingToPredict(@TempDir Path workspace) {
        CommandRegistry commands = registryWith("probe", saying("worked on it", 0));
        commands.register("verify", saying("2 tests, 1 failure", 3));
        CommandEnvironment world = environment(commands, workspace, "verify");

        StepOutcome happened = world.act(action("probe"));

        assertThat((String) happened.info().get("goal_check")).contains("2 tests, 1 failure");
        assertThat(happened.info().get("goal_exit")).isEqualTo(3);
        assertThat((String) Json.at(happened.observation(), "output"))
                .doesNotContain("2 tests, 1 failure");
    }

    @Test
    void withoutACheckTheEnvironmentNeverClaimsTheGoalAndSaysSo(@TempDir Path workspace) {
        CommandRegistry    commands = registryWith("probe", saying("worked on it", 0));
        CommandEnvironment world    = environment(commands, workspace, null);

        StepOutcome happened = world.act(action("probe"));

        assertThat(happened.isGoal()).isFalse();
        assertThat(happened.info()).doesNotContainKey("goal_check");
        assertThat(world.describe()).contains(CommandEnvironment.NO_CHECK);
    }

    @Test
    void outputTooLargeToSendIsTruncatedRatherThanSent(@TempDir Path workspace) {
        System.setProperty(CommandOutputBudget.PROPERTY, SHORT_LIMIT);
        CommandRegistry commands = registryWith("noisy", saying("x".repeat(4000), 0));

        Object seen = environment(commands, workspace, null).act(action("noisy")).observation();

        assertThat((String) Json.at(seen, "output"))
                .hasSizeLessThan(4000)
                .contains("were not included");
    }

    @Test
    void whatIsDescribedIsTheTaskAndTheInterfaceRatherThanWhatEachCommandDoesToTheWorkspace(
            @TempDir Path workspace) {
        String described = environment(new CommandRegistry(), workspace, "verify").describe();

        assertThat(described).contains(TASK);
        assertThat(described).contains("\"command\"");
        assertThat(described).contains("tree_hash");
        assertThat(described).contains("- read <filepath>");
        assertThat(described).contains("verify");
    }

    @Test
    void aCommandTheAgentRunsIsMarkedAsComingFromAModelRatherThanFromAPerson(
            @TempDir Path workspace) {
        AtomicBoolean asModel = new AtomicBoolean();
        CommandRegistry commands = registryWith("probe", command(args -> {
            asModel.set(ModelDispatch.isModelDriven());
            return 0;
        }));

        environment(commands, workspace, null).act(action("probe"));

        assertThat(asModel).isTrue();
    }

    @Test
    void theGoalCheckIsTheModelsDoingTooAndIsMarkedTheSameWay(@TempDir Path workspace) {
        AtomicBoolean asModel = new AtomicBoolean();
        CommandRegistry commands = new CommandRegistry();
        commands.register("probe", saying("done", 0));
        commands.register("verify", command(args -> {
            asModel.set(ModelDispatch.isModelDriven());
            return 0;
        }));

        environment(commands, workspace, "verify").act(action("probe"));

        assertThat(asModel).isTrue();
    }

    @Test
    void whatAPersonTypesNextIsStillNotMarkedAfterAnAgentHasRunSomething(@TempDir Path workspace) {
        CommandRegistry commands = registryWith("probe", saying("done", 0));

        environment(commands, workspace, null).act(action("probe"));

        assertThat(ModelDispatch.isModelDriven()).isFalse();
    }

    @Test
    void anAgentCannotStartAnotherAgentInsideItsOwnRun(@TempDir Path workspace) {
        AtomicInteger launched = new AtomicInteger();
        CommandRegistry commands = registryWith("agent", command(args -> {
            launched.incrementAndGet();
            return 0;
        }));

        StepOutcome happened = environment(commands, workspace, null).act(action("agent do it all"));

        assertThat(launched).hasValue(0);
        assertThat(Json.at(happened.observation(), "exit")).isEqualTo(1);
        assertThat((String) Json.at(happened.observation(), "output")).contains("agent");
    }

    @Test
    void anAgentCannotStartAChatInsideItsOwnRunEither(@TempDir Path workspace) {
        AtomicInteger launched = new AtomicInteger();
        CommandRegistry commands = registryWith("chat", command(args -> {
            launched.incrementAndGet();
            return 0;
        }));

        StepOutcome happened = environment(commands, workspace, null).act(action("chat", List.of("hello")));

        assertThat(launched).hasValue(0);
        assertThat(Json.at(happened.observation(), "exit")).isEqualTo(1);
    }

    private static CommandEnvironment environment(CommandRegistry commands, Path workspace,
                                                  String goalCheck) {
        return new CommandEnvironment(commands, workspace, TASK, goalCheck);
    }

    private static CommandRegistry registryWith(String name, CommandRegistry.Command command) {
        CommandRegistry commands = new CommandRegistry();
        commands.register(name, command);
        return commands;
    }

    /** What a test double does when it is run. */
    private interface Body {
        int run(String[] args);
    }

    /**
     * A registrable command that runs {@code body}.
     *
     * <p>{@link CommandRegistry.Command} carries a usage line as well as a body, so it is not a
     * functional interface and a double has to be written out.</p>
     */
    private static CommandRegistry.Command command(Body body) {
        return new CommandRegistry.Command() {
            @Override
            public int execute(String[] args) {
                return body.run(args);
            }

            @Override
            public String getUsage() {
                return "a test double";
            }
        };
    }

    /** A command that prints one line and leaves with a chosen exit code. */
    private static CommandRegistry.Command saying(String line, int exitCode) {
        return command(args -> {
            System.out.println(line);
            return exitCode;
        });
    }

    private static Map<String, Object> action(String command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("command", command);
        return value;
    }

    private static Map<String, Object> action(String command, Object args) {
        Map<String, Object> value = action(command);
        value.put("args", args);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> tree(Object observation) {
        return (List<String>) Json.at(observation, "tree");
    }

    @SuppressWarnings("unchecked")
    private static List<String> keysOf(Object observation) {
        return List.copyOf(((Map<String, Object>) observation).keySet());
    }
}
