package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.ai.parsing.ResponseParsingEngine;
import com.eonmux.cadetcoder.commands.IterativeCommand.StepResult;

/**
 * What the run does with an action the safety screen refused: tell the model why, and let it choose
 * another way.
 *
 * <h2>Why this is not a format correction</h2>
 *
 * <p>A refused action was read without trouble. The screen refused it for what it would do -- a
 * shell line naming {@code rm}, a path outside the project -- and the same block sent again is
 * refused again. Answered with the format exemplar, as it used to be, the model was told the one
 * thing about its reply that was right was wrong. It sent the same block back, twice, and the run
 * ended on "AI failed to provide proper ACTION format" above a correct ACTION block. Told the
 * reason, it can do the same thing another way.</p>
 *
 * <h2>Why refusals have a ceiling of their own</h2>
 *
 * <p>A model that proposes refused actions over and over is stuck, and must not go on forever. The
 * ceiling is on refusals in a row: an action that runs sets the count back to zero, because a model
 * that found another way is not stuck.</p>
 */
final class RefusedAction {

    /** How many refusals in a row the run takes before it stops. */
    static final int MAX_IN_A_ROW = 3;

    private RefusedAction() {
    }

    /**
     * Why the screen refused the reply's action.
     *
     * @param parsed what the parsing engine made of the reply; may be {@code null}
     * @return the reason, or {@code null} when nothing was refused
     */
    static String reason(ParsedResponse parsed) {
        // Only actions refused one by one. A reply refused as a whole has no action in it, and a
        // final answer that happens to match the injection screen must not be answered with "your
        // action was refused" -- it would be refused again every time it was given.
        if (!ResponseParsingEngine.refusedActions(parsed)) {
            return null;
        }
        String reason = String.join("; ", parsed.getErrors()).trim();
        return reason.isEmpty() ? "the safety screen gave no reason" : reason;
    }

    /**
     * Tells the model its action was refused and why, or ends the run once it has been told enough.
     *
     * <p>The format count is cleared: the reply followed the format, and a correction still counted
     * against it would end the run on the format of a block that was well formed.</p>
     *
     * @param context the run's context
     * @param reason  why the screen refused it, from {@link #reason(ParsedResponse)}
     * @return the step result to return from the step that read the reply
     */
    static StepResult tellTheModel(ChatContext context, String reason) {
        int inARow = context.getRefusedActionCount() + 1;
        context.setRefusedActionCount(inARow);
        context.setFormatRetryCount(0);
        if (inARow > MAX_IN_A_ROW) {
            return StepResult.failure(
                    "Stopped: the safety screen refused " + inARow + " actions in a row, and the "
                    + "model found no other way to go on. The last one was refused because: "
                    + reason, context.toMap());
        }
        context.setStep("next_step");
        return new StepResult(false, "Action refused: " + reason, context.toMap(),
                "Nothing was run: the safety screen refused that action because: " + reason
                + ". The same action will be refused again. Do what you meant another way, with a "
                + "command the screen allows, or explain why it cannot be done.")
                .addressedToModel();
    }

    /**
     * Records that an action is about to run, which ends any run of refusals.
     *
     * @param context the run's context
     */
    static void noteAnActionRan(ChatContext context) {
        context.setRefusedActionCount(0);
    }
}
