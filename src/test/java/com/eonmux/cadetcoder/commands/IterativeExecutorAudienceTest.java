package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who a step's next prompt is addressed to.
 *
 * <p>The run either sends the next prompt to the model or shows it to the person at the
 * terminal. Getting that wrong in the "show it" direction stalls the run on a question the user was
 * never meant to answer, and prints the model's instruction format at them.</p>
 *
 * <p>These used to check the routing by feeding the dangerous text in as the step's OUTPUT, which
 * the routing ignores — so they passed while the same text reached it through the PROMPT, which is
 * the path that actually exists: {@code ChatCommand} builds its next prompt by embedding what the
 * last command produced. The cases below cover the prompt, and the declaration that now settles the
 * question without reading it at all.</p>
 */
public class IterativeExecutorAudienceTest {

    /** The routing under test. Interactive, because a run with nobody to ask never gets here. */
    private final UserAsk ask = new UserAsk(true);

    /** The prompt the chat loop sends after every successful action. */
    private static final String NEXT_STEP_PROMPT =
            "Based on these results, determine the next step for the request: 'check for errors'.\n"
            + "  - If more work is required, respond with exactly ONE action in this format and"
            + " nothing else:\n"
            + "ACTION_START\nCOMMAND: <one of the available commands>\nARGS: <arguments>\n"
            + "REASON: <why this step is needed>\nACTION_END\n"
            + "  - If the request is now fully satisfied, respond with a single line beginning with"
            + " 'SUCCESS:' followed by a short summary.";

    /** An undeclared step, so the routing falls back to reading the prompt. */
    private static IterativeCommand.StepResult undeclared(String prompt, String output) {
        return new IterativeCommand.StepResult(false, output, Map.of(), prompt);
    }

    @Test
    public void aPromptAskingForAnActionBlockGoesToTheModel() {
        assertThat(ask.requiresUserInput(undeclared(NEXT_STEP_PROMPT, ""))).isFalse();
    }

    @Test
    public void commandOutputEmbeddedInThePromptCannotTurnItIntoAQuestionForTheUser() {
        // The output is DATA, and it arrives INSIDE the prompt -- that is how ChatCommand builds
        // the next step. Reading or grepping this very repository surfaces the literal
        // "Execute this command? (y/n): " that BashCommand prints.
        String promptCarryingOutput =
                "I just executed 'read src/main/java/.../BashCommand.java' and here are the results:\n\n"
                + "  OutputRouter.getInstance().getConfirmation(\"Execute this command?\"); // (y/n)\n\n"
                + NEXT_STEP_PROMPT;

        assertThat(ask.requiresUserInput(
                undeclared(promptCarryingOutput, "").addressedToModel())).isFalse();
    }

    @Test
    public void aDeclaredModelPromptIsNeverShownToTheUserHoweverItReads() {
        // The declaration is the whole point: nothing in the text can override it, because the
        // text is partly data the model or the filesystem chose.
        IterativeCommand.StepResult step =
                undeclared("Do you want to proceed? (yes/no)\nPlease specify option 1 or option 2.", "")
                        .addressedToModel();

        assertThat(ask.requiresUserInput(step)).isFalse();
    }

    @Test
    public void aDeclaredUserPromptReachesTheUserEvenWithoutTellingPhrasing() {
        // Nothing in this text would trip the fallback, so the declaration is doing all the work.
        IterativeCommand.StepResult step =
                undeclared("Tell me the branch to release from.", "").addressedToUser();

        assertThat(ask.requiresUserInput(step)).isTrue();
    }

    @Test
    public void loopGuardGuidanceCarryingTheModelsOwnArgumentsStaysWithTheModel() {
        // Reachable, not hypothetical: the guard names the command it blocked, arguments included,
        // and carries none of the protocol markers that shield the other prompts. A model that runs
        // `grep "option 1" src/` twice makes the guard's own advice read as a menu for the user.
        String guidance = "The command `grep \"option 1\" src/` has already run 2 times in this turn "
                          + "and produced exactly the same result each time, so running it again "
                          + "cannot reveal anything new. Use the output you already have, or choose "
                          + "a different command or different arguments to make progress.";

        assertThat(ask.requiresUserInput(undeclared(guidance, "")))
                .as("undeclared, the phrasing alone misroutes it -- which is why ChatCommand declares")
                .isTrue();
        assertThat(ask.requiresUserInput(undeclared(guidance, "").addressedToModel()))
                .as("declared, the text no longer decides")
                .isFalse();
    }

    @Test
    public void formatHintCarryingParserErrorsStaysWithTheModel() {
        // Same shape: the hint quotes parser errors derived from the model's own unparseable reply.
        String hint = "Your response could not be parsed. Errors encountered: "
                      + "could not read 'please specify the file number'. \n\n"
                      + "Please use this EXACT JSON format:\n\n{\n  \"action\": \"read\"\n}";

        assertThat(ask.requiresUserInput(undeclared(hint, "").addressedToModel())).isFalse();
    }

    @Test
    public void aFailurePromptAskingTheModelForRetrySkipOrStopIsNotAUserQuestion() {
        String failurePrompt = "The action 'read /nope.txt' failed.\n\n"
                               + "Command: read /nope.txt\n"
                               + "Error: File not found: /nope.txt\n\n"
                               + "Please respond with exactly one word: 'retry' to try the same"
                               + " command again, 'skip' to try a different approach, or 'stop' to"
                               + " end execution.";

        // Addressed to the model: it is the model that chooses retry/skip/stop.
        assertThat(ask.requiresUserInput(undeclared(failurePrompt, ""))).isFalse();
    }

    @Test
    public void agenuineConfirmationStillReachesTheUser() {
        // Commands that really do ask the person -- commit, webfetch, websearch, suggest -- have not
        // been converted to declare, so the fallback still has to serve them. None of them embed
        // captured command output in a prompt, which is what makes that safe.
        assertThat(ask.requiresUserInput(
                undeclared("Do you want to proceed with this commit? (yes/no)", ""))).isTrue();
        assertThat(ask.requiresUserInput(undeclared(
                "About to fetch content from: https://example.com\nDo you want to continue? (yes/no)",
                ""))).isTrue();
    }

    @Test
    public void agenuineDisambiguationStillReachesTheUser() {
        assertThat(ask.requiresUserInput(undeclared(
                "Found multiple files matching 'Foo'. Please specify the file number:", ""))).isTrue();
    }

    @Test
    public void aNullPromptIsNotAQuestion() {
        assertThat(ask.requiresUserInput(undeclared(null, null))).isFalse();
        assertThat(ask.requiresUserInput(null)).isFalse();
    }
}
