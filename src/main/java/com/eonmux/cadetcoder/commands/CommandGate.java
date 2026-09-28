package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.CommandApproval;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import com.eonmux.cadetcoder.security.SecurityValidator;

/**
 * Everything that has to be true before a shell command is allowed to run.
 *
 * <h2>Why two commands share one gate</h2>
 *
 * <p>{@code bash} runs a command and waits for it; {@code job start} runs one and does not. That is
 * the whole difference, and it has nothing to do with whether the command may run at all. Written
 * twice, the two would answer differently the first time either was changed -- and the difference
 * would be that the way of running a command which nobody is watching, for as long as it likes, was
 * the one screened less carefully. {@link SecurityValidator} already exists so that this tool and
 * the screen the model's proposals pass through cannot disagree; this is the same argument one
 * level up.</p>
 *
 * <h2>Why it is two steps and not one</h2>
 *
 * <p>The screen asks whether the command is allowed. The confirmation asks whether the person wants
 * it run. Between them each caller prints what it is about to do, which is the last thing shown
 * before a question that may be put to somebody. Folded into one call, the question would come
 * before the announcement of what it was about.</p>
 */
final class CommandGate {

    /**
     * What a gate decided.
     *
     * @param allowed        whether the caller may carry on
     * @param exitCode       what to return when it may not; meaningless when it may
     * @param alreadyApproved whether somebody has already said yes, so the confirmation below must
     *                        not ask the same question again
     */
    record Verdict(boolean allowed, int exitCode, boolean alreadyApproved) {

        static Verdict go(boolean alreadyApproved) {
            return new Verdict(true, 0, alreadyApproved);
        }

        static Verdict stop(int exitCode) {
            return new Verdict(false, exitCode, false);
        }
    }

    private CommandGate() {
    }

    /**
     * Whether {@code --force} came from the person this gate would otherwise interrupt.
     *
     * <h2>Why a model's --force is not one</h2>
     *
     * <p>The flag means "the user has already said yes", which is why it skips the question. It is
     * parsed off the argument list, and on the agentic path that list is written by the model:
     * {@code bash -f ./deploy.sh} then answers the gate's question on the user's behalf, with the
     * user never asked and the run never told. The model is a participant in the session, not the
     * person the confirmation is for, so the flag is ignored when the command came from one --
     * {@link ModelDispatch} is the seam that already knows which it was.</p>
     *
     * <p>The screen itself is unchanged. A model's command is still screened, still judged
     * automatically where that is configured, and still refused where the line cannot be read and
     * nobody can be asked. What it cannot do is skip the asking.</p>
     *
     * @param log     where the audit trail goes
     * @param asked   whether the argument list carried the flag
     * @param command the command line, for the record
     * @return whether to honour it
     */
    private static boolean personsForce(LoggingCommandSupport log, boolean asked, String command) {
        boolean honoured = ModelDispatch.personsForce(asked);
        if (asked && !honoured) {
            log.logSecurityEvent("FORCE_IGNORED",
                    "Ignoring --force on a command a model asked for: " + command, false);
        }
        return honoured;
    }

    /**
     * Screens a command against the shell policy.
     *
     * @param log         where the audit trail goes
     * @param command     the shell command line, joined as it will be run
     * @param description what the caller said it is for, or {@code null}
     * @param force       whether the confirmation was answered in advance
     * @return whether to carry on, and who has already said yes
     */
    static Verdict screen(LoggingCommandSupport log, String command, String description,
                          boolean requestedForce) {
        Configuration.SecurityConfig security  = ConfigManager.getInstance().getConfig().getSecurity();
        SecurityValidator            validator = new SecurityValidator();
        boolean                      force     = personsForce(log, requestedForce, command);

        if (ReadOnlyGuard.isEnabled()) {
            log.logSecurityEvent("READ_ONLY_MODE_CHECK", "Command execution in read-only mode", false);
            ReadOnlyGuard.blocks("run commands");
            return Verdict.stop(1);
        }

        // One screen, which says what it found rather than only that it found something. The
        // credential, network, denylist and path rules all live inside it, so this command and the
        // screen the model's proposals pass through cannot disagree about what may run.
        SecurityValidator.CommandScreening screening = validator.screenCommand(command);
        log.logSecurityEvent("COMMAND_VALIDATION",
                "Command validation for: " + command
                + (screening.allowed() ? "" : " (" + screening.reason() + ")"),
                screening.allowed());

        if (screening.allowed()) {
            return Verdict.go(false);
        }
        if (screening.personOnly()) {
            return askOnlyThePerson(log, command, screening.reason());
        }
        if (!screening.reconsiderable()) {
            OutputFormatter.printError("Command blocked by security policy: " + screening.reason());
            if (security.isSandboxMode()) {
                OutputFormatter.printInfo("Sandbox mode is enabled. Only safe commands are allowed.");
            }
            return Verdict.stop(1);
        }

        // The screen could not read the line rather than objecting to it, so somebody decides.
        if (CommandApproval.isAuto()) {
            CommandApproval.Decision verdict =
                    CommandApproval.judge(command, description, screening.reason());
            log.logSecurityEvent("COMMAND_APPROVAL",
                    "Automatic approval for: " + command + " (" + verdict.reason() + ")",
                    verdict.allowed());
            if (!verdict.allowed()) {
                OutputFormatter.printError("Command refused by the safety check: " + verdict.reason());
                return Verdict.stop(1);
            }
            OutputFormatter.printInfo("Safety check allowed this command: " + verdict.reason());
        } else if (force) {
            // --force is the user having said yes in advance, which is the same person this would
            // otherwise interrupt.
            OutputFormatter.printWarning("Running an unscreened command because --force was given: "
                                         + screening.reason());
        } else if (CommandApproval.aPersonCanBeAsked()) {
            log.logUserInteraction("CONFIRMATION_REQUIRED",
                    "Requesting user approval for an unscreened command: " + command);
            if (!CommandApproval.askThePerson(command, screening.reason())) {
                log.logUserInteraction("CONFIRMATION_DENIED", "User denied an unscreened command");
                OutputFormatter.printInfo("Command execution cancelled");
                return Verdict.stop(ExitCode.INTERRUPTED);
            }
            log.logUserInteraction("CONFIRMATION_GRANTED", "User approved an unscreened command");
        } else {
            OutputFormatter.printError("Command blocked by security policy: " + screening.reason());
            OutputFormatter.printInfo("No one could be asked. Set security.commandApproval to "
                                      + "auto to have the model decide instead.");
            return Verdict.stop(1);
        }
        return Verdict.go(true);
    }

