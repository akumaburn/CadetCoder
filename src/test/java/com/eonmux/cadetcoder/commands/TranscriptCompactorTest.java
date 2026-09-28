package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Folding a long run's transcript so it keeps fitting the model's input window. */
public class TranscriptCompactorTest {

    private Configuration.CompactionConfig settings;
    private int     originalContextTokens;
    private boolean originalEnabled;
    private double  originalTrigger;
    private double  originalTarget;
    private int     originalKeepHead;
    private int     originalKeepTail;

    @Before
    public void setUp() {
        settings              = ConfigManager.getInstance().getConfig().getCompaction();
        originalEnabled       = settings.isEnabled();
        originalTrigger       = settings.getTrigger();
        originalTarget        = settings.getTarget();
        originalKeepHead      = settings.getKeepHeadEntries();
        originalKeepTail      = settings.getKeepTailEntries();
        originalContextTokens = ConfigManager.getInstance().getConfig().getAi().getContextTokens();
        // A small, explicit window so the thresholds are reached by a transcript a test can write.
        ConfigManager.getInstance().getConfig().getAi().setContextTokens(4096);
        settings.setEnabled(true);
    }

    /** The compaction settings are the process's, so every one a case writes is put back. */
    @After
    public void tearDown() {
        settings.setEnabled(originalEnabled);
        settings.setTrigger(originalTrigger);
        settings.setTarget(originalTarget);
        settings.setKeepHeadEntries(originalKeepHead);
        settings.setKeepTailEntries(originalKeepTail);
        ConfigManager.getInstance().getConfig().getAi().setContextTokens(originalContextTokens);
    }

    /** How much of the budget the system half takes in the case that exercises it. */
    private static final double SYSTEM_SHARE = 0.7;

    /** Text of roughly the given estimated size, for sizing a prompt against the real budget. */
    private static String textOfAbout(int tokens) {
        return "w ".repeat(Math.max(1, com.eonmux.cadetcoder.ai.metrics.TokenEstimator.charsFor(tokens) / 2));
    }

    /** A transcript whose entries are large enough to blow the budget. */
    private static List<String> longTranscript(int entries) {
        List<String> t = new ArrayList<>();
        for (int i = 0; i < entries; i++) {
            t.add("System: entry " + i + " " + "x".repeat(2_000));
        }
        return t;
    }

    @Test
    public void aShortRunIsLeftAlone() {
        List<String> transcript = new ArrayList<>(List.of("Next prompt: do the thing"));
        List<String> before     = new ArrayList<>(transcript);

        assertThat(new TranscriptCompactor().compactIfNeeded(transcript, "system", true)).isNull();
        assertThat(transcript).isEqualTo(before);
    }

    @Test
    public void anOverlongRunIsFoldedAndSaysSo() {
        List<String> transcript = longTranscript(40);
        int          before     = TranscriptCompactor.estimateTranscript(transcript);

        String result = new TranscriptCompactor().compactIfNeeded(transcript, "system", true);

        assertThat(result).isNotNull().contains("folded").contains("reclaimed");
        assertThat(TranscriptCompactor.estimateTranscript(transcript)).isLessThan(before);
    }

    @Test
    public void theHeadIsKeptVerbatimBecauseCachingMatchesALeadingPrefix() {
        List<String> transcript = longTranscript(40);
        String       first      = transcript.get(0);
        String       second     = transcript.get(1);

        new TranscriptCompactor().compactIfNeeded(transcript, "system", true);

        // Folding everything would leave only the system prompt cache-eligible; keeping a head keeps
        // system + head matching, which is the cheapest cache decision available.
        assertThat(transcript.get(0)).isEqualTo(first);
        assertThat(transcript.get(1)).isEqualTo(second);
    }

    @Test
    public void theMostRecentStepsSurvive() {
        List<String> transcript = longTranscript(40);
        String       last       = transcript.get(transcript.size() - 1);

        new TranscriptCompactor().compactIfNeeded(transcript, "system", true);

        assertThat(transcript.get(transcript.size() - 1)).isEqualTo(last);
    }

    @Test
    public void whatWasDroppedIsStatedInTheTranscriptItself() {
        List<String> transcript = longTranscript(40);

        new TranscriptCompactor().compactIfNeeded(transcript, "system", true);

        assertThat(String.join("\n", transcript))
                .contains("omitted to stay within the model's context window");
    }

    @Test
    public void aRunGoingInCirclesIsNotCompacted() {
        List<String> transcript = longTranscript(40);
        List<String> before     = new ArrayList<>(transcript);

        // Rewriting history under a model that is already repeating itself removes the context that
        // would let it notice it is repeating.
        assertThat(new TranscriptCompactor().compactIfNeeded(transcript, "system", false)).isNull();
        assertThat(transcript).isEqualTo(before);
    }

