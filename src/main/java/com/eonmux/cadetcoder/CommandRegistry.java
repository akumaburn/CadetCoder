package com.eonmux.cadetcoder;

import com.eonmux.cadetcoder.error.ErrorHandler;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.logging.SessionLogger;
import com.eonmux.cadetcoder.security.SecretRedactor;
import org.reflections.Reflections;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Registers and executes commands for CadetCoder.
 */
public class CommandRegistry {
    private final Map<String, Command> commands = new HashMap<>();

    /**
     * The command classes, found once for the life of the program.
     *
     * <h2>Why they are not looked for again</h2>
     *
     * <p>Finding them means reading the classpath, and every loop pass builds a registry of its own.
     * A rebuild replaced the jar the program was running from during a {@code loopfresh} run: pass 1
     * went on working on the classes already loaded, and the scan for pass 2 read a jar that was no
     * longer there, found nothing, and raised nothing. Every command the model asked for from then
     * on was "Unknown command", for twelve passes. The classes found at the first scan are loaded
     * and stay usable whatever happens to the file they came from.</p>
     */
    private static volatile List<Class<? extends Command>> discovered;

    public CommandRegistry() {
        registerCoreCommands();
    }

    /**
     * @return the command classes, scanning the classpath the first time a scan finds any
     */
    private static List<Class<? extends Command>> commandClasses() {
        List<Class<? extends Command>> known = discovered;
        if (known != null) {
            return known;
        }
        synchronized (CommandRegistry.class) {
            if (discovered == null) {
                Set<Class<? extends Command>> found =
                        new Reflections("com.eonmux.cadetcoder.commands").getSubTypesOf(Command.class);
                List<Class<? extends Command>> sorted = new ArrayList<>(found == null ? Set.of() : found);
                sorted.sort(Comparator.comparing(Class::getName));
                if (sorted.isEmpty()) {
                    // Not remembered, so a later registry looks again rather than inheriting nothing.
                    return List.of();
                }
                discovered = List.copyOf(sorted);
            }
            return discovered;
        }
    }

    /**
     * Registers core and additional commands via automatic discovery using Reflections.
     */
    private void registerCoreCommands() {
        try {
            List<Class<? extends Command>> commandClasses = commandClasses();
            if (commandClasses.isEmpty()) {
                OutputFormatter.printError("No commands could be found: CadetCoder cannot read its own "
                        + "classes. The jar it runs from may have been deleted or replaced while it was "
                        + "running; start it again.");
                return;
            }
            for (Class<? extends Command> commandClass : commandClasses) {
                // Skip anonymous or inner classes and test-related classes
                if (commandClass.getName().contains("$") || commandClass.getName().toLowerCase().contains("test")) {
                    continue;
                }

                if (!Modifier.isAbstract(commandClass.getModifiers())) {
                    Command commandInstance = commandClass.getDeclaredConstructor().newInstance();
                    try {
                        Method setRegistry = commandClass.getMethod("setCommandRegistry", CommandRegistry.class);
                        setRegistry.invoke(commandInstance, this);
                    } catch (NoSuchMethodException e) {
                        // Optional: command may not need the registry
                    }
                    String commandName = determineCommandName(commandInstance);
                    commands.put(commandName, commandInstance);
                }
            }
        } catch (Exception e) {
            OutputFormatter.printError("Automatic command discovery failed: " + e.getMessage());
        }
    }

    /**
     * Returns a copy of {@code args} safe to log.
     *
     * @see SecretRedactor#redactArguments(String, String[])
     */
    private static String[] redactSensitiveArgs(String name, String[] args) {
        return SecretRedactor.redactArguments(name, args);
    }

    private String determineCommandName(Command command) {
        // Honor an explicit picocli @Command(name=...) so the registry key matches the advertised
        // command name (e.g. PlanModeCommand -> "plan", UberModeCommand -> "ubermode") instead of
        // the class-derived "planmode" that nothing dispatches. Fall back to the
        // class-derived name (XxxCommand -> "xxx") when there is no usable annotation name.
        picocli.CommandLine.Command annotation =
                command.getClass().getAnnotation(picocli.CommandLine.Command.class);
        if (annotation != null && annotation.name() != null && !annotation.name().isEmpty()
                && !"<main class>".equals(annotation.name())) {
            return annotation.name().toLowerCase();
        }
        String className = command.getClass().getSimpleName();
        if (className.endsWith("Command")) {
            className = className.substring(0, className.length() - "Command".length());
        }
        return className.toLowerCase();
    }

