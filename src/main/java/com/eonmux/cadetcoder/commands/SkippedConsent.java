package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.InteractivePrompts;
import com.eonmux.cadetcoder.ui.OutputCapture;

/**
 * The confirmation question a writing command skips when it has nobody to ask.
 *
 * <h2>Why the skip is reported rather than obeyed</h2>
 *
 * <p>{@code edit} and {@code multiedit} work out a change, show it, and ask whether to apply it.
 * Neither can ask when prompts are off. Both used to apply the change anyway and say nothing, so
 * files were written under a setting whose purpose is to hold a write until somebody agrees to
 * it.</p>
 *
 * <p>Refusing instead would make both commands unusable for every agent run, because
 * {@link InteractivePrompts#asModelDrivenWork} turns prompts off for the duration of a step: a
 * question asked inside a captured command never reaches a screen and would block the run. So the
 * change goes ahead and the skipped question goes on the record.</p>
 *
 * <h2>Why one class rather than a copy in each command</h2>
 *
 * <p>Because it is one rule. It was written into {@code edit} alone, and {@code multiedit} -- the
 * same gate, the same three circumstances -- stayed silent. Two copies of one sentence drift, and
 * the copy that drifts is the one nobody reads.</p>
 */
final class SkippedConsent {

    private SkippedConsent() {
    }

    /**
     * Says that a change is being applied with nobody asked, and why.
     *
     * <p>Printed as a warning, so it is not suppressed at the quieter verbosities, and collected
     * with the rest of a captured command's output, so an agent reads it as well as a person.</p>
     */
    static void announce() {
        OutputFormatter.printWarning("Applying without confirmation: " + reason() + ".");
    }

    /**
     * Why the confirmation question was not put to anybody.
     *
     * <p>The four circumstances are distinguishable because the reader does different things about
     * them. An agent step is how the tool works. A collected run is a worker or a loop pass, where
     * the question would have gone into a transcript nobody was watching. No terminal is how the
     * run was started. A setting is something the reader can change to get the question back.</p>
     *
     * @return a phrase naming the reason, for the end of a sentence
     */
    static String reason() {
        if (InteractivePrompts.isModelDrivenWork()) {
            return "this change runs inside an agent step, which has no terminal to answer on";
        }
        if (OutputCapture.isCapturing()) {
            return "what this run prints is being collected rather than shown, so a question here "
                   + "would reach nobody";
        }
        if (!InteractivePrompts.someoneIsThere()) {
            return "this run has no terminal to answer on";
        }
        return "ui.interactivePrompts is off";
    }
}
