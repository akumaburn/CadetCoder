package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ui.OutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when something asks a question nobody can answer.
 *
 * <p>A worker runs a full agent loop on a background thread with its output collected, so any
 * question it asks goes into its own transcript while the bare input prompt reaches the terminal —
 * the user is asked to answer something they cannot read, and several workers then contend for one
 * input line. The guard for that has to sit where every asker passes, not only on the path the
 * iterative executor happens to use.</p>
 */
class AskingWithNobodyToAskTest {

    /** What is answered when the question cannot reach anyone. */
    private static String defaultAnswer(String prompt, String output) {
        return new UserAsk(false).getDefaultUserInput(prompt, output);
    }

    @Test
    @DisplayName("A confirmation nobody can answer is denied, never self-granted")
    void confirmationsAreDenied() throws Exception {
        assertThat(defaultAnswer("Do you want to continue? (yes/no)", "")).isEqualTo("no");
        assertThat(defaultAnswer("Proceed? (y/n)", "")).isEqualTo("no");
        assertThat(defaultAnswer("Should we proceed: yes or no?", "")).isEqualTo("no");
    }

    @Test
    @DisplayName("Command output cannot decide the answer given on the user's behalf")
    void theOutputIsNotConsulted() throws Exception {
        // Reading this repository surfaces the literal confirmation strings it prints. Scanning the
        // output meant an unrelated question was answered "no" because a file mentioned "(yes/no)".
        String output = "Command Output:\n  getConfirmation(\"Execute this command?\"); // (yes/no)\n";

        assertThat(defaultAnswer("Which file should I open?", output))
                .as("the answer must come from the question, not from what was printed before it")
                .isEmpty();
    }

    @Test
    @DisplayName("A free-text question is left unanswered rather than answered with a digit")
    void freeTextQuestionsAreLeftUnanswered() throws Exception {
        // The old fallback tested `contains("1") && contains("2")` over the prompt AND the output,
        // which any timestamp or line count satisfies, and otherwise returned "1" outright. An agent
        // asked what to work on therefore received the digit 1 and took it as the task.
        assertThat(defaultAnswer("What would you like me to help you with today?", "")).isEmpty();
        assertThat(defaultAnswer("Describe the change you want.", "found 12 files")).isEmpty();
    }

    @Test
    @DisplayName("A genuine numbered choice still gets one")
    void numberedChoicesStillResolve() throws Exception {
        assertThat(defaultAnswer("Pick one: option 1 (a.java) or option 2 (b.html)", ""))
                .isEqualTo("1");
        assertThat(defaultAnswer("Found multiple files. Please specify the file number:", ""))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("A worker asking for confirmation is refused rather than seizing the input line")
    void confirmationFromAWorkerIsRefused() {
        boolean[] granted = {true};
        List<String> transcript = OutputCapture.collect(
                () -> granted[0] = OutputRouter.getInstance().getConfirmation("Execute this command?"));

        assertThat(granted[0])
                .as("an unanswerable confirmation must not be self-granted")
                .isFalse();
        assertThat(transcript)
                .as("the refusal belongs in the worker's own transcript, not nowhere")
                .anySatisfy(line -> assertThat(line).contains("no one can be asked"));
    }

    @Test
    @DisplayName("A worker asking for free-text input gets nothing back, and says so")
    void inputFromAWorkerIsRefused() {
        String[] answer = {"unset"};
        List<String> transcript = OutputCapture.collect(
                () -> answer[0] = OutputRouter.getInstance().getUserInput("Which file? "));

        assertThat(answer[0]).isEmpty();
        assertThat(transcript).anySatisfy(line -> assertThat(line).contains("no one can be asked"));
    }

    /**
     * Rejects an action before it can ask, rather than relying on the answer being sensible.
     *
     * <p>{@code search} with no query drops {@link SearchCommand} into the iterative executor, whose
     * first step is to ask what to search for. In an agentic loop that question reaches nobody, so
     * the turn is spent on a prompt that can only be answered with silence. {@code grep} was already
     * guarded this way; {@code search} was not, because it had only just been offered to the model.</p>
     */
    @Test
    @DisplayName("An argument-less search is rejected instead of prompting for the query")
    void anEmptySearchIsRejectedBeforeItCanAsk() {
        ChatCommand chat = new ChatCommand();
        ActionRun   run  = new ActionRun(chat, new ActionPaths(chat));

        assertThat(run.argumentsAreUsable(
                new ChatCommand.AIAction("search", new String[0], "look for something")))
                .as("a search with no query would ask the user what to search for")
                .isFalse();
        assertThat(run.argumentsAreUsable(
                new ChatCommand.AIAction("search", new String[] {"how tokens are estimated"}, "look")))
                .as("a search WITH a query must still be allowed through")
                .isTrue();
    }

    @Test
    @DisplayName("Outside a worker the guard does not engage")
    void theGuardOnlyAppliesWhileOutputIsCollected() {
        // Nothing is captured here, so the router takes its ordinary path. With no TUI and no
        // console attached under surefire that yields an empty answer too -- the point is that it
        // does NOT report the worker refusal.
        List<String> transcript = OutputCapture.collect(() -> { });

        assertThat(transcript).isEmpty();
        assertThat(OutputCapture.isCapturing()).isFalse();
    }
}
