package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.OutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.io.IOException;

/**
 * Putting a question to the person at the terminal, and deciding what to do when there is nobody
 * there.
 *
 * <h2>Why the audience is decided here and not by the run loop</h2>
 *
 * <p>A step produces one prompt, and who it is addressed to settles two different things: whether
 * the model is asked, and -- when it cannot be a person -- what is answered on their behalf. Those
 * two decisions have to agree. When they lived apart, a confirmation could be recognised as the
 * user's to make in one place and answered by the model in the other, which is how a model came to
 * approve its own privileged action.</p>
 *
 * <h2>Why a missing answer is a denial and not a guess</h2>
 *
 * <p>Every default here is chosen for what it costs when it is wrong. Declining a confirmation
 * nobody was present to give cancels a step; approving one runs an action the user never sanctioned.
 * So a yes/no with no human behind it is always no.</p>
 */
final class UserAsk {

    /**
     * Bounds re-prompting, preventing a busy-loop.
     *
     * <p>Covers both kinds of re-ask -- nothing typed, and something typed that does not answer the
     * question -- because a non-interactive source that keeps returning the same unusable line would
     * otherwise spin here forever.</p>
     */
    private static final int MAX_REPROMPTS = 100;

    private final boolean isInteractive;

    /**
     * @param isInteractive whether this process was started with a person driving it
     */
    UserAsk(boolean isInteractive) {
        this.isInteractive = isInteractive;
    }

    /**
     * Whether a step's next prompt is a question for the person at the terminal.
     *
     * <p>Only the PROMPT is examined. A command's output is data -- it can contain anything the
     * filesystem or a subprocess produced, including the literal text of this application's own
     * confirmation prompts -- so reading it as protocol let a {@code read} or {@code grep} over this
     * repository turn the model's instruction format into a question aimed at the user.</p>
     *
     * @param step the step that produced the prompt
     * @return {@code true} only when the prompt is genuinely addressed to the user
     */
    boolean requiresUserInput(IterativeCommand.StepResult step) {
        if (step == null) {
            return false;
        }
        IterativeCommand.StepResult.Audience declared = step.getAudience();
        if (declared != null) {
            // The producer said. Nothing in the text can override it, which is the whole point:
            // the text is partly data.
            return declared == IterativeCommand.StepResult.Audience.USER;
        }
        return promptReadsAsAQuestionForTheUser(step.getNextPrompt());
    }

    /**
     * The fallback for a step that did not declare its audience.
     *
     * <p>A guess, and named as one. It survives only for the commands that have not been converted
     * to declare, and it is safe for them because none of them embed captured command output in a
     * prompt -- the one that did is {@code ChatCommand}, and it declares. Reading the phrasing was
     * never a sound way to decide this; see {@link IterativeCommand.StepResult.Audience}.</p>
     *
     * <p>A prompt that asks for an action block, a {@code SUCCESS:} line, or one of
     * retry/skip/stop is addressed to the model whatever else it contains. That check comes first,
     * because the phrases below are matched against a prompt that quotes the user's own request back
     * to it: a request such as "please specify the config format" would otherwise be read as the
     * application asking the user to specify something.</p>
     *
     * <p>The bias is deliberate and asymmetric. Mistaking a model prompt for a user one wastes the
     * user's attention; mistaking a user CONFIRMATION for a model one would hand the model the
     * decision on its own privileged action, so a yes/no is never quietly routed to the LLM.</p>
     *
     * @param prompt the next prompt the step produced
     * @return {@code true} when the phrasing reads as a question aimed at the user
     */
    private boolean promptReadsAsAQuestionForTheUser(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        String text = prompt.toLowerCase();

        if (isAddressedToModel(text)) {
            return false;
        }

        return isConfirmationPrompt(text) ||
               text.contains("please select") ||
               text.contains("please specify") ||
               text.contains("which file would you like") ||
               text.contains("file number") ||
               text.contains("option 1") ||
               text.contains("option 2") ||
               (text.contains("found multiple files") && text.contains("number"));
    }