    @Test
    public void compactionCanBeTurnedOff() {
        settings.setEnabled(false);
        List<String> transcript = longTranscript(40);

        assertThat(new TranscriptCompactor().compactIfNeeded(transcript, "system", true)).isNull();
    }

    @Test
    public void aTranscriptWithNoMiddleToFoldLatchesOffInsteadOfRetryingForever() {
        settings.setKeepHeadEntries(6);
        settings.setKeepTailEntries(9);
        // Fifteen entries is exactly head + tail: there is no middle, so folding cannot reclaim
        // anything and repeating it every turn would rewrite the prefix for no gain.
        List<String> transcript = longTranscript(15);

        TranscriptCompactor compactor = new TranscriptCompactor();
        assertThat(compactor.compactIfNeeded(transcript, "system", true)).isNull();
        assertThat(compactor.isLatchedOff()).isTrue();
    }

    /**
     * The budget is for the whole prompt, so the fold has to be too.
     *
     * <p><b>The defect</b>: the trigger was measured against the system prompt plus the transcript
     * and the fold then worked to a limit computed from the transcript alone. The two disagree by
     * exactly the size of the system prompt, so a fold could reach its target, report the tokens it
     * had reclaimed, and hand back a prompt still over the window -- and nothing measured the total
     * again afterwards. The first anybody heard of it was the provider's 400, which is terminal:
     * the retry policy will not repeat it, so the run ends and the task is lost.</p>
     */
    @Test
    public void afoldLeavesRoomForTheSystemPromptItWasMeasuredWith() {
        int    budget = TranscriptCompactor.effectiveBudgetTokens();
        String system = textOfAbout((int) (budget * SYSTEM_SHARE));

        // A tail whose entries come to about the old limit -- the target measured against the
        // transcript alone. Reaching it satisfied the fold while the system prompt above still put
        // the whole prompt well over the window.
        List<String> transcript = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            transcript.add("System: entry " + i + " " + "x".repeat(200));
        }
        int perEntry = TranscriptCompactor.estimateTranscript(transcript) / transcript.size();
        settings.setKeepHeadEntries(0);
        settings.setKeepTailEntries(Math.max(2, (int) (settings.getTarget() * budget) / perEntry));

        new TranscriptCompactor().compactIfNeeded(transcript, system, true);

