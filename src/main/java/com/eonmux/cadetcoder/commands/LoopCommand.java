package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.OutageWait;
import com.eonmux.cadetcoder.resume.ResumeScope;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.timers.TimerInterval;
import com.eonmux.cadetcoder.ui.OutputCapture;

import picocli.CommandLine.Command;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Works at one goal over and over, improving on what the last pass left.
 *
 * <h2>What this is for that a single run is not</h2>
 *
 * <p>An ordinary run ends when the model says the work is done, and a model says that as soon as it
 * stops seeing anything to do. For a goal with no finish line -- make this faster, raise the
 * coverage, tidy this package -- the first stopping point is nowhere near the best one, and the
 * model has no way of knowing that from inside the run that just ended. So the run is started
 * again, against the same goal, with the work it already did now sitting in the project in front of
 * it. Improvement comes from the repetition, which is why the count is the point and not a
 * ceiling.</p>
 *
 * <h2>Why every iteration runs</h2>
 *
 * <p>Stopping early when the model reports the goal met would put the decision back in the hands of
 * the participant that cannot check it, which is the thing {@code ubermode} exists to distrust, and
 * it would make this command an ordinary run with extra words. The count is what the user asked
 * for, so the count is what runs. Ctrl-C ends it, and is meant to.</p>
 *
 * <p>A pass that FAILED still counts, because it did work and the next one can read what it left. A
 * pass that could not reach the model at all did neither, and that one stops the loop; see
 * {@link Pass}.</p>
 *
 * <h2>Why each iteration is its own conversation</h2>
 *
 * <p>A hundred iterations in one conversation is a transcript nothing can hold. Each one is a run
 * of its own, so its prompt starts small, and what carries between them is the project itself --
 * the files the last iteration changed are the files this one reads. {@link IterativeExecutor}
 * folds a transcript that approaches the model's input window, so a single long iteration stays
 * inside it too.</p>
 *
 * <h2>What carries over, and why so little of it</h2>
 *
 * <p>The tail of what the last iteration said, and nothing else. It is there so an iteration knows
 * what was just tried rather than trying it again; it is a tail rather than the whole run because
 * the whole run is the transcript this command exists to avoid carrying. {@code loopfresh} carries
 * nothing at all, for a goal where the last attempt is a rut rather than a foundation.</p>
 */
@Command (name = "loop",
        description = "Work at one goal over and over, improving on what the last pass left")
