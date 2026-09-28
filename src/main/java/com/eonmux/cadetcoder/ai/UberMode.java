package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.prompts.PromptResource;

import java.util.List;

/**
 * Driving a task to the end rather than to the first plausible stopping point.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>Every agentic loop in this tool ends the same way: the model says it is finished and the loop
 * believes it. That is the only signal available, and it is produced by the one participant with no
 * way of checking it -- the model declares completion from inside the same context that decided what
 * completion meant. So a run ends with the tests unrun, with the second call site never updated, with
 * the last third of the request answered in prose; and from the outside that is indistinguishable
 * from a run that finished, because the last line of both is a summary saying it did.</p>
 *
 * <h2>What is done about it</h2>
 *
 * <p>The claim is questioned before it is accepted. The model is sent back with the original request
 * and asked to check it part by part against what actually happened -- and, where the project has a
 * way of being checked, to run it rather than reason about it. A claim that survives that ends the
 * run; a claim that does not turns into more work, which is the entire point.</p>
 *
 * <h2>What ends a run, now that nothing counts down</h2>
 *
 * <p>There are a fixed few questions and the run ends when the model has answered all of them in a
 * row without doing anything in between. Doing something puts it back at the first one. So the
 * questions are unbounded over a run -- a model that keeps finding work keeps being asked -- while
 * a model with nothing left to do answers its way out in as many turns as there are questions.</p>
 *
 * <p>This replaces a fixed budget of questions per run. A budget ended runs for the wrong reason:
 * it stopped asking after the second claim, so the claims a long run made after that were the ones
 * nobody checked, and those are the claims worth checking. It also meant a run could end with the
 * model having never once answered the closing questions cleanly -- it simply outlasted them.</p>
 *
 * <h2>Why it is off by default</h2>
 *
 * <p>Every one of these costs a turn against the user's own tokens on work they may consider
 * already done, so it is asked for, never assumed.</p>
 */
public final class UberMode {

    /** The name of the prompt resource holding what the model is told about working this way. */
    public static final String PROMPT_NAME = "ubermode";

    /**
     * The first push-back, which asks for the request to be re-read.
     *
     * <p>Against the ORIGINAL request rather than the summary just written, because a summary is
     * written from the same understanding that decided the work was done: checked against itself it
     * always agrees, and the parts of the request that were never taken in are exactly the parts a
     * summary does not mention.</p>
     */
    private static final String FIRST =
            "Before this run ends: go back to the request as it was originally made and take it "
            + "apart into every separate thing it asked for. For each one, say whether it is done "
            + "-- done, not planned, not partly done, not described. Where this project has a way "
            + "of being checked (its build, its tests, its linter), run it now rather than "
            + "reasoning about whether it would pass. If anything is outstanding, continue with "
            + "ONE action and do not claim completion again until it is not.";

    /**
     * The second push-back, which asks about what was changed rather than what was asked.
     *
     * <p>Deliberately a different question. Repeating the first one gets the first answer again --
     * the model has already checked the request against its own account of the work and found them
     * to agree. What it has not done is look at what it actually touched and ask what else depends
     * on it, which is where the work that gets left behind actually lives.</p>
     */
    private static final String SECOND =
            "One more check before this run ends: look at what you actually changed, not at what "
            + "you set out to change. For each file you touched, is anything that depends on it now "
            + "wrong or out of date -- another caller, a test, a document that describes the old "
            + "behaviour? Did you leave anything unfinished, or anything working only because you "
            + "have not tried the case that breaks it? If so, continue with ONE action; if not, "
            + "declare completion and the run will end.";

    /** The questions, in the order they are put. A claim must survive all of them in a row. */
    private static final List<String> QUESTIONS = List.of(FIRST, SECOND);

    private UberMode() {
    }