    /**
     * Puts a command only the person may allow to the person, whatever else would decide.
     *
     * <p>Neither the model under {@code auto} approval nor a {@code --force} given in advance answers
     * this: the command can discard work that exists nowhere else, so the person sees it first.</p>
     *
     * @param log     where the audit trail goes
     * @param command the shell command line
     * @param reason  what the screen found
     * @return whether to carry on
     */
    private static Verdict askOnlyThePerson(LoggingCommandSupport log, String command, String reason) {
        log.logUserInteraction("CONFIRMATION_REQUIRED", "Only the person may allow: " + command);
        if (CommandApproval.askThePerson("Only you can allow this: " + reason + ".", command,
                                         "Run it?")) {
            log.logUserInteraction("CONFIRMATION_GRANTED", "The person allowed: " + command);
            return Verdict.go(true);
        }
        if (!CommandApproval.aPersonCanBeAsked()) {
            log.logSecurityEvent("PERSON_ONLY_REFUSED", "Nobody could be asked about: " + command, false);
            OutputFormatter.printError("Command blocked: " + reason + ", and nobody can be asked to "
                                       + "allow it. Only the user can run it.");
            return Verdict.stop(1);
        }
        log.logUserInteraction("CONFIRMATION_DENIED", "The person declined: " + command);
        OutputFormatter.printInfo("Command execution cancelled");
        return Verdict.stop(ExitCode.INTERRUPTED);
    }

    /**
     * Puts the command to whoever decides whether it should run.
     *
     * @param log             where the audit trail goes
     * @param command         the shell command line
     * @param description     what the caller said it is for, or {@code null}
     * @param force           whether the confirmation was answered in advance
     * @param alreadyApproved what {@link #screen} reported
     * @return whether to carry on
     */
    static Verdict confirm(LoggingCommandSupport log, String command, String description,
                           boolean requestedForce, boolean alreadyApproved) {
        Configuration.SecurityConfig security = ConfigManager.getInstance().getConfig().getSecurity();
        boolean                      force    = personsForce(log, requestedForce, command);
        if (!security.isRequireConfirmation() || force || alreadyApproved) {
            return Verdict.go(alreadyApproved);
        }

        if (CommandApproval.isAuto()) {
            // Auto approval exists for the runs nobody is watching. A worker's question reaches no
            // one and was answered "no" by the code that noticed as much, so the step was lost
            // without anyone deciding anything; here the model decides it.
            CommandApproval.Decision verdict = CommandApproval.judge(command, description, null);
            log.logSecurityEvent("COMMAND_APPROVAL",
                    "Automatic approval for: " + command + " (" + verdict.reason() + ")",
                    verdict.allowed());
            if (!verdict.allowed()) {
                OutputFormatter.printError("Command refused by the safety check: " + verdict.reason());
                return Verdict.stop(1);
            }
            OutputFormatter.printInfo("Safety check allowed this command: " + verdict.reason());
            return Verdict.go(true);
        }

        log.logUserInteraction("CONFIRMATION_REQUIRED",
                "Requesting user confirmation for command: " + command);
        // Asked through CommandApproval rather than the router directly, because an agent step runs
        // with its output collected: the question had been filed into the step's transcript instead
        // of drawn on the screen, and the gate then answered "no" on behalf of a user who was
        // watching the run and was never shown anything.
        if (!CommandApproval.askThePerson(command, null)) {
            log.logUserInteraction("CONFIRMATION_DENIED", "User denied command execution");
            OutputFormatter.printInfo("Command execution cancelled");
            if (!CommandApproval.aPersonCanBeAsked()) {
                // The step was lost without anybody deciding anything, which reads as a refusal
                // nobody made. Name the setting that puts the decision somewhere.
                OutputFormatter.printInfo("No one could be asked. Set security.commandApproval to "
                                          + "auto to have the model decide instead.");
            }
            // A denied or unobtainable confirmation means the command never ran, which is the same
            // ending as one the user took back: reported apart from both success and failure.
            return Verdict.stop(ExitCode.INTERRUPTED);
        }
        log.logUserInteraction("CONFIRMATION_GRANTED", "User approved command execution");
        return Verdict.go(true);
    }
}
