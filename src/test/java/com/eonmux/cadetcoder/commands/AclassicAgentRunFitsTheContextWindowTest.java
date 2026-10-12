package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptData;
import com.eonmux.cadetcoder.ai.metrics.TokenCalibration;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ContextEngine;
import com.eonmux.cadetcoder.net.LLMErrorMapper;
import com.eonmux.cadetcoder.test.StubbedProvider;
import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The classic agent's prompt carries the output of every action it ran.
 *
 * <h2>The defect</h2>
 *
 * <p>Nothing limited it. Each action's output went into every later prompt, so a long run grew past
 * the model's context window, the provider refused the request, and the run ended.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That once the prompt passes the compaction trigger, the oldest outputs are left out until it
 * is back under the target, and the latest output is always kept; that what was left out stays left
 * out, so the prompt's prefix does not change again on the next step; and that a refusal for length
 * leaves outputs out on the provider's count and asks again.</p>
 */
public class AclassicAgentRunFitsTheContextWindowTest {

    private static final String LIST_FILES = "ACTION_START\nCOMMAND: ls\nARGS: .\n"
                                             + "REASON: see what is there\nACTION_END";

    private TestOutputCapture              output;
    private Configuration.AiConfig         ai;
    private Configuration.CompactionConfig compaction;
    private int                            originalContextTokens;
    private boolean                        originalEnabled;

    @Before
    public void setUp() {
        System.setProperty("cadet.interactive", "false");
        TokenCalibration.clear();
        ai                    = ConfigManager.getInstance().getConfig().getAi();
        compaction            = ConfigManager.getInstance().getConfig().getCompaction();
        originalContextTokens = ai.getContextTokens();
        originalEnabled       = compaction.isEnabled();
        compaction.setEnabled(true);
        output = new TestOutputCapture();
        output.startCapture();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        ai.setContextTokens(originalContextTokens);
        compaction.setEnabled(originalEnabled);
        TokenCalibration.clear();
        System.clearProperty("cadet.interactive");
    }

    /** A run that has listed a directory {@code actions} times, each listing {@code chars} long. */
    private static AgentState runWith(int actions, int chars) {
        AgentState state = new AgentState("tidy the notes", 0, System::currentTimeMillis);
        for (int i = 0; i < actions; i++) {
            state.addExecutedAction(AgentActions.from(LIST_FILES, ignored -> { }), 0,
                                    "listing " + i + "\n" + "notes.md\n".repeat(chars / 9));
        }
        return state;
    }

    private static String userPrompt(AgentState state) {
        return AgentPrompts.user(state, new HashMap<>(), Mockito.mock(ContextEngine.class));
    }

    @Test
    public void theOldestOutputsAreLeftOutOnceThePromptPassesTheTrigger() {
        ai.setContextTokens(20_000);
        AgentState state = runWith(12, 8_000);

        String note = AgentHistoryFit.fit(state, "system", userPrompt(state), false);

        assertThat(note).isNotNull().contains("left out");
        assertThat(state.outputsLeftOut()).isPositive().isLessThan(12);
        String prompt = userPrompt(state);
        assertThat(prompt).doesNotContain("listing 0").contains("listing 11")
                          .contains(AgentPrompts.LEFT_OUT);
        int budget = TranscriptCompactor.effectiveBudgetTokens();
        assertThat(com.eonmux.cadetcoder.ai.PromptSize.tokens(prompt))
                .as("back under the target")
                .isLessThanOrEqualTo((int) (compaction.getTarget() * budget));
    }

    @Test
    public void aPromptUnderTheTriggerIsLeftAlone() {
        ai.setContextTokens(1_000_000);
        AgentState state = runWith(12, 8_000);

        assertThat(AgentHistoryFit.fit(state, "system", userPrompt(state), false)).isNull();
        assertThat(state.outputsLeftOut()).isZero();
    }

    /** The model needs the result of what it just did to decide what to do next. */
    @Test
    public void theLatestOutputIsAlwaysKept() {
        ai.setContextTokens(4_096);
        AgentState state = runWith(3, 40_000);

        AgentHistoryFit.fit(state, "system", userPrompt(state), false);

        assertThat(state.outputsLeftOut()).isEqualTo(2);
        assertThat(userPrompt(state)).contains("listing 2");
    }

    @Test
    public void aRefusalLeavesOutputsOutWhateverTheEstimateSays() {
        ai.setContextTokens(1_000_000);
        AgentState state = runWith(4, 8_000);
        TokenCalibration.observeAtLeast(com.eonmux.cadetcoder.ai.PromptSize.configuredModel(),
                                        userPrompt(state).length(), 1_000_001);

        assertThat(AgentHistoryFit.fit(state, "system", userPrompt(state), true))
                .isNotNull();
        assertThat(state.outputsLeftOut()).isEqualTo(3);
    }

    @Test
    public void nothingIsLeftOutWhenCompactionIsTurnedOff() {
        ai.setContextTokens(20_000);
        compaction.setEnabled(false);
        AgentState state = runWith(12, 8_000);

        assertThat(AgentHistoryFit.fit(state, "system", userPrompt(state), false)).isNull();
        assertThat(state.outputsLeftOut()).isZero();
    }

    @Test
    public void aRunRefusedForLengthLeavesOutputsOutAndAsksAgain() {
        ai.setContextTokens(1_000_000);
        AtomicInteger request = new AtomicInteger();
        try (StubbedProvider provider = StubbedProvider.answeringWith(asked -> {
            int number = request.incrementAndGet();
            if (number == 3) {
                throw LLMErrorMapper.fromStatus("local", "qwen", null, 400,
                        "{\"error\":{\"message\":\"This model's maximum context length is 1000000"
                        + " tokens. However, your prompt contains at least 1000001 input"
                        + " tokens.\"}}", null);
            }
            return number < 5 ? LIST_FILES : "TASK COMPLETE: done";
        })) {
            int exit = new IterativeExecutor().execute(new AgentCommand(),
                                                       new String[] {"--classic", "-y", "tidy"});

            List<String> prompts = provider.asked().stream()
                                           .map(PromptData::getUserPrompt).toList();
            assertThat(prompts.get(3)).as("asked again with the oldest output left out")
                                      .contains(AgentPrompts.LEFT_OUT);
            assertThat(exit).isZero();
        }
        assertThat(output.getAllOutput())
                .contains("The prompt was too long for the model. Context compacted:");
    }
}
