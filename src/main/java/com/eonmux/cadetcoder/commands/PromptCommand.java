package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.prompts.PromptTemplateEngine;
import com.eonmux.cadetcoder.security.SecurityValidator;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Manage AI prompt templates: list, show, edit, reset, reload.
 *
 * <p>This command is dispatched via {@link CommandRegistry.Command#execute(String[])}
 * with a hand-rolled argument parser (no picocli binding at runtime), so the
 * accepted contract is:
 *
 * <pre>
 *   prompt [list|show|edit|reset|reload] [name] [-c|--content &lt;text&gt;] [-f|--file &lt;path&gt;]
 * </pre>
 *
 * The positional name may appear before or after the options; option VALUES are
 * consumed positionally even when they happen to look like another option.
 *
 * <p>The class-level {@link Command} annotation is REQUIRED: this class is
 * registered as a picocli subcommand of the top-level CLI (see {@code Main}),
 * and picocli reads the subcommand name from it. Only the per-field
 * {@code @Parameters}/{@code @Option} bindings were dead (superseded by the
 * hand-rolled {@link #parseArgs} parser) and were removed.
 */
@Command(name = "prompt", description = "Show or edit the system prompts the AI is sent")
public class PromptCommand implements CommandRegistry.Command {

    /** Default action when none is supplied. */
    private static final String DEFAULT_ACTION = "list";

    /** Only simple names are allowed, preventing path traversal into the prompts dir. */
    private static final Pattern VALID_PROMPT_NAME = Pattern.compile("[A-Za-z0-9_-]+");

    /** Sentinel prefix the engine returns when no template exists for a name. */
    private static final String NO_TEMPLATE_PREFIX = "No template found for prompt:";

    @Override
    public int execute(String[] args) {
        ParsedArgs parsed = parseArgs(args);
        String     action = parsed.action.toLowerCase();
        if (action.equals("edit") || action.equals("reset")) {
            Integer refused = ModelDispatch.refuseSetupChange("prompt " + action);
            if (refused != null) {
                return refused;
            }
        }

        try {
            switch (action) {
                case "list":
                    return listPrompts();
                case "show":
                    return showPrompt(parsed.promptName);
                case "edit":
                    return editPrompt(parsed.promptName, parsed.content, parsed.contentFile);
                case "reset":
                    return resetPrompt(parsed.promptName);
                case "reload":
                    return reloadPrompts();
                default:
                    OutputFormatter.printError("Unknown action: " + parsed.action);
                    OutputFormatter.printInfo("Available actions: list, show, edit, reset, reload");
                    return 1;
            }
        } catch (Exception e) {
            OutputFormatter.printError("Error during " + parsed.action + " action: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Immutable result of parsing the raw argument array. Each field is resolved
     * exactly once so callers never mutate parser state.
     */
    private static final class ParsedArgs {
        final String action;
        final String promptName;
        final String content;
        final String contentFile;

        ParsedArgs(String action, String promptName, String content, String contentFile) {
            this.action      = action;
            this.promptName  = promptName;
            this.content     = content;
            this.contentFile = contentFile;
        }
    }

    /**
     * Parse the raw arguments into an immutable {@link ParsedArgs}.
     *
     * <p>The first non-option token is the action, the second non-option token is
     * the prompt name (so the name may appear before or after options). When an
     * option (-c/--content, -f/--file) is seen, the very next token is consumed as
     * its VALUE unconditionally, even if that value itself starts with "-"; this
     * stops a content/path value that looks like a flag from being mis-parsed.
     */
    private ParsedArgs parseArgs(String[] args) {
        String action      = DEFAULT_ACTION;
        String promptName  = "";
        String content     = null;
        String contentFile = null;
        int    positional  = 0;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }

            if (arg.equals("-c") || arg.equals("--content")) {
                if (i + 1 < args.length) {
                    content = args[++i];
                }
                continue;
            }
            if (arg.equals("-f") || arg.equals("--file")) {
                if (i + 1 < args.length) {
                    contentFile = args[++i];
                }
                continue;
            }
            if (arg.startsWith("-")) {
                // Unknown flag: ignore so it is not mistaken for a positional value.
                continue;
            }

            // Positional token: first is action, second is prompt name.
            if (positional == 0) {
                action = arg;
            } else if (positional == 1) {
                promptName = arg;
            }
            positional++;
        }

        return new ParsedArgs(action, promptName, content, contentFile);
    }

    private int listPrompts() {
        OutputFormatter.printHeader("Available Prompt Templates");

        Map<String, String> prompts = PromptTemplateEngine.getInstance().listAvailablePrompts();

        // Group by source
        OutputFormatter.printSubheader("Default Prompts");
        prompts.entrySet().stream()
               .filter(e -> e.getValue().equals("default"))
               .forEach(e -> OutputFormatter.printInfo("  " + e.getKey()));

        OutputFormatter.printSubheader("User Overrides");
        boolean hasOverrides = false;
        for (Map.Entry<String, String> entry : prompts.entrySet()) {
            if (entry.getValue().equals("user")) {
                OutputFormatter.printInfo("  " + entry.getKey() + " (overridden)");
                hasOverrides = true;
            }
        }
        if (!hasOverrides) {
            OutputFormatter.printInfo("  (none)");
        }

        OutputFormatter.printInfo("\nUse '" + CommandUsage.prefix() + "prompt show <name>' to view a prompt");
        OutputFormatter.printInfo("Use '" + CommandUsage.prefix() + "prompt edit <name> -c \"content\"' to customize a prompt");

        return 0;
    }

    private int showPrompt(String promptName) {
        if (isBlank(promptName)) {
            OutputFormatter.printError("Please specify a prompt name");
            return 1;
        }

        // Validate the prompt name before resolving it to a file, matching edit/reset:
        // a name like "../../something" would otherwise escape the prompts directory.
        if (!VALID_PROMPT_NAME.matcher(promptName).matches()) {
            OutputFormatter.printError("Invalid prompt name: " + promptName);
            return 1;
        }

        String template = PromptTemplateEngine.getInstance().getRawPrompt(promptName);

        // Report absence as a failure instead of printing a null/sentinel body.
        if (isMissingTemplate(template)) {
            OutputFormatter.printError("Prompt not found: " + promptName);
            OutputFormatter.printInfo("Use '" + CommandUsage.prefix() + "prompt list' to see available prompts");
            return 1;
        }

        OutputFormatter.printHeader("Prompt Template: " + promptName);
        OutputFormatter.printInfo(template);

        return 0;
    }

    private int editPrompt(String promptName, String content, String contentFile) throws IOException {
        if (isBlank(promptName)) {
            OutputFormatter.printError("Please specify a prompt name");
            return 1;
        }

        if (!VALID_PROMPT_NAME.matcher(promptName).matches()) {
            OutputFormatter.printError("Invalid prompt name: " + promptName);
            return 1;
        }

        String newContent;

        if (content != null) {
            newContent = content;
        } else if (contentFile != null) {
            // Validate the path before reading so -f cannot read arbitrary files
            // (traversal, credential files, outside-project paths).
            if (!new SecurityValidator().isFileAccessAllowed(contentFile)) {
                OutputFormatter.printError("Access to file is not allowed: " + contentFile);
                return 1;
            }
            Path file = Paths.get(contentFile);
            if (!Files.exists(file)) {
                OutputFormatter.printError("File not found: " + contentFile);
                return 1;
            }
            newContent = Files.readString(file);
        } else {
            OutputFormatter.printError("Please provide content with -c or -f option");
            return 1;
        }

        // Save the custom prompt
        Map<String, String> metadata = new HashMap<>();
        metadata.put("source", "user");
        metadata.put("created", java.time.Instant.now().toString());

        PromptTemplateEngine.getInstance().saveCustomPrompt(promptName, newContent, metadata);

        OutputFormatter.printSuccess("Prompt '" + promptName + "' has been customized");
        OutputFormatter.printInfo("Use 'prompt reset " + promptName + "' to restore the default");

        return 0;
    }

    private int resetPrompt(String promptName) throws IOException {
        if (isBlank(promptName)) {
            OutputFormatter.printError("Please specify a prompt name");
            return 1;
        }

        if (!VALID_PROMPT_NAME.matcher(promptName).matches()) {
            OutputFormatter.printError("Invalid prompt name: " + promptName);
            return 1;
        }

        // Delete the user override via the engine so the SAME prompts directory
        // is used as list/save (avoids re-deriving a divergent path / NPE).
        boolean deleted;
        try {
            deleted = PromptTemplateEngine.getInstance().deleteCustomPrompt(promptName);
        } catch (IOException e) {
            OutputFormatter.printError("Failed to delete prompt override: " + e.getMessage());
            return 1;
        }

        if (deleted) {
            OutputFormatter.printSuccess("Prompt '" + promptName + "' reset to default");
            return 0;
        }

        OutputFormatter.printWarning("No user override found for prompt '" + promptName + "'");
        return 1;
    }

    private int reloadPrompts() {
        PromptTemplateEngine.getInstance().clearCache();
        OutputFormatter.printSuccess("Prompt template cache cleared. Templates will be reloaded on next use.");
        return 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }

    /**
     * Whether the engine returned no usable template: either null, empty, or the
     * engine's "No template found for prompt:" sentinel produced for unknown names.
     */
    private static boolean isMissingTemplate(String template) {
        return template == null
               || template.isEmpty()
               || template.startsWith(NO_TEMPLATE_PREFIX);
    }


    @Override
    public String getUsage() {
        return "prompt [list|show|edit|reset|reload] [prompt-name] [-c content | -f file]";
    }
}
