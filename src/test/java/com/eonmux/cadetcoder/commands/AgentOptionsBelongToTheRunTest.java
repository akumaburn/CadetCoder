package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code -t}, {@code -m}, {@code -v} and {@code -y} were given for, and how long they last.
 *
 * <p>The argument vector is parsed twice, in two different places, and the two disagreed.
 * {@code execute} consumed the options into the command's own fields and handed the executor only
 * the remaining task tokens; {@code executeStep} then parsed that stripped vector a second time,
 * found no options in it, and put the defaults into the run context -- and the context is what the
 * run is actually governed by. So {@code -m 5} did not stop the agent after five steps, {@code -t 30}
 * did not stop it after thirty seconds, and {@code -v} showed nothing. The same is true of the step
 * budget {@code WorkerPool} gives each worker, which it passes as {@code -m}.</p>
 *
 * <p>What the first parse did do was write those fields, on the single instance the registry keeps
 * for the whole session. Nothing put them back, so the budget one invocation was given was still
 * there for the next one -- including {@code -y}, whose leak means an agent nobody consented to
 * running skips the question and starts work.</p>
 */
public class AgentOptionsBelongToTheRunTest {

    private final Map<String, Object> runContext = new HashMap<>();

    private TestOutputCapture outputCapture;

    @Before
    public void setUp() {
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();
    }

    /**
     * An agent that stops at the point its options have become the run's.
     *
     * <p>{@code confirm_task} is the first step reached with the vector fully parsed, and stopping
     * there keeps the AI client and the session singletons out of it -- the question under test is
     * settled before any of them is needed.</p>
     */
    private AgentCommand agentRecordingItsBudget() {
        return new AgentCommand() {
            @Override
            public IterativeCommand.StepResult executeStep(String[] args, Map<String, Object> ctx,
                                                           String llmResponse) {
                if ("confirm_task".equals(ctx.get("step"))) {
                    runContext.clear();
                    runContext.putAll(ctx);
                    return IterativeCommand.StepResult.success("intercepted", ctx);
                }
                return super.executeStep(args, ctx, llmResponse);
            }
        };
    }

    @Test
    public void theStepBudgetOnTheCommandLineGovernsTheRun() {
        agentRecordingItsBudget().execute(new String[] {"-m", "5", "tidy", "the", "imports"});

        assertThat(runContext.get("maxStepCount"))
                .as("the run the user asked for is one that stops after five steps")
                .isEqualTo(5);
    }

    @Test
    public void theTimeBudgetOnTheCommandLineGovernsTheRun() {
        agentRecordingItsBudget().execute(new String[] {"-t", "30", "tidy", "the", "imports"});

        assertThat(runContext.get("timeoutSec")).isEqualTo(30);
    }

    @Test
    public void verboseOnTheCommandLineGovernsTheRun() {
        agentRecordingItsBudget().execute(new String[] {"-v", "tidy", "the", "imports"});

        assertThat(runContext.get("verboseMode")).isEqualTo(true);
    }

    @Test
    public void theTaskIsWhatIsLeftWhenTheOptionsAreTakenOut() {
        agentRecordingItsBudget().execute(
                new String[] {"-m", "5", "tidy", "the", "imports", "-v", "-y"});

        assertThat(runContext.get("task"))
                .as("an option is not part of the task the model is asked to do")
                .isEqualTo("tidy the imports");
    }

    /** The inline form the shell accepts everywhere else. */
    @Test
    public void theInlineFormOfAnOptionGovernsTheRunToo() {
        agentRecordingItsBudget().execute(new String[] {"--max-steps=7", "tidy", "the", "imports"});

        assertThat(runContext.get("maxStepCount")).isEqualTo(7);
        assertThat(runContext.get("task")).isEqualTo("tidy the imports");
    }

    @Test
    public void whatOneInvocationWasGivenIsNotAppliedToTheNext() {
        AgentCommand agent = agentRecordingItsBudget();

        agent.execute(new String[] {"-m", "5", "-t", "30", "-v", "-y", "tidy", "the", "imports"});
        agent.execute(new String[] {"write", "the", "release", "notes"});

        assertThat(runContext.get("maxStepCount"))
                .as("nobody asked this run to stop after five steps")
                .isEqualTo(AgentOptions.UNLIMITED);
        assertThat(runContext.get("timeoutSec")).isEqualTo(AgentOptions.UNLIMITED);
        assertThat(runContext.get("verboseMode")).isEqualTo(false);
        assertThat(runContext.get("preconfirmed"))
                .as("consent given for one task is not consent for the next")
                .isEqualTo(false);
    }

    /**
     * The general form: a run is given its options, it does not take them.
     *
     * <p>The {@code @Option} fields are picocli's binding for {@link AgentCommand#call()} and nothing
     * else. A run that writes them is a run whose settings outlive it, which is the whole of the
     * defect above however many options there come to be.</p>
     */
    @Test
    public void aRunLeavesTheCommandsOptionFieldsAsItFoundThem() throws Exception {
        AgentCommand agent = agentRecordingItsBudget();
        Map<String, Object> before = optionFields(agent);

        agent.execute(new String[] {"-m", "5", "-t", "30", "-v", "-y", "tidy", "the", "imports"});

        assertThat(optionFields(agent)).isEqualTo(before);
    }

    private static Map<String, Object> optionFields(AgentCommand agent) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Field field : AgentCommand.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(CommandLine.Option.class)) {
                field.setAccessible(true);
                values.put(field.getName(), field.get(agent));
            }
        }
        assertThat(values).as("the options this test is about").isNotEmpty();
        return values;
    }
}
