package com.eonmux.cadetcoder.harness.cadet;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.commands.CommandAliases;
import com.eonmux.cadetcoder.commands.CommandLineTokenizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One command, read out of whatever shape the model wrote it in.
 *
 * <h2>Why there is exactly one reader</h2>
 *
 * <p>An action is read twice before it happens: once by the commit gate, deciding whether it is
 * allowed, and once by the environment, doing it. Two readers can disagree, and the disagreement is
 * one-directional -- the gate sees a command name it has never heard of and lets it through as the
 * safe middle, while the environment splits the same string properly and runs whatever was really
 * in there. So both go through here.</p>
 *
 * <h2>Why a loose shape is read rather than refused</h2>
 *
 * <p>These arrive from a language model. The canonical shape keeps the name and the arguments apart,
 * but the shape a model reaches for first is the whole line in one string, and one argument written
 * as a bare string rather than a list of one is close behind. Correcting punctuation costs a step
 * each time and teaches nothing, so every shape that names something unambiguously is read. What is
 * refused is only what names nothing at all.</p>
 *
 * <h2>Why the verb is the canonical one</h2>
 *
 * <p>The same argument, applied to the word rather than the punctuation. {@code cat} means
 * {@code read} and {@code run} means {@code bash} on the chat and agent paths, which both ask
 * {@link CommandAliases}; this path did not, so a harness run that wrote {@code cat pom.xml} was
 * told there is no such command and spent a step on a synonym its siblings accept. Worse, the name
 * is what the gate reads: {@code CommandEffects} recognises the shell by the word {@code bash}, so
 * {@code run rm -rf build} was weighed as an ordinary costly step with its line never examined, and
 * a narrowed {@code security.allowedActions} refused the synonyms of the very commands it
 * permits.</p>
 *
 * @param name the command to run, trimmed; empty when the action named none
 * @param args what to hand it, in order
 */
public record CommandInvocation(String name, List<String> args) {

    /** What an action that names no command reads as. */
    public static final CommandInvocation NONE = new CommandInvocation("", List.of());

    /** The action field naming the command, or the whole line. */
    private static final String COMMAND = "command";

    /** The action field holding the arguments, when they were kept apart from the name. */
    private static final String ARGS = "args";

    public CommandInvocation {
        name = name == null ? "" : name.trim();
        args = List.copyOf(args);
    }

    /**
     * Reads an action.
     *
     * <p>The command field is always tokenized, and any separate {@code args} are appended to
     * whatever the line already carried. That is what makes every shape one shape: a name with its
     * arguments beside it, a whole line on its own, and the muddle of both together all arrive here
     * as the same invocation, and none of them silently loses an argument.</p>
     *
     * @param action the action value, in any shape
     * @return what it asks for, or {@link #NONE} if it asks for nothing runnable
     */
    public static CommandInvocation from(Object action) {
        if (!Json.has(action, COMMAND) || !(Json.at(action, COMMAND) instanceof String)) {
            return NONE;
        }
        String[] line = CommandLineTokenizer.tokenize((String) Json.at(action, COMMAND));
        if (line.length == 0) {
            return NONE;
        }
        List<String> args = new ArrayList<>(Arrays.asList(line).subList(1, line.length));
        if (Json.has(action, ARGS)) {
            args.addAll(texts(Json.at(action, ARGS)));
        }
        return new CommandInvocation(CommandAliases.canonicalize(line[0]), args);
    }

    /** Whether this names a command at all. */
    public boolean named() {
        return !name.isEmpty();
    }

    /** The arguments as a command takes them. */
    public String[] argv() {
        return args.toArray(new String[0]);
    }

    /**
     * The whole invocation written back out as one line.
     *
     * <p>Arguments holding whitespace are quoted, so the line the observation reports is the line
     * that would run again if it were handed back.</p>
     *
     * @return the line, or empty when nothing was named
     */
    public String line() {
        StringBuilder written = new StringBuilder(name);
        for (String arg : args) {
            written.append(' ').append(quoted(arg));
        }
        return written.toString();
    }

    private static String quoted(String arg) {
        return arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace) ? "\"" + arg + "\""
                                                                              : arg;
    }

    /**
     * Arguments as text, whatever they were written as.
     *
     * <p>A model that writes {@code "args": ["notes.txt", 40]} means the fortieth line, not a
     * number, and a model that writes a single argument without its list means that one argument.
     * A {@code null} among them is an argument it did not fill in, which is an empty one rather than
     * the four letters {@code null}.</p>
     */
    private static List<String> texts(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            return List.of(String.valueOf(value));
        }
        List<String> written = new ArrayList<>();
        for (Object element : (List<?>) value) {
            written.add(element == null ? "" : String.valueOf(element));
        }
        return written;
    }
}