    /**
     * Whether a prompt names the response protocol only the model can satisfy.
     *
     * <p>Positive identification rather than a heuristic: these are the exact contracts the chat and
     * agent loops state in the prompts they build.</p>
     */
    private static boolean isAddressedToModel(String lowerCasePrompt) {
        return lowerCasePrompt.contains("action_start")
               || lowerCasePrompt.contains("action_end")
               || lowerCasePrompt.contains("success:")
               || lowerCasePrompt.contains("task_complete")
               || lowerCasePrompt.contains("'retry'")
               || lowerCasePrompt.contains("provide another action block");
    }

    /**
     * Whether the given (already lower-cased) prompt text is a yes/no confirmation or permission
     * prompt. These must never be auto-approved by the LLM or by a fabricated default.
     */
    private boolean isConfirmationPrompt(String question) {
        // Matched up to the option list rather than to its closing bracket, because a confirmation
        // that offers a third way out is still a confirmation: "(yes/no/modify)" -- the form the
        // three commands that rewrite a file ask in -- contains none of "(yes/no)", "(y/n)" or
        // "yes/no)", so the one question in this tool that authorises overwriting somebody's work
        // was the one this did not recognise, and it was put to the model to answer.
        return question.contains("(yes/no") ||
               question.contains("(y/n") ||
               question.contains("yes/no)");
    }

    /**
     * Whether the calling thread may put a question to the person at the terminal.
     *
     * <p>Narrower than "this process is interactive" on purpose, and deliberately not folded into
     * it: that flag also selects a whole execution mode further up (a run with no initial prompt
     * skips LLM calls entirely when it is false), so widening it would stop a worker calling the
     * model at all. This asks a smaller question, at the point the question is asked rather than at
     * construction.</p>
     *
     * <p>A thread whose output is being collected cannot ask anything, because nobody will see it.
     * That is the real invariant, and it is what workers trip: a worker runs a full agent loop on a
     * background thread with {@link OutputCapture} installed, so its "User Input Required" heading
     * and the question itself go into that worker's transcript while only the bare input prompt
     * reaches the shell. The user was shown an unexplained {@code >>>} and asked to answer a question
     * they could not read. Worse, several workers run at once and each would seize the same single
     * prompt slot, so answering one released whichever had installed itself last and stranded the
     * rest -- which is what made an answered prompt look ignored and immediately re-asked.</p>
     *
     * @return {@code true} only when a question would actually reach a person who can answer it
     */
    boolean canAskUser() {
        return isInteractive && !OutputCapture.isCapturing();
    }

    /**
     * Asks the question, or answers it in the user's absence.
     *
     * @param prompt the question
     * @param output the step's output, shown as context
     * @return what the next step should be given as the answer
     */
    String getUserInput(String prompt, String output) {
        try {
            OutputFormatter.printSubheader("User Input Required");

            if (output != null && !output.trim().isEmpty()) {
                OutputFormatter.printInfo("Context:");
                // Same policy as a step's own output: a step that read a large file put the entire
                // file on screen here, bypassing the hidden-output setting entirely.
                StepOutput.printForConsole(output);
            }

            OutputFormatter.printInfo("Prompt: " + prompt);

            if (!canAskUser()) {
                // In auto mode a question from a model's work is answered by the model, in a request
                // of its own; see StandInAnswer. What follows is printed into the step's output, so
                // the model doing the work reads what was answered and why.
                if (com.eonmux.cadetcoder.ai.StandInAnswer.answersNow()) {
                    java.util.Optional<com.eonmux.cadetcoder.ai.StandInAnswer.Answer> answered =
                            com.eonmux.cadetcoder.ai.StandInAnswer.ask(
                                    prompt, output,
                                    prompt != null && isConfirmationPrompt(prompt.toLowerCase()));
                    if (answered.isPresent()) {
                        return answered.get().text();
                    }
                }
                // Both messages land in the collected output when this is a worker or a model's
                // step, so what was asked and what was assumed in its place is recorded.
                String defaultInput = getDefaultUserInput(prompt, output);
                OutputFormatter.printInfo((OutputCapture.isCapturing()
                        ? "Nobody can be asked from here" : "Non-interactive mode")
                        + ", so the default answer is used: '" + defaultInput + "'");
                return defaultInput;
            }

            return getUserInputDirectly(prompt);

        } catch (RuntimeException e) {
            // An explicit user cancellation (exit/quit) must abort the current step rather than
            // be swallowed into a fabricated default selection. Let it propagate up to the run
            // loop's handler, which prints an error and returns exit code 1.
            if (e.getMessage() != null && e.getMessage().contains("User cancelled input")) {
                throw e;
            }
            OutputFormatter.printWarning("Error getting user input: " + e.getMessage());
            return getDefaultUserInput(prompt, output);
        } catch (Exception e) {
            OutputFormatter.printWarning("Error getting user input: " + e.getMessage());
            return getDefaultUserInput(prompt, output);
        }
    }

