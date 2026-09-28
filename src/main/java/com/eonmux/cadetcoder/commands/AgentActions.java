package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ai.parsing.ActionBlockParser;
import com.eonmux.cadetcoder.ai.parsing.ParsedResponse;
import com.eonmux.cadetcoder.ai.parsing.ParsingContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The next step, read out of what the model just said.
 *
 * <h2>What is accepted, in priority order</h2>
 *
 * <ol>
 *   <li>a completion signal: a line starting with {@code TASK COMPLETE:} or {@code TASK FAILED:};</li>
 *   <li>a properly delimited {@code ACTION_START}/{@code ACTION_END} block, read by the shared
 *       parser;</li>
 *   <li>a {@code COMMAND:} line, with {@code ARGS:} either inline on the same line or on a
 *       following line;</li>
 *   <li>a bare leading {@code complete <summary>}.</li>
 * </ol>
 *
 * <h2>Why prose is not a command</h2>
 *
 * <p>A reply with none of those markers reads as nothing at all, so the caller can warn and
 * re-prompt with the format reminder. Taking the first word of arbitrary prose as a command means a
 * reply like "Sure, I will help" runs {@code sure}.</p>
 */
final class AgentActions {

    private AgentActions() {}

    /**
     * The action the model's reply proposes.
     *
     * @param response the model's reply
     * @param warn     where to report a reply that could not be read
     * @return the action, or {@code null} when the reply proposes none
     */
    static AgentAction from(String response, Consumer<String> warn) {
        if (response == null) {
            return null;
        }
        try {
            String[] lines = response.split("\n");

            AgentAction completion = completionSignal(lines, response);
            if (completion != null) {
                return completion;
            }

            // A properly delimited ACTION block goes to the SHARED parser, which is the only one
            // that understands ARGS_BEGIN/ARGS_END and the per-command argument mapping. The line
            // scan below reads a single ARGS: line and stops at the next label, so a write payload
            // or a multiedit's EDIT blocks -- both advertised in the agent's catalog, both
            // inherently multi-line -- arrived empty. Chat has parsed this correctly all along.
            AgentAction delimited = fromDelimitedBlock(response, warn);
            if (delimited != null) {
                return delimited;
            }

            AgentAction commandLine = fromCommandLine(lines, response);
            if (commandLine != null) {
                return commandLine;
            }

            String[] words = response.trim().split("\\s+", 2);
            if (words.length > 0 && words[0].equalsIgnoreCase("complete")) {
                return new AgentAction("complete",
                                       words.length > 1 ? words[1].split("\\s+") : new String[0],
                                       response);
            }
        } catch (Exception e) {
            warn.accept("Failed to parse AI response '" + response + "': " + e.getMessage());
            OutputFormatter.printWarning("Failed to parse action: " + e.getMessage());
        }
        return null;
    }

    /** A {@code TASK COMPLETE:} or {@code TASK FAILED:} line anywhere in the reply. */
    private static AgentAction completionSignal(String[] lines, String response) {
        for (String line : lines) {
            String trimmed = line.trim();
            String upper   = trimmed.toUpperCase();
            if (upper.startsWith("TASK COMPLETE:") || upper.startsWith("TASK FAILED:")) {
                return new AgentAction(
                        "complete",
                        CommandLineTokenizer.tokenize(trimmed.substring(trimmed.indexOf(':') + 1)),
                        response);
            }
        }
        return null;
    }

    /**
     * A {@code COMMAND:} line, with its arguments inline or on a following {@code ARGS:} line.
     *
     * @param lines    the reply, split into lines
     * @param response the reply as a whole, kept on the action
     * @return the action, or {@code null} when no such line is there
     */
    private static AgentAction fromCommandLine(String[] lines, String response) {
        for (int i = 0; i < lines.length; i++) {
            int marker = lines[i].toUpperCase().indexOf("COMMAND:");
            if (marker < 0) {
                continue;
            }
            String   rest   = lines[i].substring(marker + "COMMAND:".length()).trim();
            String[] inline = rest.split("\\s+ARGS:\\s*|\\s+args:\\s*", 2);
            if (inline[0].trim().isEmpty()) {
                continue;
            }
            String[] cmdTokens = CommandLineTokenizer.tokenize(inline[0].trim());

            List<String> args = new ArrayList<>();
            // Any extra tokens on the COMMAND line are treated as args.
            for (int k = 1; k < cmdTokens.length; k++) {
                args.add(cmdTokens[k]);
            }
            if (inline.length > 1 && !inline[1].trim().isEmpty()) {
                Collections.addAll(args, CommandLineTokenizer.tokenize(inline[1].trim()));
            } else {
                appendFollowingArgs(lines, i, args);
            }
            return new AgentAction(cmdTokens[0], args.toArray(new String[0]), response);
        }
        return null;
    }

    /** The {@code ARGS:} line that follows a {@code COMMAND:} line, if the block has one. */
    private static void appendFollowingArgs(String[] lines, int commandLine, List<String> args) {
        for (int j = commandLine + 1; j < lines.length; j++) {
            String upper = lines[j].trim().toUpperCase();
            if (upper.startsWith("ARGS:")) {
                String value = lines[j].trim().substring("ARGS:".length()).trim();
                if (!value.isEmpty()) {
                    Collections.addAll(args, CommandLineTokenizer.tokenize(value));
                }
                return;
            }
            if (upper.startsWith("ACTION_END") || upper.startsWith("COMMAND:")) {
                return;
            }
        }
    }

    /**
     * Reads an {@code ACTION_START}/{@code ACTION_END} block with the shared block parser.
     *
     * <p>Returns {@code null} when the reply carries no block markers, so the more forgiving line
     * scan still handles the shape models most often produce when they drop them. Delegating rather
     * than teaching the local scanner about {@code ARGS_BEGIN} is deliberate: the shared parser also
     * carries the per-command argument mapping (write's verbatim payload, multiedit's edit blocks,
     * todowrite's flags), all of which the local scanner lacks entirely.</p>
     *
     * @param response the model's reply
     * @param warn     where to report a parser that declined the reply
     * @return the first action in the block, or {@code null} if there is none to take
     */
    private static AgentAction fromDelimitedBlock(String response, Consumer<String> warn) {
        if (!response.toUpperCase().contains("ACTION_START")) {
            return null;
        }
        try {
            ParsedResponse parsed = new ActionBlockParser()
                    .parse(response, new ParsingContext.Builder(response).build());
            if (parsed == null || parsed.getActions() == null || parsed.getActions().isEmpty()) {
                return null;
            }
            ChatCommand.AIAction legacy = parsed.getActions().get(0).toLegacyAction();
            if (legacy == null || legacy.command == null || legacy.command.isBlank()) {
                return null;
            }
            return new AgentAction(legacy.command,
                                   legacy.arguments == null ? new String[0] : legacy.arguments,
                                   response);
        } catch (RuntimeException e) {
            // A parser failure is not an agent failure: fall through to the line scan rather than
            // losing a step the simpler path could still have handled.
            warn.accept("Shared block parser declined the response: " + e.getMessage());
            return null;
        }
    }
}
