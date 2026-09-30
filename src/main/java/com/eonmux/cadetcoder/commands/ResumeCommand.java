package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.ExitCode;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.agents.WorkerRegistry;
import com.eonmux.cadetcoder.resume.ResumeBriefing;
import com.eonmux.cadetcoder.session.ResumePoint;
import com.eonmux.cadetcoder.session.SessionManager;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Carries on the run the user last interrupted.
 *
 * <h2>What is carried on</h2>
 *
 * <p>An interrupted {@code chat}, {@code agent}, {@code loop}, {@code loopfresh} or
 * {@code workers} run saves where it stopped as the session's resume point; see
 * {@link ResumePoint}. This starts the same command again from there. A chat is shown its request
 * and what it did, and keeps the count of uber mode's questions its work had passed. An agent is
 * started with the same options and told what its record says it did. A loop carries on the pass
 * it was on and runs the passes left. A workers run runs again only the tasks that did not
 * finish.</p>
 *
 * <p>A run that the provider stops answering part-way saves a point as well, once it has done
 * some work, so the provider's outage costs no more than the interrupt would.</p>
 *
 * <h2>When the point is forgotten</h2>
 *
 * <p>When the resumed run ends by itself, whether its work succeeded or failed. A run interrupted
 * again saves a new point in its place. A run cut off from the model saves a new point too, or,
 * when it did nothing, leaves this one for the next attempt. A resume that cannot start, because
 * workers already run or the saved arguments cannot be read, leaves the point as it is.</p>
 *
 * <h2>Why the model is refused</h2>
 *
 * <p>It starts a model run, and a model that asks for it is already inside one; see
 * {@link ModelDispatch}.</p>
 */
@picocli.CommandLine.Command (name = "resume", description = "Carry on the run you last interrupted")
public class ResumeCommand implements CommandRegistry.InterruptibleCommand {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private final Supplier<ChatCommand>    chats;
    private final Supplier<AgentCommand>   agents;
    private final Supplier<WorkersCommand> workers;

    private CommandRegistry                     registry;
    private CommandRegistry.InterruptionContext interruptionContext;

    public ResumeCommand() {
        this(ChatCommand::new, AgentCommand::new, WorkersCommand::new);
    }

    /**
     * @param chats   makes the chat a resumed chat runs in
     * @param agents  makes the agent a resumed agent runs in
     * @param workers makes the command a resumed workers run runs in
     */
    ResumeCommand(Supplier<ChatCommand> chats, Supplier<AgentCommand> agents,
                  Supplier<WorkersCommand> workers) {
        this.chats   = chats;
        this.agents  = agents;
        this.workers = workers;
    }

