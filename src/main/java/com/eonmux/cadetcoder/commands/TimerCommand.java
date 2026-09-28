package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.timers.AgentTimer;
import com.eonmux.cadetcoder.timers.TimerInterval;
import com.eonmux.cadetcoder.timers.TimerRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A standing reminder the model leaves itself, delivered on every prompt it comes due for.
 *
 * <h2>What this is for</h2>
 *
 * <p>Some work is long and mostly not the agent's to do: a build running under workers, a test suite,
 * anything started and then waited on. The agent has no clock of its own -- it sees a prompt, answers
 * it, and is gone until the next one -- so "check back on this in two minutes" is a thing it can
 * intend and cannot arrange. A timer is that arrangement: the instruction is kept here, and every
 * loop in this tool appends the ones that have come due to the prompt it is about to send, with the
 * count and the time attached.</p>
 *
 * <h2>Why waiting is a command and not a sleep</h2>
 *
 * <p>An agent with nothing to do until its next check-in would otherwise burn the interval in model
 * calls, asking what to do next and being told to wait -- which is the most expensive way to pass two
 * minutes ever devised. {@code timer wait} spends it in one command instead, and returns as soon as
 * something is actually owed.</p>
 */
@picocli.CommandLine.Command (name = "timer",
        description = "Set a repeating check-in that fires into a later step")
public class TimerCommand implements CommandRegistry.InterruptibleCommand {

    /** Flags that take a value, so {@code --every=2m} means the same as {@code --every 2m}. */
    private static final Set<String> VALUE_FLAGS = Set.of("--every", "-e", "--limit", "-l");

    /** How long one blocking wait may last, however far off the next firing is. */
    private static final long MAX_WAIT_SECONDS = 600;

    /** How often a wait looks up from sleeping, so an interrupt is noticed promptly. */
    private static final long WAIT_TICK_MILLIS = 200;

    private volatile CommandRegistry.InterruptionContext interruptionContext;

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    /** @return whether whoever started this has asked for it to stop */
    private boolean interrupted() {
        return (interruptionContext != null && interruptionContext.isInterrupted())
               || Thread.currentThread().isInterrupted();
    }

    @Override
    public int execute(String[] args) {
        if (args == null || args.length == 0) {
            return list();
        }
        String[] expanded = CommandOptions.expandInlineValues(args, VALUE_FLAGS);
        switch (expanded[0].toLowerCase()) {
            case "create":
            case "add":
            case "set":
                return create(rest(expanded));
            case "list":
            case "status":
                return list();
            case "cancel":
            case "stop":
            case "remove":
                return cancel(rest(expanded));
            case "wait":
                return waitForOne(rest(expanded));
            default:
                OutputFormatter.printError("Unknown timer subcommand: " + expanded[0]);
                OutputFormatter.printInfo(getUsage());
                return 1;
        }
    }

    /** @return {@code args} without its first token */
    private static String[] rest(String[] args) {
        return java.util.Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * Sets a timer.
     *
     * <p>The instruction is every token that is not an option, joined back together. A model that
     * wrote {@code timer create --every 2m check the build} meant one instruction of four words, and
     * insisting it quote them would fail the call rather than teach it to.</p>
     *
     * @param args everything after {@code create}
     * @return the exit code
     */
    private int create(String[] args) {
        Duration     every       = null;
        int          limit       = AgentTimer.UNLIMITED;
        List<String> instruction = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String token = args[i];
            if (("--every".equals(token) || "-e".equals(token)) && i + 1 < args.length) {
                try {
                    every = TimerInterval.parse(args[++i]);
                } catch (IllegalArgumentException e) {
                    OutputFormatter.printError(e.getMessage());
                    return 1;
                }
            } else if (("--limit".equals(token) || "-l".equals(token)) && i + 1 < args.length) {
                try {
                    limit = Math.max(0, Integer.parseInt(args[++i].trim()));
                } catch (NumberFormatException e) {
                    OutputFormatter.printError("Not a number of firings: " + args[i]);
                    return 1;
                }
            } else {
                instruction.add(token);
            }
        }

        if (every == null) {
            OutputFormatter.printError("A timer needs an interval: --every 2m");
            OutputFormatter.printInfo(getUsage());
            return 1;
        }
        if (instruction.isEmpty()) {
            OutputFormatter.printError("A timer needs to say what it is reminding you to do.");
            OutputFormatter.printInfo(getUsage());
            return 1;
        }

        try {
            AgentTimer timer = TimerRegistry.create(String.join(" ", instruction), every, limit);
            OutputFormatter.printSuccess("Timer " + timer.id() + " set, firing every "
                                         + TimerInterval.render(timer.interval())
                                         + (limit == AgentTimer.UNLIMITED
                                            ? "" : " at most " + limit + " times")
                                         + ": " + timer.instruction());
            OutputFormatter.printInfo("It will be reported in a later step. `timer cancel "
                                      + timer.id() + "` stops it.");
            return 0;
        } catch (IllegalArgumentException | IllegalStateException e) {
            OutputFormatter.printError(e.getMessage());
            return 1;
        }
    }