    /**
     * Executes a command by name with the provided arguments, falling back to {@code chat} when the
     * name is not registered.
     *
     * @param name The name of the command
     * @param args The arguments for the command
     * @return The exit code of the command
     */
    public int executeCommand(String name, String[] args) {
        return executeCommand(name, args, true);
    }

    /**
     * Executes a command by name with the provided arguments.
     *
     * <p>{@code allowChatFallback} decides what happens when {@code name} is not a registered
     * command. It must be {@code false} whenever the user named the command <em>explicitly</em> --
     * i.e. wrote it with a leading {@code /} (see
     * {@link com.eonmux.cadetcoder.commands.InputRouter}). Silently forwarding an explicit command to
     * the model is the wrong answer twice over: a typo such as {@code /raed pom.xml} became a billed
     * LLM round trip instead of a one-line "unknown command", and the user never learned the name was
     * wrong. It stays {@code true} for bare input, where falling back to chat <em>is</em> the intent.</p>
     *
     * @param name              The name of the command
     * @param args              The arguments for the command
     * @param allowChatFallback whether an unregistered name should be forwarded to {@code chat}
     * @return The exit code of the command
     */
    public int executeCommand(String name, String[] args, boolean allowChatFallback) {
        if (name == null || name.trim().isEmpty()) {
            OutputFormatter.printError("No command given.");
            return 1;
        }
        if (args == null) {
            args = new String[0];
        }
        String  commandKey = name.trim().toLowerCase();
        Command command    = commands.get(commandKey);
        if (command == null) {
            if (!allowChatFallback) {
                return reportUnknownCommand(commandKey);
            }
            // Try to fall back to chat command
            command = commands.get("chat");
            if (command == null) {
                // If chat command is also not available, return error
                return reportUnknownCommand(commandKey);
            }
            // If falling back to chat, prepend the original command name to args
            String[] newArgs = new String[args.length + 1];
            newArgs[0] = name;
            System.arraycopy(args, 0, newArgs, 1, args.length);
            args = newArgs;
        }

        if (asksHowItIsUsed(args)) {
            return printUsageOf(command);
        }

        // Log command execution
        DebugLogger debugLogger = DebugLogger.getInstance();
        SessionLogger sessionLogger = SessionLogger.getInstance();
        
        // Redact sensitive arguments before logging so a misused "login <provider> <apikey>" can
        // never persist the key to the debug/session logs (the key must only be read at a prompt).
        String[] loggableArgs = redactSensitiveArgs(name, args);
        debugLogger.logCommand(name, loggableArgs);
        sessionLogger.logCommandStart(name, loggableArgs);
        
        long startTime = System.currentTimeMillis();

        try {
            // Check if command supports interruption
            if (command instanceof InterruptibleCommand) {
                InterruptibleCommand interruptibleCommand = (InterruptibleCommand) command;
                int exitCode = executeInterruptibleCommand(interruptibleCommand, args);
                long duration = System.currentTimeMillis() - startTime;
                
                debugLogger.logResponse(name, exitCode, duration);
                sessionLogger.logCommandEnd(name, exitCode, duration);
                return exitCode;
            } else {
                int  exitCode = command.execute(args);
                long duration = System.currentTimeMillis() - startTime;
                
                debugLogger.logResponse(name, exitCode, duration);
                sessionLogger.logCommandEnd(name, exitCode, duration);
                return exitCode;
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            
            debugLogger.logResponse(name, 1, duration);
            debugLogger.error("CommandRegistry", "Command execution failed: " + name, e);
            
            sessionLogger.logCommandEnd(name, 1, duration);
            sessionLogger.logError("CommandRegistry", "Command execution failed: " + name, e);

            // Delegate exception handling to ErrorHandler
            ErrorHandler.getInstance().handleException(e);
            return 1;
        }
    }
    
    /**
     * Reports an unregistered command name with concrete next steps.
     *
     * <p>The old message dumped every registered name on one unsorted line, which is 40+ words of
     * noise for what is almost always a typo. This names the closest matches instead and points at
     * {@code /help}.</p>
     *
     * @param commandKey the lower-cased name that was not found
     * @return {@code 1}, always -- an unknown command is a failure
     */
    /**
     * Whether the arguments are a question about how the command is used rather than arguments.
     *
     * <h2>Why this is asked here and not in each command</h2>
     *
     * <p>Only the three picocli-parsed commands answered {@code --help}; everywhere else it was
     * just another argument, so the first thing anyone types at an unfamiliar command did something
     * else with it. {@code chat --help} sent the two words to the model and billed the call,
     * {@code websearch --help} searched the web for them, {@code bash --help} offered to run
     * {@code --help} as a shell command, and {@code plan --help} opened an interactive planning
     * session. Every command already owns one description of its own usage -- the text
     * {@code help &lt;command&gt;} prints -- so the answer existed in all of them and was reachable
     * in almost none.</p>
     *
     * <p>This is the one point every invocation passes through, from the command line and from the
     * shell's prompt alike, so a command written later answers the question without having to
     * remember to.</p>
     *
     * <p>Only the FIRST argument counts. Further along, the word belongs to the command:
     * {@code runs show --help} asks for a run by that name and is answered by saying there is none,
     * rather than by a usage block that ignores what was asked.</p>
     *
     * @param args the arguments the command was given
     * @return whether to print the usage instead of running the command
     */
    private static boolean asksHowItIsUsed(String[] args) {
        return args.length > 0 && ("-h".equals(args[0]) || "--help".equals(args[0]));
    }

    /**
     * Prints one command's usage, spelled for the surface it is being read on.
     *
     * @param command the command that was asked about
     * @return {@code 0} -- being told how something is used is not a failure
     */
    private static int printUsageOf(Command command) {
        String usage = command.getUsage();
        if (usage == null || usage.trim().isEmpty()) {
            OutputFormatter.printError("That command does not say how it is used.");
            return 1;
        }
        OutputFormatter.println(com.eonmux.cadetcoder.commands.CommandUsage.render(usage));
        return 0;
    }

    /** @return the registered names, less the ones a model is refused for starting a loop */
    private SortedSet<String> commandsAModelMayRun() {
        SortedSet<String> names = new TreeSet<>();
        for (String name : commands.keySet()) {
            if (!com.eonmux.cadetcoder.commands.ModelDispatch.startsALoop(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private int reportUnknownCommand(String commandKey) {
        OutputFormatter.printError("Unknown command: " + commandKey);
        // Spelled for the surface the reader is on. A suggestion the user cannot type as written
        // is barely a suggestion: "/read" is wrong on the command line, "cadet read" is wrong at
        // the shell prompt, and this message is reached from both.
        String prefix = com.eonmux.cadetcoder.ui.OutputRouter.getInstance().commandPrefix();
        java.util.List<String> suggestions =
                com.eonmux.cadetcoder.commands.InputRouter.suggest(commandKey, commands.keySet(), 3);
        if (!suggestions.isEmpty()) {
            StringBuilder message = new StringBuilder("Did you mean: ");
            for (int i = 0; i < suggestions.size(); i++) {
                if (i > 0) {
                    message.append(", ");
                }
                message.append(prefix).append(suggestions.get(i));
            }
            message.append('?');
            OutputFormatter.printInfo(message.toString());
        }
        if (com.eonmux.cadetcoder.commands.ModelDispatch.isModelDriven()) {
            // A model's action is never sent to the AI, and /help is not an action it can take:
            // told to run it, a model ran `help` and `/help` and got "Unknown command" for both.
            OutputFormatter.printInfo(commands.isEmpty()
                    ? "No commands are available at all; the program cannot read its own classes."
                    : "The commands there are: " + String.join(", ", commandsAModelMayRun()) + ".");
            return 1;
        }
        OutputFormatter.printInfo("Run '" + prefix + "help' to list every command. Anything that is "
                + "not a command name is sent to the AI.");
        return 1;
    }

    /**
     * How long an interrupted command is given to stop before the user is told it has not.
     *
     * <p>A command that polls the interruption context notices within one poll of its own; this is
     * long enough for several, and short enough that a command which ignores interruption entirely
     * does not hold the prompt.</p>
     */
    private static final long INTERRUPT_GRACE_MILLIS = 2000L;

    /**
     * Runs a command on its own thread and watches for the user asking it to stop.
     *
     * <p>Static and package-private for the same reason {@link #awaitStop} is: the decision it
     * makes is about a command and a signal, not about a registry, and it has to be exercisable
     * from a thread that has never been near the shell -- which is exactly the case it used to get
     * wrong.</p>
     *
     * @param command the command to run
     * @param args    its arguments
     * @return the command's exit code, or {@link ExitCode#INTERRUPTED} if the user interrupted it
     */
    static int executeInterruptibleCommand(InterruptibleCommand command, String[] args) {
        // Set up interruption context
        InterruptionContext context = new InterruptionContext();
        command.setInterruptionContext(context);
        
        // Execute command in a separate thread, which is still the caller's work: see
        // ThreadHandover for what would otherwise be left behind at this line.
        Thread commandThread = new Thread(ThreadHandover.carrying(() -> {
            try {
                int exitCode = command.execute(args);
                context.setExitCode(exitCode);
            } catch (Exception e) {
                context.setException(e);
            } finally {
                context.setCompleted(true);
            }
        }));
        
        commandThread.setDaemon(true);
        commandThread.start();
        
        // Monitor for interruption requests
        while (!context.isCompleted()) {
            // Check for interruption request from InteractiveShell
            if (InterruptSignal.isRequested()) {
                context.setInterrupted(true);
                commandThread.interrupt();
                reportInterrupt(awaitStop(commandThread, INTERRUPT_GRACE_MILLIS));
                return ExitCode.INTERRUPTED;
            }
            
            try {
                Thread.sleep(100); // Check every 100ms
            } catch (InterruptedException e) {
                context.setInterrupted(true);
                commandThread.interrupt();
                reportInterrupt(awaitStop(commandThread, INTERRUPT_GRACE_MILLIS));
                // sleep() consumed this thread's interrupt; hand it back rather than swallowing it.
                Thread.currentThread().interrupt();
                return ExitCode.INTERRUPTED;
            }
        }
        
        if (context.hasException()) {
            throw new RuntimeException(context.getException());
        }
        
        return context.getExitCode();
    }
    
    /**
     * Waits for an interrupted command's thread to actually finish.
     *
     * <p>{@link Thread#interrupt()} sets a flag; it does not stop a directory walk, a read on a
     * process pipe, or a request in flight. Returning the moment the flag was set told the shell
     * the command was over while it was still writing output and still holding the singleton
     * {@code Command} instance the next line would reuse.</p>
     *
     * <p>Bounded, because a thread that will not stop cannot be made to from inside the JVM. When
     * the grace is spent the caller says so rather than hanging the shell on a command it cannot
     * end.</p>
     *
     * @param commandThread the thread running the command
     * @param graceMillis   how long to wait before giving up
     * @return whether the thread finished within the grace period
     */
    static boolean awaitStop(Thread commandThread, long graceMillis) {
        long deadline = System.currentTimeMillis() + graceMillis;
        while (commandThread.isAlive()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                commandThread.join(remaining);
            } catch (InterruptedException e) {
                // Hand this thread's own interrupt back to whoever is above us and answer with
                // what is actually true right now.
                Thread.currentThread().interrupt();
                return !commandThread.isAlive();
            }
        }
        return true;
    }

    /**
     * Says what the interrupt achieved.
     *
     * @param stopped whether the command's thread finished before the grace period ran out
     */
    private static void reportInterrupt(boolean stopped) {
        if (stopped) {
            OutputFormatter.printWarning("Command interrupted by user request");
        } else {
            OutputFormatter.printWarning("Command interrupted, but it has not stopped yet and is "
                    + "still running in the background. Press Ctrl+Q to quit if it will not stop.");
        }
    }

    /**
     * Retrieves the registered commands.
     *
     * <p>The returned map is a read-only view. It used to be the registry's own map, so anything
     * holding it could add or drop a command for the whole process through what reads as an
     * accessor -- and tests did exactly that. Registration is a real operation and now has real
     * methods; see {@link #register} and {@link #unregister}.</p>
     *
     * @return an unmodifiable map of command names to Command instances
     */
    public Map<String, Command> getCommands() {
        return Collections.unmodifiableMap(commands);
    }

    /**
     * Registers a command under {@code name}, replacing any command already there.
     *
     * @param name    the registry key, lower-cased by the caller's convention
     * @param command the command to dispatch for that name
     * @return the command previously registered under that name, or {@code null}
     */
    public Command register(String name, Command command) {
        return commands.put(name, command);
    }

    /**
     * Removes the command registered under {@code name}.
     *
     * @param name the registry key
     * @return the command that was removed, or {@code null} if there was none
     */
    public Command unregister(String name) {
        return commands.remove(name);
    }

    /**
     * Retrieves a command by its name.
     *
     * @param name The name of the command to retrieve
     * @return The command if found, or null if no command is registered with the given name
     */
    public Command getCommand(String name) {
        return commands.get(name);
    }

    /**
     * Command interface to be implemented by all commands.
     */
    public interface Command {
        /**
         * Executes the command with the given arguments.
         *
         * @param args The arguments for the command
         * @return The exit code of the command
         */
        int execute(String[] args);

        /**
         * What this command does, in one short line.
         *
         * <p>Read from the command's {@code @Command} annotation, which is also where the registry
         * takes its name from. A command that declared the text twice -- once in the annotation and
         * once in an override -- had two descriptions that could disagree, and seven of them did:
         * the reader saw whichever one the surface they were on happened to consult.</p>
         *
         * <p>The search walks up the class hierarchy because picocli's annotation is not
         * {@code @Inherited}: a subclass of a command -- a test double that fixes one behaviour,
         * say -- is still that command and still describes itself the same way.</p>
         *
         * @return the description, or an empty string if no class in the hierarchy is annotated
         */
        default String getDescription() {
            for (Class<?> type = getClass(); type != null; type = type.getSuperclass()) {
                picocli.CommandLine.Command annotation =
                        type.getAnnotation(picocli.CommandLine.Command.class);
                if (annotation != null) {
                    return String.join(" ", annotation.description());
                }
            }
            return "";
        }

        /**
         * Provides usage information for the command.
         *
         * @return The command usage
         */
        String getUsage();
    }
    
    /**
     * Interface for commands that support interruption
     */
    public interface InterruptibleCommand extends Command {
        /**
         * Sets the interruption context for this command
         */
        void setInterruptionContext(InterruptionContext context);

        /**
         * Whether the user has taken this command back.
         *
         * @return whether to stop at the next point it is safe to
         */
        default boolean shouldInterrupt() {
            return stopWasAsked(null);
        }

        /**
         * The one rule for "has this been taken back", wherever it is asked.
         *
         * <h2>Why all three are consulted</h2>
         *
         * <p>The same event arrives by three routes and no single one of them sees it first every
         * time. {@link InterruptSignal} is set the instant the user presses the key, and is the only
         * one of the three that is true immediately. The context is written by the registry's
         * monitor, which polls, so it lags by up to its poll interval. The thread's own flag is set
         * when something blocking has to be unwedged, and is the only route a command reached
         * outside the registry ever gets.</p>
         *
         * <p>Answered from one of them alone, a command's checkpoints stop meaning what they were
         * written to mean. {@code bash} asks before it starts a process precisely so that a command
         * taken back is never run -- but read off the context alone, that question answered "no" for
         * the whole gap between the key press and the next poll, and the process was started anyway.
         * Read off the thread flag alone, it answered "no" until something happened to interrupt the
         * thread, which for a command busy in a directory walk is never.</p>
         *
         * @param given the context the registry handed the command, or {@code null} if it has none
         * @return whether the user has asked for this to stop
         */
        static boolean stopWasAsked(InterruptionContext given) {
            return InterruptSignal.isRequested()
                   || (given != null && given.isInterrupted())
                   || Thread.currentThread().isInterrupted();
        }
    }
    
    /**
     * Context for managing command interruption
     */
    public static class InterruptionContext {
        private volatile boolean interrupted = false;
        private volatile boolean completed = false;
        private volatile int exitCode = 0;
        private volatile Exception exception = null;
        
        public boolean isInterrupted() {
            return interrupted;
        }
        
        public void setInterrupted(boolean interrupted) {
            this.interrupted = interrupted;
        }
        
        public boolean isCompleted() {
            return completed;
        }
        
        public void setCompleted(boolean completed) {
            this.completed = completed;
        }
        
        public int getExitCode() {
            return exitCode;
        }
        
        public void setExitCode(int exitCode) {
            this.exitCode = exitCode;
        }
        
        public Exception getException() {
            return exception;
        }
        
        public void setException(Exception exception) {
            this.exception = exception;
        }
        
        public boolean hasException() {
            return exception != null;
        }
    }
}
