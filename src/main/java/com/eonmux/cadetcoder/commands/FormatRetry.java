package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;

/**
 * What happens when the model's reply cannot be read as an action: how it is corrected, how often,
 * and what the run says when it stops asking.
 *
 * <h2>Why this is one class and not three branches</h2>
 *
 * <p>Three steps of the chat machine can hit an unreadable reply -- the first analysis, a correction
 * that was itself unreadable, and a follow-up turn -- and each used to carry its own copy of the
 * ceiling, its own corrective prompt and its own give-up message. The copies had already drifted:
 * the same situation was described to the model in two different sentences, and the ceiling was the
 * bare number {@code 2} written out three times, so raising it in one place would have raised it
 * nowhere. The rule belongs in one place because it is one rule.</p>
 *
 * <h2>Why the counter is bumped here</h2>
 *
 * <p>Asking again and recording that it was asked are the same act. Keeping them apart is what lets
 * a step ask a fourth time by forgetting the increment, which is the failure this ceiling exists to
 * prevent.</p>
 */
final class FormatRetry {

    /** How many corrections the model is sent before the run gives up on it. */
    static final int MAX_ATTEMPTS = 2;

    /** The step a corrected reply comes back to. */
    private static final String STEP = "format_retry";

    /**
     * What a correction says before anything else.
     *
     * <h2>Why the model is told this and not only what the format is</h2>
     *
     * <p>A reply that could not be read as an action is a reply whose action did not run. The model
     * that wrote it does not know that: it wrote {@code job start ...}, was answered with a
     * complaint about formatting, and went on to wait for the job -- which did not exist, because
     * nothing had started it. It then reasoned about what that absence meant for the rest of its
     * work. The same misreading, one run later, produced three commits the model believed it had
     * made and then tried to undo.</p>
     */
    private static final String NOTHING_RAN =
            "Nothing was run for it: a reply that cannot be read as an action carries out no "
            + "command, so the state of the project and of any background job is exactly what it "
            + "was before you sent it.";

    /** The block the model is being asked for, shown filled in rather than as a template. */
    private static final String EXEMPLAR =
            "ACTION_START\n"
            + "COMMAND: read\n"
            + "ARGS: src/main/java/com/eonmux/cadetcoder/shell/ScriptExecutor.java\n"
            + "REASON: Read the ScriptExecutor class to understand its functionality\n"
            + "ACTION_END";

    private FormatRetry() {
    }

    /**
     * Whether the model has already been corrected as often as it is going to be.
     *
     * @param context the run's context, which carries the count
     * @return true when the next unreadable reply should end the run
     */
    static boolean exhausted(ChatContext context) {
        return context.getFormatRetryCount() >= MAX_ATTEMPTS;
    }

    /**
     * Sends one more correction and counts it.
     *
     * @param context    the run's context
     * @param label      what this attempt is called in the transcript, without the attempt number
     * @param correction what to tell the model
     * @return the step result to return from the step that could not read the reply
     */
    static StepResult askAgain(ChatContext context, String label, String correction) {
        int attempt = context.getFormatRetryCount() + 1;
        context.setFormatRetryCount(attempt);
        context.setStep(STEP);
        return new StepResult(false, label + " " + attempt, context.toMap(), correction)
                .addressedToModel();
    }

    /**
     * The model kept ignoring the format. This is what the run fails with.
     *
     * @param context     the run's context, which carries how many times it was asked
     * @param llmResponse the last reply that could not be read
     * @return the failing step result
     */
    static StepResult giveUp(ChatContext context, String llmResponse) {
        return StepResult.failure(
                "ERROR: AI failed to provide proper ACTION format after "
                + context.getFormatRetryCount() + " attempts.\n"
                + "AI Response was: " + llmResponse, context.toMap());
    }

    /**
     * The same end, reached from the analysis step, which knows what the parser tried and can say so.
     *
     * @param context     the run's context
     * @param parsed      what the parsing engine made of the reply
     * @param llmResponse the reply itself
     * @return the failing step result
     */
    static StepResult giveUp(ChatContext context, ParsedResponse parsed, String llmResponse) {
        return StepResult.failure(String.format(
                "ERROR: AI failed to provide parseable response after %d attempts.\n"
                + "Parsing Result: %s\n"
                + "Strategy Used: %s\n"
                + "Confidence: %.2f\n"
                + "Errors: %s\n"
                + "AI Response was: %s",
                context.getFormatRetryCount(),
                parsed.getResult(),
                parsed.getStrategyUsed(),
                parsed.getConfidence(),
                String.join(", ", parsed.getErrors()),
                llmResponse), context.toMap());
    }

    /**
     * A correction built around the exemplar, under a lead sentence and above a closing instruction.
     *
     * @param lead    how this reply was wrong
     * @param closing what to do about it
     * @return what to send back
     */
    static String correction(String lead, String closing) {
        return lead + " " + NOTHING_RAN + " You MUST use this EXACT format:\n\n"
               + EXEMPLAR + "\n\n" + closing;
    }
}