    /**
     * Gets user input through the TUI-aware {@link OutputRouter}.
     *
     * <p>{@code OutputRouter.getUserInput} blocks on the interactive shell's prompt line when the
     * TUI is active and transparently falls back to {@link System#console()} in plain CLI mode, so a
     * single implementation serves both. Previously this branched into a placeholder that returned a
     * canned default whenever the TUI was active, which silently broke mid-step prompting for every
     * iterative command. Typing {@code exit}/{@code quit} cancels the current step by throwing a
     * "User cancelled input" runtime exception.</p>
     */
    /** Longest question kept on the input line itself; a longer one stays above it. */
    private static final int MAX_PROMPT_CHARS = 100;

    /**
     * The question, rendered onto the input line.
     *
     * @param question what is being asked
     * @return the input-line prompt, never bare
     */
    static String inputPrompt(String question) {
        if (question == null || question.isBlank()) {
            return ">>> ";
        }
        String flat = question.strip().replaceAll("\\s+", " ");
        if (flat.length() > MAX_PROMPT_CHARS) {
            // The whole question is printed above; the line only has to say which one it is.
            flat = flat.substring(0, MAX_PROMPT_CHARS - 1) + "…";
        }
        return flat + " >>> ";
    }

    private String getUserInputDirectly(String prompt) throws IOException {
        OutputRouter router = OutputRouter.getInstance();
        UnifiedOutput.println();
        UnifiedOutput.flush();

        int attempts = 0;
        while (true) {
            // The question travels with the ask. Printed separately and asked for with a bare
            // ">>> ", it reaches the screen only while nothing is collecting this thread's output --
            // so a nested command left the user looking at a prompt for a question they never saw.
            String userInput = router.getUserInput(inputPrompt(prompt));

            if (userInput == null) {
                // No input source (EOF / closed console) -- fall back to a sensible default.
                return getDefaultUserInput(prompt, "");
            }

            userInput = userInput.trim();

            // Checked BEFORE the answer is judged, so a way out exists from a question the user
            // cannot or does not want to answer. Judging first would reject "exit" at a yes/no
            // prompt and ask again, which is a loop with no door.
            if (userInput.equalsIgnoreCase("exit") || userInput.equalsIgnoreCase("quit")) {
                OutputFormatter.printInfo("User requested exit");
                throw new RuntimeException("User cancelled input");
            }

            String complaint = userInput.isEmpty()
                    ? "Please enter a valid choice"
                    : rejectionReason(userInput, prompt);

            if (complaint != null) {
                // Fall back to a default if input cannot be obtained, the thread was interrupted, or
                // we have re-prompted too many times. The final guard protects against a stopped TUI
                // runner that returns empty immediately (instead of blocking), which would otherwise
                // busy-loop.
                if (!router.canPrompt() || Thread.currentThread().isInterrupted()
                    || ++attempts > MAX_REPROMPTS) {
                    return getDefaultUserInput(prompt, "");
                }
                UnifiedOutput.print(complaint + " (or 'exit' to cancel): ");
                UnifiedOutput.flush();
                continue;
            }

            // Announced only once it has been accepted. Reporting success and then handing back
            // something the next step would read differently is the mismatch this whole path had.
            OutputFormatter.printSuccess("User input: " + userInput);
            return userInput;
        }
    }