    /** Shows what is set and when each one is next owed. */
    private int list() {
        List<AgentTimer> running = TimerRegistry.active();
        if (running.isEmpty()) {
            OutputFormatter.printInfo("No timers are set. `timer create --every 2m <what to check>`"
                                      + " sets one.");
            return 0;
        }
        OutputFormatter.printSubheader("Timers");
        Instant now = Instant.now();
        for (AgentTimer timer : running) {
            OutputFormatter.printInfo(String.format("  %-4s every %-7s next in %-7s fired %d, %s left: %s",
                                                    timer.id(),
                                                    TimerInterval.render(timer.interval()),
                                                    untilDue(timer, now),
                                                    timer.fireCount(),
                                                    timer.remaining(),
                                                    timer.instruction()));
        }
        return 0;
    }

    /** How long until {@code timer} is next owed, or {@code now} when it already is. */
    private static String untilDue(AgentTimer timer, Instant now) {
        Duration left = Duration.between(now, timer.dueAt());
        return left.isNegative() || left.isZero() ? "now" : TimerInterval.render(left);
    }

    /**
     * Stops one timer, or all of them.
     *
     * @param args the id, or {@code all}
     * @return the exit code
     */
    private int cancel(String[] args) {
        if (args.length == 0) {
            OutputFormatter.printError("Which timer? `timer cancel <id>`, or `timer cancel all`.");
            return 1;
        }
        if ("all".equalsIgnoreCase(args[0].trim())) {
            int stopped = TimerRegistry.cancelAll();
            OutputFormatter.printSuccess(stopped == 0
                    ? "There were no timers to cancel."
                    : "Cancelled " + stopped + (stopped == 1 ? " timer." : " timers."));
            return 0;
        }
        if (TimerRegistry.cancel(args[0])) {
            OutputFormatter.printSuccess("Timer " + args[0].trim() + " cancelled.");
            return 0;
        }
        OutputFormatter.printError("No timer called " + args[0].trim()
                                   + ". `timer list` shows what is set.");
        return 1;
    }

    /**
     * Waits until something is owed.
     *
     * <p>Bounded twice over: by whatever the caller asked for, and by {@link #MAX_WAIT_SECONDS},
     * because a command that blocks for an hour is indistinguishable at the terminal from one that
     * has hung. The firing itself is NOT consumed here -- it is reported where every other firing is,
     * in the next prompt -- so waiting and being told are one thing that happens in order rather than
     * two that can disagree.</p>
     *
     * @param args how many seconds at most, if the caller said
     * @return the exit code
     */
    private int waitForOne(String[] args) {
        if (TimerRegistry.active().isEmpty()) {
            OutputFormatter.printInfo("No timers are set, so there is nothing to wait for.");
            return 0;
        }
        long capSeconds = MAX_WAIT_SECONDS;
        if (args.length > 0) {
            try {
                capSeconds = Math.min(MAX_WAIT_SECONDS, Math.max(0, Long.parseLong(args[0].trim())));
            } catch (NumberFormatException e) {
                OutputFormatter.printError("Not a number of seconds: " + args[0]);
                return 1;
            }
        }

        Instant deadline = Instant.now().plusSeconds(capSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (interrupted()) {
                OutputFormatter.printWarning("Stopped waiting.");
                return 0;
            }
            if (TimerRegistry.nextDueAt().map(due -> !Instant.now().isBefore(due)).orElse(true)) {
                OutputFormatter.printInfo("A timer has come due; it is reported with the next step.");
                return 0;
            }
            try {
                Thread.sleep(WAIT_TICK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                OutputFormatter.printWarning("Stopped waiting.");
                return 0;
            }
        }
        OutputFormatter.printInfo("Waited " + capSeconds + "s; the next timer is still not due.");
        return 0;
    }

    @Override
    public String getUsage() {
        return "timer create --every <interval> <what to check> [--limit <n>]\n"
             + "timer list                       what is set, and when each is next due\n"
             + "timer cancel <id>|all            stop one, or all of them\n"
             + "timer wait [<seconds>]           block until the next one is due\n"
             + "Intervals are written 30s, 5m, 2h or 1h30m.";
    }
}
