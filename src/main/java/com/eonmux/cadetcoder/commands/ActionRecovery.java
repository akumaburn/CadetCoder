package com.eonmux.cadetcoder.commands;

import java.util.Map;

/**
 * Mending an action that failed for a reason the loop can put right itself.
 *
 * <h2>Why only two failures are mendable</h2>
 *
 * <p>Mending an action means acting on a guess about what the model meant, so it is worth doing only
 * where the guess is nearly certain. Two failures qualify: a file that is not where the model said
 * but is somewhere this conversation has already seen, and a {@code write} given a path and no
 * content, which means the empty file it named. Everything else -- a refused path, a missing search
 * pattern, a file the process may not read -- has no answer that can be inferred from the failure,
 * and inventing one produces an action the user never asked for.</p>
 *
 * <h2>What used to be here</h2>
 *
 * <p>A recovery arm per error kind and per command, of which all but two returned {@code null}
 * unconditionally. The scaffolding read as though permission errors and malformed greps were
 * handled; nothing handled them, and a reader had to follow four calls to find that out.</p>
 */
final class ActionRecovery {

    /** The prefix a validation failure uses to name the command whose arguments were wrong. */
    private static final String INVALID_ARGUMENTS = "invalid_arguments_for_";

    private final LoggingCommandSupport log;
    private final ProjectFileSearch     files;
    private final FilenameSimilarity    names;

    /**
     * @param log where to record what was mended and what was not
     */
    ActionRecovery(LoggingCommandSupport log) {
        this.log   = log;
        this.files = new ProjectFileSearch(log);
        this.names = new FilenameSimilarity(log);
    }

    /**
     * Whether a failure is one of the kinds anything here can put right.
     *
     * @param action       the action that failed
     * @param errorType    which kind of failure it was
     * @param errorDetails what specifically went wrong
     * @return {@code true} when {@link #mended} is worth calling
     */
    static boolean isMendable(ChatCommand.AIAction action, String errorType, String errorDetails) {
        if (action == null || errorType == null) {
            return false;
        }
        return ActionOutcome.FILE_NOT_FOUND.equals(errorType)
               || (ActionOutcome.VALIDATION.equals(errorType)
                   && errorDetails != null && errorDetails.startsWith(INVALID_ARGUMENTS));
    }

    /**
     * The same action with the mistake corrected.
     *
     * @param action       the action that failed
     * @param errorType    which kind of failure it was
     * @param errorDetails what specifically went wrong
     * @param context      the conversation, which knows what files it has already found
     * @return the corrected action, or {@code null} when nothing here can correct it
     */
    ChatCommand.AIAction mended(ChatCommand.AIAction action, String errorType, String errorDetails,
                                ChatContext context) {
        if (!isMendable(action, errorType, errorDetails)) {
            return null;
        }
        try {
            if (ActionOutcome.FILE_NOT_FOUND.equals(errorType)) {
                return fileFoundElsewhere(action, context);
            }
            return writeWithEmptyContent(action, errorDetails);
        } catch (Exception e) {
            log.logWarning("Error Recovery", "Failed to recover from error: " + e.getMessage());
            return null;
        }
    }

    /**
     * The action pointed at a file that really is there.
     *
     * <p>What this conversation has already found comes first: a path proved to exist a moment ago
     * beats one the project structure suggests. The walk is the fallback.</p>
     */
    private ChatCommand.AIAction fileFoundElsewhere(ChatCommand.AIAction action, ChatContext context) {
        if (action.arguments == null || action.arguments.length == 0) {
            return null;
        }
        String filePath = action.arguments[0];
        if (filePath == null || filePath.isEmpty()) {
            return null;
        }
        String fileName = names.baseFilename(filePath);
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        Map<String, String> foundFiles = context.getFoundFiles();
        if (foundFiles != null) {
            for (Map.Entry<String, String> entry : foundFiles.entrySet()) {
                if (names.areSimilar(fileName, entry.getKey())) {
                    log.logDebug("Error Recovery",
                            "Found similar file: " + entry.getValue() + " for " + filePath);
                    return pointedAt(action, entry.getValue());
                }
            }
        }

        String found = files.locate(fileName);
        if (found != null && !found.isEmpty()) {
            log.logDebug("Error Recovery", "Found file by searching: " + found + " for " + filePath);
            return pointedAt(action, found);
        }
        return null;
    }

    /**
     * A {@code write} that named a path and nothing else, given the empty content it meant.
     *
     * <p>Only for {@code write}, and only when the failure named {@code write}: a validation failure
     * reported for one command must not rewrite another.</p>
     */
    private static ChatCommand.AIAction writeWithEmptyContent(ChatCommand.AIAction action,
                                                              String errorDetails) {
        if (action.command == null
            || !action.command.equals(errorDetails.substring(INVALID_ARGUMENTS.length()))
            || !"write".equals(action.command)
            || action.arguments == null || action.arguments.length != 1) {
            return null;
        }
        return new ChatCommand.AIAction(action.command,
                                        new String[] {action.arguments[0], ""},
                                        action.explanation + " (fixed args)");
    }

    /** The same action, aimed at a different file. */
    private static ChatCommand.AIAction pointedAt(ChatCommand.AIAction action, String filePath) {
        String[] arguments = action.arguments.clone();
        arguments[0] = filePath;
        return new ChatCommand.AIAction(action.command, arguments,
                                        action.explanation + " (recovered)");
    }
}
