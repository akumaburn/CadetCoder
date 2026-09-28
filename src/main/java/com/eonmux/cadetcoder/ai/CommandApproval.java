package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.agents.WorkerPool;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.ui.OutputCapture;
import com.eonmux.cadetcoder.ui.OutputRouter;

import java.util.Locale;

/**
 * Who answers when a shell command needs approval.
 *
 * <h2>The two modes</h2>
 *
 * <p>{@code manual} asks the person at the terminal, and is the default, so no command runs on the
 * model's word alone until the user chooses that. It asks during an agent step too; see
 * {@link #askThePerson}. Inside a worker nobody can be asked, so a command there that needs
 * approval is refused.</p>
 *
 * <p>{@code auto} asks the model. It suits a long agentic run whose commands are routine, where a
 * question per command is a question that stops being read, and it is the only mode in which a
 * worker can run a command that needs approval.</p>
 *
 * <h2>What a mode cannot change</h2>
 *
 * <p>Neither mode is consulted about a command the static screens refuse outright. A line that runs
 * {@code rm}, names a credential file or writes into {@code /etc} is refused because of what it
 * does, and there is no question left to put to anyone. Approval is asked only where
 * {@link SecurityValidator.CommandScreening#reconsiderable()} says the screen could not tell -- a
 * program built out of a variable, a script file whose contents cannot be read, a command sent to
 * the background -- and for the ordinary confirmation
 * {@code security.requireConfirmation} asks for.</p>
 *
 * <h2>Why a refusal is the answer to everything that goes wrong</h2>
 *
 * <p>The model is being asked to stand in for a person's consent. A call that fails, times out, or
 * comes back in a shape this cannot read has not given consent, and treating any of those as
 * approval would make an unreachable provider the most permissive configuration there is.</p>
 */
public final class CommandApproval {

    /** System property that overrides the configured mode for one run. */
    public static final String PROPERTY = "cadet.commandApproval";

    /** Who is asked. */
    public enum Mode {
        /** The person at the terminal. */
        MANUAL,
        /** The model. */
        AUTO
    }

    /**
     * What was decided, and why.
     *
     * @param allowed whether the command may run
     * @param reason  the words to show for it; never empty
     */
    public record Decision(boolean allowed, String reason) {

        /** @return approval, with the reason given for it */
        public static Decision allow(String reason) {
            return new Decision(true, reason == null || reason.isBlank() ? "approved" : reason.trim());
        }

        /** @return a refusal, with the reason given for it */
        public static Decision deny(String reason) {
            return new Decision(false, reason == null || reason.isBlank() ? "refused" : reason.trim());
        }
    }

    /** The one reply shape the judge is allowed to use. */
    private static final String CONTRACT =
            "Answer with one line, and nothing else: either \"ALLOW: <why it is safe>\" or "
            + "\"DENY: <what it would do that is not safe>\".";

    private CommandApproval() {
    }