    /** @return whether runs are being driven to the end */
    public static boolean isOn() {
        try {
            return ConfigManager.getInstance().getConfig().getAi().isUberMode();
        } catch (RuntimeException e) {
            // A configuration this cannot be read from is not a reason to change how a run behaves;
            // the answer that changes nothing is the safe one.
            return false;
        }
    }

    /** @return how many questions a claim has to survive, in a row, for the run to end */
    public static int questionCount() {
        return QUESTIONS.size();
    }

    /**
     * What the model is told about working this way, for the system prompt.
     *
     * @return the directive, or an empty string when the mode is off or the prompt is absent
     */
    public static String directive() {
        return isOn() ? PromptResource.text(PROMPT_NAME) : "";
    }

    /**
     * A command's own system prompt with the directive on the end.
     *
     * <p>Appended rather than prepended: it modifies the contract the command has just stated, and
     * an instruction that qualifies another one has to be read after it. Applied by the agentic
     * commands themselves rather than in {@link SystemPromptProvider#compose}, which every
     * completion passes through -- a one-shot explanation is not a run, and telling it not to stop
     * until the task is finished describes nothing it is doing.</p>
     *
     * @param commandPrompt what the command tells the model it is doing
     * @return the prompt to send, unchanged when the mode is off
     */
    public static String applyTo(String commandPrompt) {
        String directive = directive();
        if (directive.isEmpty()) {
            return commandPrompt;
        }
        return commandPrompt == null || commandPrompt.isBlank()
               ? directive
               : commandPrompt + "\n\n" + directive;
    }

    /**
     * The push-back for one claim of completion.
     *
     * @param round which question in the sequence this is, counting from 1
     * @return what to send back, the last question for any round beyond the sequence
     */
    public static String questionFor(int round) {
        int index = Math.min(Math.max(round, 1), QUESTIONS.size()) - 1;
        return QUESTIONS.get(index);
    }

    /**
     * Everything a run is sent back with when its claim of completion is questioned.
     *
     * <h2>Why the request leads and the claim follows it</h2>
     *
     * <p>The request is the thing being checked against, and by the time a run claims completion it
     * is many turns back and has been restated, summarised and worked around. Putting it first makes
     * the check a comparison rather than a recollection. The claim is quoted after it so what is
     * being questioned is one specific set of words, not the general idea of being finished -- a
     * model asked whether it is "really done" agrees; a model asked whether a named claim holds
     * against a named request has to look.</p>
     *
     * @param round       which question in the sequence this is, counting from 1
     * @param request     what the run was asked for, if it is known
     * @param claim       what the model said when it declared the work finished
     * @param howToFinish what the model should answer instead if the claim does hold, in the form
     *                    the calling loop actually reads
     * @return the prompt
     */
    public static String challenge(int round, String request, String claim, String howToFinish) {
        StringBuilder prompt = new StringBuilder();
        if (request != null && !request.isBlank()) {
            prompt.append("The request was: ").append(request.strip()).append("\n\n");
        }
        if (claim != null && !claim.isBlank()) {
            prompt.append("You have just said this run is finished:\n")
                  .append(claim.strip()).append("\n\n");
        }
        prompt.append(questionFor(round));
        if (howToFinish != null && !howToFinish.isBlank()) {
            prompt.append("\n\n").append(howToFinish.strip());
        }
        return prompt.toString();
    }

    /**
     * Whether a run that has answered {@code passed} questions in a row is asked another.
     *
     * <h2>Why the count is of a streak rather than of a run</h2>
     *
     * <p>The count is reset by the run doing anything, so it says how many questions the model has
     * answered since it last acted. That is what makes the questioning unbounded while still
     * ending: there is no number of questions a run may not exceed, only a number it must answer
     * consecutively, and only a model that has genuinely stopped working can reach it.</p>
     *
     * @param passed how many questions this run has answered since it last did any work
     * @return whether to question the claim now being made
     */
    public static boolean shouldQuestion(int passed) {
        return isOn() && passed < QUESTIONS.size();
    }
}
