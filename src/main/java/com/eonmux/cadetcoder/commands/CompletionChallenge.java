package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.jobs.BackgroundJob;

import java.util.List;

/**
 * Questioning a run that says it has finished: about the jobs it left running, and, while uber
 * mode is on, about the request.
 *
 * <h2>Why the claim is not simply believed</h2>
 *
 * <p>A model declares completion from inside the same context that decided what completion meant, so
 * the declaration is evidence of nothing except that the model has stopped seeing work. The parts of
 * a request that get left behind are the parts that were never taken in, and those are exactly the
 * parts the closing summary does not mention -- which is why checking the summary against itself
 * always agrees, and why the check here is against the request as it was originally made.</p>
 *
 * <h2>What ends the questioning</h2>
 *
 * <p>Answering it. There are a fixed few questions, asked in turn, and a claim that answers one
 * without the run having done anything since moves on to the next. Running an action puts the run
 * back at the first question, so a model that keeps finding work keeps being asked, and only a
 * model that has genuinely stopped can answer its way to the end.</p>
 *
 * <p>The count is carried in the run's own context rather than in a field, because a fresh context
 * is rebuilt on every step and a streak held anywhere narrower would be broken by the step boundary
 * rather than by the run doing something.</p>
 */
final class CompletionChallenge {

    /** The step that reads what the model says next, which is where an answer to this belongs. */
    private static final String ANSWERED = "next_step";

    private CompletionChallenge() {
    }

    /**
     * The step that sends a run back to check itself, if this claim is to be questioned.
     *
     * @param context the run's context, which carries how many questions it has answered already
     * @param claim   what the model said when it declared the work finished
     * @return the step that continues the run, or {@code null} to accept the claim and finish
     */
    static IterativeCommand.StepResult questioning(ChatContext context, String claim) {
        // Asked first and whether or not uber mode is on: a run that ends here is never told when
        // its jobs end, so their results could not reach the answer they were started for.
        List<BackgroundJob> running = JobsLeftRunning.toAskAbout(context.getJobsAskedAbout());
        if (!running.isEmpty()) {
            context.setJobsAskedAbout(
                    JobsLeftRunning.nowAskedAbout(context.getJobsAskedAbout(), running));
            context.setStep(ANSWERED);
            OutputFormatter.printInfo("Jobs this run started are still running; asking whether the"
                                      + " answer needs them.");
            return new IterativeCommand.StepResult(false, "Completion claimed with jobs running.",
                                                   context.toMap(),
                                                   JobsLeftRunning.question(running, HOW_TO_FINISH))
                    .addressedToModel();
        }
        if (!UberMode.shouldQuestion(context.getUberChecksPassed())) {
            return null;
        }
        int round = context.getUberChecksPassed() + 1;
        context.setUberChecksPassed(round);
        // Where the run is left is what decides who reads the reply, and a question whose answer
        // nobody reads is not a question. This is asked from three steps, and one of them --
        // analyze_request -- puts the ORIGINAL request to the model and takes no reply of its own,
        // so a run left there would have the answer dropped where it arrived and the whole request
        // asked again from the beginning. next_step is the step that means "the model has spoken,
        // work out whether that was an action, a botched one, or the answer", which is exactly what
        // a reply to this is.
        context.setStep(ANSWERED);

        OutputFormatter.printInfo("Checking the claim that this is finished (" + round + " of "
                                  + UberMode.questionCount() + "). "
                                  + CommandUsage.prefix() + "ubermode off stops this.");

        return new IterativeCommand.StepResult(false, "Completion claimed; checking it.",
                                               context.toMap(),
                                               promptFor(context, claim, round))
                .addressedToModel();
    }

    /**
     * What the model is sent back with: the shared challenge, ending in the format this loop reads.
     *
     * @param context the run's context, for the request it started from
     * @param claim   what the model said when it declared the work finished
     * @param round   which question in the sequence this is, counting from 1
     * @return the prompt
     */
    private static String promptFor(ChatContext context, String claim, int round) {
        return UberMode.challenge(round, context.getUserRequest(), claim, HOW_TO_FINISH);
    }

    /**
     * Notes that the run has done something, so the next claim starts the questions again.
     *
     * <h2>Why work resets the count</h2>
     *
     * <p>The questions are about a claim, and a run that has acted since the last one is making a
     * new claim rather than repeating the old one. Left un-reset, a model could answer the first
     * question, do a further hour of work, and end on the second question alone -- with everything
     * it changed in between never checked against the request at all.</p>
     *
     * @param context the run's context
     */
    static void workWasDone(ChatContext context) {
        context.setUberChecksPassed(0);
    }

    /**
     * What the chat loop reads as "more work" and as "finished".
     *
     * <p>Restated with the challenge because a run several turns long has not been shown the format
     * since its first prompt, and a reply that neither parses as an action nor carries the marker is
     * treated as a format failure -- so a model told to keep going and not told how would be
     * corrected for obeying.</p>
     */
    private static final String HOW_TO_FINISH =
            "Respond with exactly ONE action in this format if anything remains:\n"
            + "ACTION_START\nCOMMAND: <one of the available commands>\nARGS: <arguments>\n"
            + "REASON: <why this step is needed>\nACTION_END\n"
            + "If everything really is done and verified, answer with a single line beginning "
            + "'SUCCESS:' and the run will end.";
}
