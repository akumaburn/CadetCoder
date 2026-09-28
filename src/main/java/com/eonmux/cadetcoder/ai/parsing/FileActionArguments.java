package com.eonmux.cadetcoder.ai.parsing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.eonmux.cadetcoder.ai.parsing.ActionArguments.addIntegerOption;
import static com.eonmux.cadetcoder.ai.parsing.ActionArguments.appendRemainingPositionals;
import static com.eonmux.cadetcoder.ai.parsing.ActionArguments.flagTrue;
import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.asString;
import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.asStringList;
import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.normalizedKeys;
import static com.eonmux.cadetcoder.ai.parsing.ParsedAction.scalarText;

/**
 * How the commands that read and change files want to be asked.
 *
 * <h2>Why an edit is translated rather than passed along</h2>
 *
 * <p>A model that names both the text to find and the text to put in its place has already done the
 * work {@code EditCommand} would ask a model to do. {@code EditCommand} joins whatever it is given
 * into one prose string and asks for SEARCH/REPLACE blocks; {@code MultiEditCommand} has exactly one
 * deterministic form and performs the replacement as stated. So a fully specified edit is rendered
 * into that form and dispatched there, and only an under-specified one is left to be worked out.</p>
 */
final class FileActionArguments {

    /** Parameter keys, normalised, naming the text an edit replaces. */
    private static final String[] OLD_STRING_KEYS =
            {"oldstring", "oldtext", "old", "from", "search", "find"};

    /** Parameter keys, normalised, naming the text an edit puts in its place. */
    private static final String[] NEW_STRING_KEYS =
            {"newstring", "newtext", "new", "to", "replace", "replacement"};

    /** Parameter keys, normalised, asking for every occurrence rather than the first. */
    private static final String[] REPLACE_ALL_KEYS = {"replaceall", "all", "global"};

    private FileActionArguments() {}

    /**
     * {@code read <file_path> [--limit <n>] [--offset <n>]}.
     *
     * <p>{@code ReadCommand} matches its flags literally as separate tokens, not as
     * {@code --limit=50}, so each is emitted as two.</p>
     */
    static ActionArgv read(ParsedAction action) {
        List<String> args = new ArrayList<>();
        String       path = action.getFilePath();
        if (path != null) {
            args.add(path);
        }
        addIntegerOption(args, "--limit", action.getIntegerParameter("limit"));
        addIntegerOption(args, "--offset", action.getIntegerParameter("offset"));
        return new ActionArgv("read", args);
    }

    /**
     * Every path as its own argv entry, then the batch options.
     *
     * <p>A model that named one path still works; a model that named five gets all five.</p>
     */
    static ActionArgv multiread(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        for (String path : asStringList(parameters.get("paths"))) {
            args.add(path);
        }
        if (args.isEmpty()) {
            String single = action.getFilePath();
            if (single != null) {
                args.add(single);
            }
        }
        addIntegerOption(args, "--limit", action.getIntegerParameter("limit"));
        addIntegerOption(args, "--offset", action.getIntegerParameter("offset"));
        addIntegerOption(args, "--max-files", either(action, "max_files", "max-files"));
        addIntegerOption(args, "--max-total-lines",
                         either(action, "max_total_lines", "max-total-lines"));
        return new ActionArgv("multiread", args);
    }

    /**
     * {@code write <file_path> <content> [-f]}.
     *
     * <p>Content is one verbatim argument: {@code WriteCommand} captures the first non-flag token
     * after the path as the whole content.</p>
     */
    static ActionArgv write(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String path = action.getFilePath();
        if (path != null) {
            args.add(path);
        }
        String content = asString(parameters.get("content"));
        if (content == null) {
            content = asString(parameters.get("text"));
        }
        if (content != null) {
            args.add(content);
        }
        if (flagTrue(parameters, "force")) {
            args.add("-f");
        }
        return new ActionArgv("write", args);
    }

    /**
     * A fully specified edit, dispatched as the {@code multiedit} it is; anything less, left to
     * {@code edit} to work out.
     *
     * <p>The leftover parameters of an under-specified edit are emitted in stable key order, which
     * is alphabetical -- so {@code new_string} would arrive ahead of {@code old_string} and state
     * the replacement backwards, with nothing to say which word was which. That is the case this
     * translation exists to keep out of {@code EditCommand}.</p>
     */
    static ActionArgv edit(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String path  = action.getFilePath();
        String block = renderEdit(parameters);
        if (path != null && block != null) {
            args.add(path);
            args.add(block);
            return new ActionArgv("multiedit", args);
        }
        if (path != null) {
            args.add(path);
        }
        appendRemainingPositionals(args, parameters);
        return new ActionArgv("edit", args);
    }

    /** {@code multiedit <file_path> <edit blocks>}. */
    static ActionArgv multiedit(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String path = action.getFilePath();
        if (path != null) {
            args.add(path);
        }
        String edits = renderEdits(parameters.get("edits"));
        if (edits != null) {
            args.add(edits);
        }
        return new ActionArgv("multiedit", args);
    }