    /**
     * @return who is asked when a command needs approval
     */
    public static Mode mode() {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            return "auto".equalsIgnoreCase(override.trim()) ? Mode.AUTO : Mode.MANUAL;
        }
        try {
            String configured = ConfigManager.getInstance().getConfig().getSecurity()
                                             .getCommandApproval();
            return "auto".equalsIgnoreCase(configured) ? Mode.AUTO : Mode.MANUAL;
        } catch (RuntimeException unreadable) {
            // A configuration that cannot be read is not a configuration saying "auto". The
            // narrower mode is the one to fall back to, whichever one ships as the default.
            return Mode.MANUAL;
        }
    }

    /** @return whether the model answers approval questions */
    public static boolean isAuto() {
        return mode() == Mode.AUTO;
    }

    /**
     * Asks the model whether one command may run.
     *
     * @param command     the command line as it will be run
     * @param description what the command is for, as the caller stated it; may be {@code null}
     * @param concern     what the static screens could not settle; may be {@code null}
     * @return the verdict
     */
    public static Decision judge(String command, String description, String concern) {
        if (command == null || command.isBlank()) {
            return Decision.deny("there is no command to approve");
        }
        String reply;
        try {
            // Asked outside the step's output capture, like the person is. What this request cost
            // is the tool's own bookkeeping; collected, it would be handed to the model in the next
            // prompt as though the command being screened had printed it.
            reply = OutputCapture.outsideCapture(() -> AIManager.getInstance().complete(
                    new PromptData(systemPrompt(), userPrompt(command, description, concern))));
        } catch (RuntimeException failed) {
            DebugLogger.getInstance().warn("CommandApproval",
                    "The safety check could not be completed: " + failed.getMessage());
            return Decision.deny("the safety check could not be completed: " + failed.getMessage());
        }
        return read(reply);
    }

    /**
     * Reads the judge's reply.
     *
     * <p>The verdict has to be the first thing the reply says. A model that discusses the command
     * first will use both words while it does, and picking whichever appears anywhere would be
     * reading the deliberation rather than the conclusion.</p>
     *
     * @param reply what the model said
     * @return the verdict
     */
    static Decision read(String reply) {
        String text = ResponseSanitizer.sanitize(reply == null ? "" : reply).trim();
        for (String line : text.split("\\R")) {
            String candidate = line.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            // A model asked for one line still reaches for emphasis and bullets now and then.
            candidate = candidate.replaceAll("^[-*>`#\\s]+", "").replace("**", "").strip();
            String upper = candidate.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ALLOW")) {
                return Decision.allow(after(candidate));
            }
            if (upper.startsWith("DENY")) {
                return Decision.deny(after(candidate));
            }
            break; // the verdict was not the first thing said, so there is no verdict to read
        }
        return Decision.deny("the safety check did not answer with ALLOW or DENY");
    }

    /** The reason that follows a verdict word, or a stand-in when the model gave none. */
    private static String after(String line) {
        int colon = line.indexOf(':');
        String reason = colon < 0 ? "" : line.substring(colon + 1).strip();
        return reason.isEmpty() ? "no reason given" : reason;
    }

    /** What the judge is told about the job it is doing. */
    private static String systemPrompt() {
        return "You are the safety gate of a coding assistant. You are shown ONE shell command that "
               + "the assistant is about to run in the user's project, and you decide whether it "
               + "runs.\n\n"
               + "Allow a command that does ordinary development work: reading files, searching, "
               + "listing, building, running tests, inspecting version control, writing inside the "
               + "project.\n"
               + "Deny a command that destroys work or reaches beyond the project: deleting or "
               + "overwriting files the request did not call for, rewriting version-control history, "
               + "changing the machine's configuration or services, reading credentials or keys, "
               + "sending data to another host, or anything whose effect you cannot determine from "
               + "the line itself.\n"
               + "You are not the only check. Commands that are refused outright never reach you, "
               + "so the ones you see are ordinary work in an unusual shape more often than not.\n\n"
               + CONTRACT;
    }

    /** What the judge is asked about. */
    private static String userPrompt(String command, String description, String concern) {
        StringBuilder asked = new StringBuilder();
        asked.append("Working directory: ").append(System.getProperty("user.dir")).append('\n');
        asked.append("Command:\n").append(command).append('\n');
        if (description != null && !description.isBlank()) {
            asked.append("\nWhat the assistant says it is for: ").append(description.strip())
                 .append('\n');
        }
        if (concern != null && !concern.isBlank()) {
            asked.append("\nWhat the automatic screen could not settle: ").append(concern.strip())
                 .append('\n');
        }
        asked.append('\n').append(CONTRACT);
        return asked.toString();
    }

    /**
     * Whether a question put now would reach somebody who can answer it.
     *
     * <p>A worker is the case where nobody is there. Its output is collected into its own
     * transcript rather than displayed, and nobody is watching that transcript as it is written, so
     * a question asked from inside one is never seen.</p>
     *
     * <p>An agent step in the shell is NOT that case, though its output is collected in the same
     * way so the model can read what a command said. The user is watching the run happen. Treating
     * the collection itself as "nobody is there" refused every unscreened command of every agent
     * run with "no one could be asked", while the person who could have answered sat looking at
     * it.</p>
     *
     * @return whether a person can be asked
     */
    public static boolean aPersonCanBeAsked() {
        if (WorkerPool.insideWorker()) {
            return false;
        }
        try {
            return OutputRouter.getInstance().canPrompt();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Puts one command to the person at the terminal.
     *
     * <p>Asked outside the step's output capture, so the question is drawn on the screen and the
     * answer comes from the shell's input line. Printed there too: a bare "Run it anyway?" with the
     * reason filed into the step's transcript is a question with its subject missing.</p>
     *
     * @param command the command line as it will be run
     * @param concern what the static screens could not settle, or {@code null} when this is the
     *                ordinary confirmation {@code security.requireConfirmation} asks for
     * @return whether the person allowed it
     */
    public static boolean askThePerson(String command, String concern) {
        boolean unscreened = concern != null && !concern.isBlank();
        return askThePerson(unscreened ? "This command could not be checked: " + concern.strip() : null,
                            command, unscreened ? "Run it anyway?" : "Execute this command?");
    }

    /**
     * Puts one yes-or-no question to the person at the terminal, never to the model.
     *
     * <p>For an act that only the person may approve, such as a force push, whatever
     * {@code security.commandApproval} says.</p>
     *
     * @param warning  what to say first, or {@code null}
     * @param subject  what the question is about, printed with it
     * @param question the question
     * @return whether the person said yes; {@code false} when nobody can be asked
     */
    public static boolean askThePerson(String warning, String subject, String question) {
        if (!aPersonCanBeAsked()) {
            return false;
        }
        return OutputCapture.outsideCapture(() -> {
            if (warning != null) {
                OutputFormatter.printWarning(warning);
            }
            // The subject is named here rather than left to the caller: the caller's own header
            // went into the step's capture, so on screen the question would have no subject.
            OutputFormatter.printInfo(subject == null ? "" : subject.strip());
            try {
                return OutputRouter.getInstance().getConfirmation(question);
            } catch (RuntimeException unavailable) {
                return false;
            }
        });
    }
}