    /**
     * Whether an answer actually answers the question.
     *
     * <h2>Why this rejects rather than warns</h2>
     *
     * <p>It used to print "Expected yes/no answer, but got: maybe" and then return "maybe"
     * unchanged. Downstream reads anything that is not yes as a refusal, so the warning described a
     * rejection while the code performed a silent denial -- the user was told their answer was wrong
     * and then had it acted on anyway, as the opposite of what they might have meant.</p>
     *
     * <h2>What is NOT policed here</h2>
     *
     * <p>Numbered choices. There used to be a range check accepting 1 through 10, a bound with no
     * relation to how many options were actually offered: eleven files listed, and a perfectly good
     * "11" drew a warning. This method knows the question's wording and nothing about the caller's
     * options, so it validates only what that wording settles -- which is yes and no.</p>
     *
     * @param input  what the user typed, already trimmed and known non-empty
     * @param prompt the question they were asked
     * @return {@code null} when the answer is acceptable, otherwise what to say before asking again
     */
    String rejectionReason(String input, String prompt) {
        String question = prompt == null ? "" : prompt.toLowerCase();
        boolean yesOrNo = isConfirmationPrompt(question)
                          || (question.contains("yes") && question.contains("no"));
        if (!yesOrNo) {
            return null;
        }
        // "true"/"false" used to be accepted and then read downstream as a refusal, because the
        // test there is for "yes". Anything but a plain yes or no is asked again instead.
        if (input.toLowerCase().matches("y|yes|n|no")) {
            return null;
        }
        return "Please answer yes or no";
    }

    /**
     * What to answer when the question cannot reach anyone.
     *
     * @param prompt the question
     * @param output the step's output; retained for the signature, deliberately not examined
     * @return the answer to hand to the next step
     */
    String getDefaultUserInput(String prompt, String output) {
        // The OUTPUT is not read, only the prompt. It is data -- a file this tool just printed, a
        // subprocess's stderr -- and reading it here decided what to ANSWER on behalf of the user
        // based on text neither of them wrote. Reading this repository was enough to find "(yes/no)"
        // in it. The parameter stays for the signature; see requiresUserInput for the same fix.
        String question = prompt == null ? "" : prompt.toLowerCase();

        // Confirmation/permission prompts default to a SAFE DENIAL when no human is present. Auto-
        // approving would bypass requireConfirmation gates (e.g. WebFetch's SSRF confirmation or an
        // agent execution prompt) and run actions the user never sanctioned. The downstream steps
        // treat anything other than yes/y as a refusal, so "no" cleanly cancels.
        if (isConfirmationPrompt(question)) {
            OutputFormatter.printWarning("No one to ask: declining confirmation (safe default)");
            return "no";
        }

        // A numbered choice, and only when the prompt actually offers numbered options. For a file
        // selection, prefer source over generated output.
        if (question.contains("option 1") && question.contains("option 2")) {
            if (question.contains("src/main/java") && question.contains("target/site")) {
                OutputFormatter.printInfo("No one to ask: choosing option 1 (source code)");
                return "1";
            }
            if (question.contains(".java") && question.contains(".html")) {
                OutputFormatter.printInfo("No one to ask: choosing the Java source file");
                return "1";
            }
            OutputFormatter.printInfo("No one to ask: choosing option 1");
            return "1";
        }
        if (question.contains("file number") || question.contains("please select")) {
            OutputFormatter.printInfo("No one to ask: choosing the first option");
            return "1";
        }

        // A bare yes/no with no marker is still a permission question, and still denied.
        if (question.contains("yes") && question.contains("no")) {
            OutputFormatter.printInfo("No one to ask: answering no (safe default)");
            return "no";
        }

        // Nothing sensible to say.
        //
        // This used to answer "1" -- and before that, reached "1" for almost anything, because the
        // test above it was `contains("1") && contains("2")` against the prompt AND the command
        // output, which any timestamp, line count or file size satisfies. A free-text question
        // ("what would you like me to do?") was therefore answered with the digit 1, and an agent
        // took that as its task. An empty answer is the honest one: every command that asks a
        // question already treats a blank reply as "not answered" and cancels that step cleanly.
        OutputFormatter.printWarning("No one to ask, and no sensible default: leaving it unanswered");
        return "";
    }
}