    /** {@code ls [path]} then the boolean flags and {@code --max-depth}, as LSCommand takes them. */
    static ActionArgv ls(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String path = action.getFilePath();
        if (path != null) {
            args.add(path);
        }
        if (flagTrue(parameters, "all", "show_all", "show-all")) {
            args.add("-a");
        }
        if (flagTrue(parameters, "long", "long_format")) {
            args.add("-l");
        }
        if (flagTrue(parameters, "recursive")) {
            args.add("-R");
        }
        if (flagTrue(parameters, "reverse")) {
            args.add("-r");
        }
        if (flagTrue(parameters, "dirs_first", "dirs-first")) {
            args.add("-d");
        }
        addIntegerOption(args, "--max-depth", either(action, "max_depth", "max-depth"));
        return new ActionArgv("ls", args);
    }

    /** {@code notebookread <notebook> [--cell <id>]}. */
    static ActionArgv notebookread(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String path = action.getFilePath();
        if (path == null) {
            path = asString(parameters.get("notebook_path"));
        }
        if (path != null) {
            args.add(path);
        }
        String cell = asString(parameters.get("cell"));
        if (cell == null) {
            cell = asString(parameters.get("cell_id"));
        }
        if (cell != null) {
            args.add("--cell");
            args.add(cell);
        }
        return new ActionArgv("notebookread", args);
    }

    /** {@code notebookedit <notebook> <cell> <source> [--type <t>] [--mode <m>]}. */
    static ActionArgv notebookedit(ParsedAction action) {
        Map<String, Object> parameters = action.parameters();
        List<String>        args       = new ArrayList<>();
        String path = action.getFilePath();
        if (path == null) {
            path = asString(parameters.get("notebook_path"));
        }
        if (path != null) {
            args.add(path);
        }
        String cellId = asString(parameters.get("cell_id"));
        if (cellId == null) {
            cellId = asString(parameters.get("cell"));
        }
        if (cellId != null) {
            args.add(cellId);
        }
        String source = asString(parameters.get("source"));
        if (source == null) {
            source = asString(parameters.get("new_source"));
        }
        if (source != null) {
            args.add(source);
        }
        String type = asString(parameters.get("type"));
        if (type != null) {
            args.add("--type");
            args.add(type);
        }
        String mode = asString(parameters.get("mode"));
        if (mode != null) {
            args.add("--mode");
            args.add(mode);
        }
        return new ActionArgv("notebookedit", args);
    }

    /** The first of two spellings of one integer option that the action actually carries. */
    private static Integer either(ParsedAction action, String first, String second) {
        Integer value = action.getIntegerParameter(first);
        return value != null ? value : action.getIntegerParameter(second);
    }

    /**
     * Renders the {@code edits} parameter of a multiedit into the block form the command parses.
     *
     * <p>The natural JSON shape for a multiedit is a list of objects,
     * {@code [{"old_string": "a", "new_string": "b"}]}. {@code MultiEditCommand} has exactly one
     * deterministic single-argument form -- {@code EDIT_START/OLD/NEW/REPLACE_ALL/EDIT_END} -- and
     * classifies any other single trailing argument as prose, which sends the request back to the
     * model to be re-derived. So the structure the model already supplied is translated here rather
     * than dropped and asked for again.</p>
     *
     * <p>A string that is already in block form is passed through untouched.</p>
     *
     * @param editsValue the raw {@code edits} parameter (may be {@code null})
     * @return the instruction to dispatch, or {@code null} when there is nothing usable
     */
    private static String renderEdits(Object editsValue) {
        if (editsValue == null) {
            return null;
        }
        if (!(editsValue instanceof Collection)) {
            String text = scalarText(editsValue);
            return (text == null || text.trim().isEmpty()) ? null : text;
        }

        StringBuilder blocks = new StringBuilder();
        for (Object element : (Collection<?>) editsValue) {
            String block = renderEdit(element);
            if (block == null) {
                continue;
            }
            if (blocks.length() > 0) {
                blocks.append('\n');
            }
            blocks.append(block);
        }
        return blocks.length() == 0 ? null : blocks.toString();
    }

    /**
     * Renders one deterministic replacement into the block form {@code MultiEditCommand} parses, or
     * {@code null} when the map names nothing to replace.
     *
     * <p>Used for each entry of a multiedit's {@code edits} list and for the parameters of a single
     * {@code edit} action, which is the same thing said once: both name the text to find and the
     * text to put in its place.</p>
     */
    private static String renderEdit(Object element) {
        if (!(element instanceof Map)) {
            String text = scalarText(element);
            return (text == null || text.trim().isEmpty()) ? null : text;
        }

        Map<String, Object> edit      = normalizedKeys((Map<?, ?>) element);
        String              oldString = firstScalarOf(edit, OLD_STRING_KEYS);
        if (oldString == null || oldString.isEmpty()) {
            return null;
        }
        String newString  = firstScalarOf(edit, NEW_STRING_KEYS);
        String replaceAll = firstScalarOf(edit, REPLACE_ALL_KEYS);

        return "EDIT_START\n"
               + "OLD: " + oldString + "\n"
               + "NEW: " + (newString != null ? newString : "") + "\n"
               + "REPLACE_ALL: " + Boolean.parseBoolean(replaceAll != null ? replaceAll.trim() : "false")
               + "\nEDIT_END";
    }

    /** The first present scalar value among {@code keys}, or {@code null}. */
    private static String firstScalarOf(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            String text = scalarText(values.get(key));
            if (text != null) {
                return text;
            }
        }
        return null;
    }
}