        int prompt = com.eonmux.cadetcoder.ai.metrics.TokenEstimator.estimate(system)
                     + TranscriptCompactor.estimateTranscript(transcript);
        assertThat(prompt)
                .as("a compaction that reports success has to have produced a prompt that fits")
                .isLessThanOrEqualTo(budget);
    }

    /**
     * A saving too small to be worth repeating is still the difference between a request and a 400.
     *
     * <p><b>The defect</b>: a fold reclaiming less than {@code MINIMUM_RECLAIM} was thrown away AND
     * compaction was latched off for the rest of the run, so the prompt went out exactly as it was
     * -- over the window, with nothing said and nothing left that would ever try again.</p>
     */
    @Test
    public void afoldThatBarelyHelpsIsStillAppliedWhenThePromptDoesNotOtherwiseFit() {
        settings.setKeepHeadEntries(6);
        settings.setKeepTailEntries(9);
        // A head that is most of the bulk: folding the rest away cannot reclaim a fifth of it, and
        // the prompt does not fit either way.
        List<String> transcript = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            transcript.add("System: head " + i + " " + "x".repeat(20_000));
        }
        for (int i = 0; i < 10; i++) {
            transcript.add("System: tail " + i + " " + "x".repeat(2_000));
        }
        int before = TranscriptCompactor.estimateTranscript(transcript);

        TranscriptCompactor compactor = new TranscriptCompactor();
        String              summary   = compactor.compactIfNeeded(transcript, "system", true);

        assertThat(TranscriptCompactor.estimateTranscript(transcript))
                .as("what could be reclaimed was reclaimed rather than discarded")
                .isLessThan(before);
        assertThat(summary)
                .as("and the prompt that still does not fit is described as one that does not fit")
                .isNotNull()
                .contains("tokens of prompt against a limit of");
        assertThat(compactor.isLatchedOff())
                .as("there is nothing left to fold, so the run stops trying")
                .isTrue();
    }

    /**
     * When it cannot be made to fit, that is said in words rather than left to the provider.
     *
     * <p>A prompt over the window is a terminal 400 that ends the run. Answering it with
     * {@code null} -- which the caller reads as "nothing to report" -- meant the person watching
     * learned about it from the failure.</p>
     */
    @Test
    public void apromptThatCannotBeMadeToFitSaysSo() {
        settings.setKeepHeadEntries(6);
        settings.setKeepTailEntries(9);
        List<String> transcript = longTranscript(15);

        com.eonmux.cadetcoder.test.TestOutputCapture console =
                new com.eonmux.cadetcoder.test.TestOutputCapture();
        String said;
        try {
            new TranscriptCompactor().compactIfNeeded(transcript, "system", true);
        } finally {
            said = console.getAllOutput();
            console.restore();
        }

        assertThat(said)
                .as("the prompt and the limit are both named, so the reader can tell which is wrong")
                .contains("Cannot make this request fit")
                .contains("tokens of prompt against a limit of")
                .contains("input window")
                .contains("ai.contextTokens");
    }

    /** Being over the trigger is ordinary; only being over the window is worth interrupting for. */
    @Test
    public void atranscriptThatStillFitsIsNotAnnouncedAsAFailure() {
        settings.setTrigger(0.05);
        settings.setKeepHeadEntries(1);
        settings.setKeepTailEntries(1);
        List<String> transcript = new ArrayList<>(List.of("System: one", "System: two"));

        com.eonmux.cadetcoder.test.TestOutputCapture console =
                new com.eonmux.cadetcoder.test.TestOutputCapture();
        String said;
        try {
            new TranscriptCompactor().compactIfNeeded(transcript, "system", true);
        } finally {
            said = console.getAllOutput();
            console.restore();
        }

        assertThat(said).doesNotContain("Cannot make this request fit");
    }

    /**
     * The measurement names both numbers, because one of them is the answer.
     *
     * <p>"About 4,068 tokens over what the model will take" reads the same whether the session has
     * gone on too long or the window was never known. The pair says which: over a limit of 900,000
     * it is a long session, over a limit of 3,584 it is a limit that is wrong.</p>
     */
    @Test
    public void thepromptAndTheLimitAreBothNamed() {
        assertThat(TranscriptCompactor.measured(7_652, 3_584))
                .isEqualTo("about 7,652 tokens of prompt against a limit of 3,584");
    }

    @Test
    public void thelimitSaysWhereItCameFrom() {
        String said = TranscriptCompactor.whereTheLimitComesFrom(
                new com.eonmux.cadetcoder.ai.ContextWindow.Window(
                        8_192, "assumed: no window is published for commandcode/deepseek-v4"));

        assertThat(said)
                .contains("8,192-token input window")
                .contains("assumed: no window is published for commandcode/deepseek-v4")
                .contains("held back for the reply");
    }

    /** A window nobody published is a setting away from being right, and that is said first. */
    @Test
    public void whatToDoDependsOnWhereTheLimitCameFrom() {
        assertThat(TranscriptCompactor.whatToDoAboutIt(
                new com.eonmux.cadetcoder.ai.ContextWindow.Window(8_192, "assumed: no window is published for x/y")))
                .startsWith("Run '").contains("models context <tokens>");

        assertThat(TranscriptCompactor.whatToDoAboutIt(
                new com.eonmux.cadetcoder.ai.ContextWindow.Window(1_000_000, "published for deepseek/deepseek-v4-flash")))
                .startsWith("Start a new session");
    }

    @Test
    public void theBudgetLeavesRoomForTheResponse() {
        // The window is what the model accepts in total; the generation limit comes out of it.
        int budget = TranscriptCompactor.effectiveBudgetTokens();

        assertThat(budget).isLessThan(com.eonmux.cadetcoder.ai.ContextWindow.tokens());
        assertThat(budget).isGreaterThanOrEqualTo(TranscriptCompactor.MINIMUM_BUDGET_TOKENS);
    }

    @Test
    public void roomIsHeldBackEvenWhenNoCeilingWasAsked() {
        // ai.maxTokens is zero by default, meaning no ceiling is sent. Reading that number here
        // would reserve nothing and let the prompt grow into the whole window, leaving the model
        // no room to answer in.
        int original = ConfigManager.getInstance().getConfig().getAi().getMaxTokens();
        try {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(0);

            assertThat(TranscriptCompactor.replyReserveTokens(4096)).isPositive();
        } finally {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(original);
        }
    }

    @Test
    public void theReserveNeverClaimsMoreThanHalfTheWindow() {
        // A model whose published output limit is its whole context would otherwise leave the
        // prompt nothing but the floor.
        int original = ConfigManager.getInstance().getConfig().getAi().getMaxTokens();
        try {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(1_000_000);

            assertThat(TranscriptCompactor.replyReserveTokens(4096)).isEqualTo(2048);
        } finally {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(original);
        }
    }

    @Test
    public void aCeilingSomebodyAskedForIsTheReserve() {
        int original = ConfigManager.getInstance().getConfig().getAi().getMaxTokens();
        try {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(512);

            assertThat(TranscriptCompactor.replyReserveTokens(100_000)).isEqualTo(512);
        } finally {
            ConfigManager.getInstance().getConfig().getAi().setMaxTokens(original);
        }
    }
}