    public void setCommandRegistry(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void setInterruptionContext(CommandRegistry.InterruptionContext context) {
        this.interruptionContext = context;
    }

    @Override
    public boolean shouldInterrupt() {
        return CommandRegistry.InterruptibleCommand.stopWasAsked(interruptionContext);
    }

    @Override
    public int execute(String[] args) {
        String asked = args == null || args.length == 0 ? "" : args[0].trim().toLowerCase(Locale.ROOT);
        if (asked.equals("-h") || asked.equals("--help")) {
            OutputFormatter.println(CommandUsage.render(getUsage()));
            return 0;
        }
        if (!asked.isEmpty() && !asked.equals("show") && !asked.equals("discard")) {
            OutputFormatter.printError("There is no `" + CommandUsage.prefix() + "resume " + args[0]
                                       + "`.");
            OutputFormatter.printInfo("Usage:\n" + CommandUsage.render(getUsage()));
            return 1;
        }
        Optional<ResumePoint> saved = SessionManager.getInstance().getResumePoint();
        if (saved.isEmpty()) {
            OutputFormatter.printInfo("Nothing to resume. When you interrupt a chat, agent, loop or "
                                      + "workers run, it saves where it stopped, and "
                                      + CommandUsage.render("resume") + " carries it on.");
            return 1;
        }
        ResumePoint point = saved.get();
        if (asked.equals("show")) {
            show(point);
            return 0;
        }
        if (asked.equals("discard")) {
            SessionManager.getInstance().clearResumePoint();
            OutputFormatter.printSuccess("Forgot the interrupted " + about(point) + ".");
            return 0;
        }
        if (!point.isHere()) {
            OutputFormatter.printError("The interrupted " + about(point) + " worked in "
                                       + point.project() + ". Start CadetCoder there to resume it.");
            return 1;
        }
        Optional<String> refused = refusal(point);
        if (refused.isPresent()) {
            OutputFormatter.printError(refused.get());
            return 1;
        }
        return carryOn(point);
    }

    /**
     * Starts the interrupted run again from where it stopped.
     *
     * @param point where it stopped
     * @return the resumed run's exit code
     */
    private int carryOn(ResumePoint point) {
        OutputFormatter.printHeader("Resuming the " + about(point));
        OutputFormatter.printInfo("Interrupted " + when(point) + ".");
        String briefing = ResumeBriefing.restore(point);
        // The timers are set in this process now, so a second attempt does not set them twice.
        SessionManager.getInstance().setResumePoint(
                point.withTimers(ResumePoint.Timer.inSession()));

        int result = run(point, briefing);

        if (result == ExitCode.INTERRUPTED || result == ExitCode.UNREACHABLE) {
            // Interrupted, the run saved a new point in place of this one. Unreachable, it did
            // nothing that replaces it.
            return result;
        }
        SessionManager.getInstance().clearResumePoint();
        return result;
    }

    /**
     * Why the point cannot be carried on now, checked before anything starts, so that a refusal
     * leaves the point to try again.
     *
     * @param point where the run stopped
     * @return the reason, or empty when the run can start
     */
    private static Optional<String> refusal(ResumePoint point) {
        if (point.kind() == null) {
            return Optional.of("The saved point does not say what kind of run it was.");
        }
        String[] args = point.arguments().toArray(new String[0]);
        return switch (point.kind()) {
            case ResumePoint.CHAT -> point.chat() == null || isBlank(point.chat().request())
                                     ? Optional.of("The interrupted chat has no request to carry on.")
                                     : Optional.empty();
            case ResumePoint.AGENT -> point.agent() == null
                                      || isBlank(AgentOptions.parse(args).taskDescription())
                                      ? Optional.of("The interrupted agent has no task to carry on.")
                                      : Optional.empty();
            case ResumePoint.LOOP, ResumePoint.LOOPFRESH -> {
                String unreadable = point.loop() == null ? "it holds no pass"
                                                         : LoopCommand.Options.read(args).refusal;
                yield unreadable == null ? Optional.empty() : Optional.of(
                        "The interrupted loop cannot be read back: " + unreadable);
            }
            case ResumePoint.WORKERS -> WorkerRegistry.active().isPresent()
                                        ? Optional.of("Workers are already running. Use `workers "
                                                      + "wait` or `workers stop`, then resume again.")
                                        : Optional.empty();
            default -> Optional.of("This CadetCoder cannot resume a run of kind '" + point.kind()
                                   + "'.");
        };
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    private int run(ResumePoint point, String briefing) {
        switch (point.kind()) {
            case ResumePoint.CHAT -> {
                ChatCommand chat = chats.get();
                chat.setCommandRegistry(registry);
                chat.setInterruptionContext(interruptionContext);
                return chat.resume(point.chat(), briefing);
            }
            case ResumePoint.AGENT -> {
                AgentCommand agent = agents.get();
                agent.setCommandRegistry(registry);
                agent.setInterruptionContext(interruptionContext);
                return agent.resume(point.agent(), point.arguments(), briefing);
            }
            case ResumePoint.LOOP, ResumePoint.LOOPFRESH -> {
                LoopCommand loop = ResumePoint.LOOPFRESH.equals(point.kind())
                                   ? new LoopFreshCommand() : new LoopCommand();
                loop.setCommandRegistry(registry);
                loop.setInterruptionContext(interruptionContext);
                return loop.resume(point.loop(), point.arguments(), briefing);
            }
            default -> {
                WorkersCommand run = workers.get();
                run.setInterruptionContext(interruptionContext);
                return run.resume(point.workers(), point.arguments(), briefing);
            }
        }
    }

    /** Says what would be resumed, without starting it. */
    private static void show(ResumePoint point) {
        OutputFormatter.printHeader("The interrupted " + about(point));
        OutputFormatter.printInfo("Interrupted " + when(point) + ", in " + point.project() + ".");
        if (point.chat() != null) {
            OutputFormatter.printInfo("Request: " + point.chat().request());
            OutputFormatter.printInfo("Its record holds " + point.chat().transcript().size()
                                      + " entries.");
        }
        if (point.agent() != null && point.agent().record() != null) {
            OutputFormatter.printInfo("Its record: " + point.agent().record());
        }
        if (point.loop() != null && point.loop().interruptedPass() != null) {
            OutputFormatter.printInfo("Pass " + point.loop().pass() + " was interrupted part-way: "
                                      + point.loop().interruptedPass().transcript().size()
                                      + " entries of its record are kept.");
        }
        for (ResumePoint.Worker worker : point.workers()) {
            OutputFormatter.printInfo(String.format("  %-10s %-10s %s",
                    worker.finished() ? worker.status().toLowerCase(Locale.ROOT) : "unfinished",
                    "Worker " + worker.index(), worker.task()));
        }
        for (ResumePoint.Job job : point.jobs()) {
            OutputFormatter.printInfo("  job " + job.id() + "  " + job.command());
        }
        for (ResumePoint.Timer timer : point.timers()) {
            OutputFormatter.printInfo("  timer  " + timer.instruction());
        }
        OutputFormatter.printInfo(CommandUsage.render("resume") + " carries it on; "
                                  + CommandUsage.render("resume discard") + " forgets it.");
    }

    /**
     * @param point where the run stopped, which may lack its kind or the part its kind needs when
     *              the session file was edited by hand; it can then still be shown and discarded
     * @return the run, as a few words for a heading
     */
    private static String about(ResumePoint point) {
        if (point.kind() == null) {
            return "run";
        }
        if (point.chat() == null && ResumePoint.CHAT.equals(point.kind())
            || point.loop() == null && (ResumePoint.LOOP.equals(point.kind())
                                        || ResumePoint.LOOPFRESH.equals(point.kind()))) {
            return point.kind() + " run";
        }
        return switch (point.kind()) {
            case ResumePoint.CHAT -> "chat: " + firstLine(point.chat().request());
            case ResumePoint.AGENT -> "agent: " + firstLine(
                    AgentOptions.parse(point.arguments().toArray(new String[0])).taskDescription());
            case ResumePoint.LOOP, ResumePoint.LOOPFRESH -> point.kind() + " at pass "
                    + point.loop().pass() + " of " + point.loop().times() + ": " + firstLine(
                    LoopCommand.Options.read(point.arguments().toArray(new String[0])).goal);
            case ResumePoint.WORKERS -> "workers run: "
                    + point.workers().stream().filter(ResumePoint.Worker::finished).count()
                    + " of " + point.workers().size() + " finished";
            default -> point.kind() + " run";
        };
    }

    private static String when(ResumePoint point) {
        return WHEN.format(Instant.ofEpochMilli(point.interruptedAt()).atZone(ZoneId.systemDefault()));
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String first = text.strip().lines().findFirst().orElse("");
        return first.length() > 80 ? first.substring(0, 80) + "..." : first;
    }

    @Override
    public String getUsage() {
        return "resume             carry on the run you last interrupted\n"
             + "resume show        say what would be carried on\n"
             + "resume discard     forget it\n"
             + "  A chat, agent, loop, loopfresh or workers run saves where it stopped when you\n"
             + "  interrupt it, or when the provider stops answering after it did some work. A\n"
             + "  resumed run is shown what it did, a loop carries on the pass it was on, and a\n"
             + "  workers run runs again only the tasks that did not finish.";
    }
}