public class LoopCommand extends LoggingCommandSupport
        implements CommandRegistry.InterruptibleCommand {

    /** How many times a loop runs when nobody says. */
    static final int DEFAULT_TIMES = 100;

    /**
     * The most iterations one command may ask for.
     *
     * <p>A loop spends a whole model run per iteration, so the difference between a typed
     * {@code 100} and a mistyped {@code 10000} is somebody's month of tokens. The ceiling is the
     * one thing here that is a ceiling.</p>
     */
    static final int MOST_TIMES = 1000;

    /** How much of an iteration's closing words the next one is told about. */
    private static final int CARRIED_CHARACTERS = 2000;

    private CommandRegistry.InterruptionContext interruptionContext;

    /** The registry this command was found in, which every pass dispatches through. */
    private CommandRegistry registry;

    /**
     * @param registry the registry that holds this command
     */
    public void setCommandRegistry(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    /**
     * Whether the user has taken the loop back.
     *
     * <p>The context is consulted as well as the signal and the thread's own flag, because a loop
     * is the one command where the difference matters: a pass may take minutes, and a Ctrl-C the
     * registry's monitor saw but this thread has not is still a Ctrl-C.</p>
     */
    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }

    /** @return whether what the last iteration said is put to the next one */
    boolean carriesTheLastResult() {
        return true;
    }

    /** @return the name this command is invoked by, for its own messages */
    String name() {
        return "loop";
    }

    @Override
    public int execute(String[] args) {
        Options options = Options.read(args);
        if (options.refusal != null) {
            OutputFormatter.printError(options.refusal);
            OutputFormatter.printInfo(getUsage());
            return 1;
        }

        startCommandLogging(name(), args);
        OutputFormatter.printHeader(options.times + " passes at: " + options.goal);
        OutputFormatter.printInfo("Ctrl-C ends it. Every pass runs; none of them decides to stop.");
        return run(options, args, new ResumePoint.Loop(1, options.times, 0, null, null), "");
    }

    /**
     * Carries on an interrupted loop from the pass it was on.
     *
     * @param loop      how far the loop had got
     * @param arguments the loop's arguments as it was started with them
     * @param briefing  what the resumed pass is told about the interrupt, beyond its own record
     * @return the exit code
     */
    public int resume(ResumePoint.Loop loop, List<String> arguments, String briefing) {
        String[] args    = arguments.toArray(new String[0]);
        Options  options = Options.read(args);
        if (options.refusal != null) {
            OutputFormatter.printError("The interrupted loop cannot be read back: " + options.refusal);
            return 1;
        }
        startCommandLogging(name(), args);
        OutputFormatter.printHeader("Pass " + loop.pass() + " of " + options.times + " at: "
                                    + options.goal);
        return run(options, args, loop, briefing == null ? "" : briefing);
    }

    /**
     * Runs the passes from where {@code from} says to start.
     *
     * <p>An interrupt ends the loop at once and saves where it stopped: the pass it was on, what
     * that pass had done, and what the last finished pass said. A pass cut short is not counted as
     * done, because {@code resume} carries it on.</p>
     *
     * @param options  what the loop was asked for
     * @param args     its arguments, for the resume point
     * @param from     the pass to start at, and what the loop knew there
     * @param briefing what the first pass is told about an interrupt it carries on from, or empty
     * @return the exit code
     */
    private int run(Options options, String[] args, ResumePoint.Loop from, String briefing) {
        int    done       = from.pass() - 1;
        int    iterations = from.iterations();
        String lastSaid   = from.lastSaid();
        try (ResumeScope scope = ResumeScope.open()) {
            for (int pass = from.pass(); pass <= options.times; pass++) {
                if (shouldInterrupt()) {
                    return stopped(scope, args, new ResumePoint.Loop(
                            pass, options.times, iterations, lastSaid, null), done);
                }
                OutputFormatter.printSubheader("Pass " + pass + " of " + options.times);
                boolean resumingIt = pass == from.pass();
                Pass ran = resumingIt && from.interruptedPass() != null
                           ? resumedPass(from.interruptedPass(), pass, options.times, iterations,
                                         briefing)
                           : onePass(options.goal, pass, options.times, iterations, lastSaid,
                                     resumingIt ? briefing : "");
                ResumePoint.Loop here = new ResumePoint.Loop(
                        pass, options.times, iterations + ran.iterations, lastSaid,
                        scope.inner().map(ResumePoint::chat).orElse(null));
                if (ran.reachedNoModel) {
                    return reachedNoModel(scope, args, here, done, ran.iterations);
                }
                if (ran.interrupted) {
                    return stopped(scope, args, here, done);
                }
                lastSaid    = ran.said;
                iterations += ran.iterations;
                done++;
            }
        }

        OutputFormatter.printSuccess("Finished " + done
                                     + (done == 1 ? " pass and " : " passes and ")
                                     + counted(iterations) + " at: " + options.goal);
        completeCommandLogging(0);
        return 0;
    }

    /**
     * Ends an interrupted loop, and saves where it stopped.
     *
     * @return the interrupted exit code
     */
    private int stopped(ResumeScope scope, String[] args, ResumePoint.Loop where, int done) {
        OutputFormatter.printWarning("Stopped after " + done
                                     + (done == 1 ? " pass, " : " passes, ")
                                     + counted(where.iterations()) + ".");
        scope.stopped(ResumePoint.loop(name(), Arrays.asList(args), where));
        completeCommandLogging(ExitCode.INTERRUPTED);
        return ExitCode.INTERRUPTED;
    }

    /**
     * Ends a loop whose pass could not reach the model, and saves where it stopped when there is
     * work to carry on.
     *
     * <p>A loop cut off in its first pass, before the pass did anything, has nothing to carry on,
     * and a resume point would only replace an earlier one.</p>
     *
     * @param inPass how many iterations the pass that could not reach the model ran
     * @return the unreachable exit code
     */
    private int reachedNoModel(ResumeScope scope, String[] args, ResumePoint.Loop where, int done,
                               int inPass) {
        OutputFormatter.printError(cutShort(where.pass(), where.times(), done, inPass));
        if (where.pass() > 1 || where.interruptedPass() != null) {
            scope.stopped(ResumePoint.loop(name(), Arrays.asList(args), where));
        } else {
            OutputFormatter.printInfo("Start the loop again once the provider will answer.");
        }
        completeCommandLogging(ExitCode.UNREACHABLE);
        return ExitCode.UNREACHABLE;
    }

    /**
     * Runs one pass and reports what it ended by saying.
     *
     * <p>The pass's own output is shown as it happens rather than held back until it finishes: a
     * loop is watched, and a hundred silent passes followed by a summary is not something anyone
     * can steer. It is collected as well as shown, because the tail of it is what the next pass is
     * told.</p>
     *
     * @param goal     what the loop is working at
     * @param pass     which pass this is, counting from 1
     * @param of       how many there are
     * @param before   how many iterations every earlier pass ran, in total
     * @param lastSaid what the previous pass ended by saying, or {@code null} for the first
     * @param briefing what the pass is told about an interrupt it carries on from, or empty
     * @return what this pass ended by saying, how it ended, and how many iterations it ran
     */
    private Pass onePass(String goal, int pass, int of, int before, String lastSaid,
                         String briefing) {
        String asked = request(goal, pass, of, lastSaid);
        if (!briefing.isBlank()) {
            asked += "\n\nThe user interrupted this loop before this pass started, and has now "
                     + "resumed it.\n" + briefing.strip();
        }
        String request = asked;
        return aPass(pass, of, before, chat -> chat.execute(new String[] {request}));
    }

    /**
     * Carries on the pass an interrupt cut short.
     *
     * @param cut      what the pass had done
     * @param pass     which pass it is, counting from 1
     * @param of       how many there are
     * @param before   how many iterations every earlier pass ran, in total
     * @param briefing what the pass is told about the interrupt, beyond its own record
     * @return what the pass ended by saying, how it ended, and how many iterations it ran
     */
    private Pass resumedPass(ResumePoint.Chat cut, int pass, int of, int before, String briefing) {
        return aPass(pass, of, before, chat -> chat.resume(cut, briefing));
    }

    /**
     * Runs one pass's conversation, showing and collecting what it prints.
     *
     * @param runs starts the conversation and answers its exit code
     */
    private Pass aPass(int pass, int of, int before,
                       ToIntFunction<ChatCommand> runs) {
        List<String> said     = new ArrayList<>();
        int[]        exitCode = new int[1];
        int iterations = LoopPass.inPass(pass, of, before, () -> OutputCapture.collectAlongside(
                said::add,
                () -> exitCode[0] = runs.applyAsInt(passChat())));
        return new Pass(tailOf(said), exitCode[0] == ExitCode.UNREACHABLE,
                        exitCode[0] == ExitCode.INTERRUPTED, iterations);
    }

    /**
     * The conversation one pass runs in.
     *
     * <p>Given this command's own registry, so every pass dispatches through the commands the
     * program started with rather than each building a registry of its own.</p>
     */
    private ChatCommand passChat() {
        ChatCommand chat = new ChatCommand();
        if (registry != null) {
            chat.setCommandRegistry(registry);
        }
        return chat;
    }

    /**
     * @param iterations how many iterations a loop has run
     * @return them in words, as {@code 1 iteration} or {@code 42 iterations}
     */
    private static String counted(int iterations) {
        return iterations + (iterations == 1 ? " iteration" : " iterations");
    }

    /**
     * What the loop says when the model stops answering during a pass.
     *
     * <h2>Why the pass's own iterations decide the wording</h2>
     *
     * <p>It used to say the pass "did no work" and count it among the passes left unrun, whatever
     * the pass had done. A pass that ran 123 iterations over four hours before the provider began
     * failing was reported as empty, and somebody reading that line would take the work to be lost
     * and not look for it. A pass that never reached the model did do nothing, and is still said
     * to.</p>
     *
     * @param pass       the pass that was cut short, counting from one
     * @param times      how many passes the loop was asked for
     * @param done       how many passes finished before it
     * @param iterations how many iterations the cut-short pass ran
     * @return the message
     */
    static String cutShort(int pass, int times, int done, int iterations) {
        if (iterations == 0) {
            return "The model could not be reached, so pass " + pass + " did no work. Stopping with "
                   + (times - done) + " of the " + times + " passes unrun; the reason is above.";
        }
        return "The model stopped answering during pass " + pass + ", after " + counted(iterations)
               + ", so that pass did not finish. Its work so far is in the project. Stopping with "
               + (times - pass) + " of the " + times + " passes not started; the reason is above.";
    }

    /**
     * What one pass is asked for.
     *
     * <h2>Why the pass is numbered in the prompt</h2>
     *
     * <p>A model told only the goal reads the work already done as somebody else's and starts
     * again. Told that it is the fourth pass of a hundred at the same goal, it reads that work as
     * the thing it is improving, which is the whole arrangement.</p>
     *
     * @param goal     what the loop is working at
     * @param pass     which pass this is, counting from 1
     * @param of       how many there are
     * @param lastSaid what the previous pass ended by saying, or {@code null}
     * @return the request to put to the model
     */
    String request(String goal, int pass, int of, String lastSaid) {
        StringBuilder asked = new StringBuilder();
        asked.append(goal);
        asked.append("\n\nThis is pass ").append(pass).append(" of ").append(of)
             .append(" at that goal. ");
        if (pass == 1) {
            asked.append("Start it.");
        } else {
            asked.append("Earlier passes have already worked on it, and what they changed is in "
                         + "the project now. Read the current state of the work before deciding "
                         + "what to do, and improve on it rather than starting again or repeating "
                         + "what is already there.");
        }
        if (carriesTheLastResult() && lastSaid != null && !lastSaid.isBlank()) {
            asked.append("\n\nThe previous pass ended by saying:\n").append(lastSaid.strip());
        }
        return asked.toString();
    }

    /**
     * The closing words of a pass.
     *
     * @param said every line the pass printed
     * @return the last of it, bounded, or {@code null} when it said nothing
     */
    private static String tailOf(List<String> said) {
        StringBuilder tail = new StringBuilder();
        for (int i = said.size() - 1; i >= 0 && tail.length() < CARRIED_CHARACTERS; i--) {
            tail.insert(0, said.get(i));
        }
        String carried = tail.toString().strip();
        return carried.isEmpty() ? null : carried;
    }

    /** @return how long one request in a loop waits for a provider, as a person would write it */
    private static String outageBudget() {
        return TimerInterval.render(
                Duration.ofMillis(OutageWait.fromSystemProperties().budgetMillis()));
    }

    @Override
    public String getUsage() {
        return name() + " [--times=<n>] <goal>\n"
             + "  Runs the goal as a full run, " + DEFAULT_TIMES + " times over by default, each\n"
             + "  pass improving on what the last one left. Every pass runs: none of them stops\n"
             + "  early because the model says the goal is met. Ctrl-C ends it.\n"
             + "  A provider that stops answering is waited for, up to " + outageBudget()
             + " per request,\n"
             + "  without asking anyone; a rejected key or a rate limit stops the loop.\n"
             + "  --times=<n>, -n <n>  how many passes, 1 to " + MOST_TIMES + "\n"
             + "  Example: " + name() + " --times=20 \"raise the test coverage of the parser\"";
    }

    /**
     * What one pass came to.
     *
     * <h2>Why a pass that reached no model is not a pass</h2>
     *
     * <p>Every pass runs whatever the model says, because the model cannot judge its own finish
     * line -- that is what this command is for. A pass that never opened a request is a different
     * thing entirely: it did nothing, it said nothing, and the next one will do nothing either,
     * because what stopped it is an expired key or an account that has been cut off for the next
     * five hours. Counted as passes, the remaining ninety-eight take a few seconds and end by
     * reporting a hundred passes done at a goal nothing was done to.</p>
     *
     * @param said          what the pass ended by saying, or {@code null}
     * @param reachedNoModel whether the pass ended because no request could be made at all
     * @param interrupted    whether the user interrupted the pass
     * @param iterations     how many turns the pass took, which the loop counts across all of them
     */
    private record Pass(String said, boolean reachedNoModel, boolean interrupted, int iterations) {
    }

    /** What a loop was asked to do, or why it cannot be. */
    static final class Options {

        String goal;
        int    times = DEFAULT_TIMES;
        String refusal;

        /**
         * Reads the arguments.
         *
         * @param args the command's arguments
         * @return what was asked for, carrying {@link #refusal} when it cannot be honoured
         */
        static Options read(String[] args) {
            Options options = new Options();
            if (args == null || args.length == 0) {
                options.refusal = "What should the loop work at?";
                return options;
            }
            List<String> words = new ArrayList<>();
            String[] expanded = CommandOptions.expandInlineValues(args, java.util.Set.of("--times"));
            for (int i = 0; i < expanded.length; i++) {
                String word = expanded[i];
                boolean isCount = "--times".equals(word) || "-n".equals(word);
                if (isCount && i + 1 < expanded.length) {
                    options.refusal = options.countedFrom(expanded[++i]);
                    if (options.refusal != null) {
                        return options;
                    }
                    continue;
                }
                if (isCount) {
                    options.refusal = "--times needs a number after it.";
                    return options;
                }
                words.add(word);
            }
            options.goal = String.join(" ", words).strip();
            if (options.goal.isEmpty()) {
                options.refusal = "What should the loop work at?";
            }
            return options;
        }

        /**
         * Reads the count, and says why it cannot be read when it cannot.
         *
         * @param value what was written after the flag
         * @return the refusal, or {@code null} when the count was taken
         */
        private String countedFrom(String value) {
            int asked;
            try {
                asked = Integer.parseInt(value.trim());
            } catch (NumberFormatException notANumber) {
                return "'" + value + "' is not a number of passes.";
            }
            if (asked < 1) {
                return "A loop of " + asked + " passes would do nothing.";
            }
            if (asked > MOST_TIMES) {
                // Refused rather than clamped. A loop spends a model run per pass, and quietly
                // running 1000 of the 10000 somebody asked for is a bill they did not agree to.
                return asked + " passes is more than " + MOST_TIMES
                       + ", which is as many as one loop may ask for.";
            }
            times = asked;
            return null;
        }
    }
}
