package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the help says, as data, for both places that show it.
 *
 * <h2>Why one source</h2>
 *
 * <p>The help was written twice. {@code /help} listed every command under a heading for what it is
 * for, and said nothing about the keyboard, the mouse or the slash rule. The shell's F1 overlay
 * explained all three, then named seven commands out of forty-two and pointed at {@code /help} for
 * the rest. Neither answered a reader who had opened it: "what can I type" needed the overlay
 * closed, and "how do I copy a selection" needed a command run. One list of sections, rendered by
 * each surface in its own way, is the same reference wherever it is opened.</p>
 *
 * <h2>Why the prefix decides what is shown</h2>
 *
 * <p>A command is {@code /grep} at the shell's prompt and {@code cadet grep} in a terminal, and
 * {@link CommandUsage#prefix()} already answers which. That same answer says whether the reader is
 * inside the shell, so the sections about keys, the mouse and the predicted command name -- which
 * exist only there -- are included exactly when they apply.</p>
 */
public final class HelpContent {

    /**
     * One row: what to type, and what it does.
     *
     * @param term        the command, key or gesture
     * @param description what it does
     */
    public record Entry(String term, String description) {

        /** @return the columns {@link #term} occupies */
        int width() {
            return term == null ? 0 : term.length();
        }
    }

    /**
     * One part of the reference.
     *
     * @param heading what the part is about
     * @param entries its rows, which may be empty
     * @param notes   prose under the rows, one paragraph per element; may be empty
     */
    public record Section(String heading, List<Entry> entries, List<String> notes) {

        /**
         * The column the descriptions start at, for this section alone.
         *
         * <p>Per section rather than per document: the widest term anywhere is a two-key
         * combination like {@code Ctrl+Home / Ctrl+End}, and aligning forty command names to it
         * would indent every description by twenty columns to spare one row its ragged edge.</p>
         *
         * @return the width to pad terms to
         */
        public int width() {
            int width = 0;
            for (Entry entry : entries) {
                width = Math.max(width, entry.width());
            }
            return width;
        }
    }

    /**
     * The command groups, in the order they are shown, and the commands in each.
     *
     * <p>Ordered by how early someone needs them: setting a provider up comes before asking the
     * model for anything, and the housekeeping commands come last. A command that is not listed
     * here still appears, under {@link #OTHER} -- a new command must never go missing from the
     * reference because nobody remembered to add it to this map.</p>
     */
    private static final LinkedHashMap<String, List<String>> GROUPS = new LinkedHashMap<>();

    /** Where a command that is in no group is shown. */
    public static final String OTHER = "Other";

    static {
        GROUPS.put("Ask the AI", List.of("chat", "edit", "agent", "loop", "loopfresh",
                                        "workers", "resume", "runs", "plan"));
        GROUPS.put("Read and write files",
                   List.of("read", "multiread", "write", "multiedit", "ls", "stat",
                           "diff", "patch", "notebookread", "notebookedit"));
        GROUPS.put("Find things", List.of("grep", "glob", "search", "index"));
        GROUPS.put("Understand code", List.of("analyze", "explain", "suggest", "refactor"));
        GROUPS.put("Run things", List.of("bash", "job", "execute", "shell"));
        GROUPS.put("Git", List.of("commit", "push", "undo"));
        GROUPS.put("The web", List.of("webfetch", "websearch"));
        GROUPS.put("Session and tasks",
                   List.of("session", "context", "todoread", "todowrite", "timer", "compact"));
        GROUPS.put("Providers and models", List.of("login", "copilot", "models"));
        GROUPS.put("Settings", List.of("config", "ubermode", "theme", "prompt"));
        GROUPS.put("Help and exiting", List.of("help", "clear", "quit"));
    }

    private HelpContent() {
    }

    /**
     * Whether the reader is at the interactive shell's prompt.
     *
     * @param prefix how a command is invoked where they are reading
     * @return whether the keys, the mouse and the slash rule apply
     */
    public static boolean atTheShell(String prefix) {
        return prefix != null && prefix.startsWith(String.valueOf(InputRouter.COMMAND_PREFIX));
    }

    /**
     * The group a command belongs to.
     *
     * @param name a registered command name
     * @return its group heading, or {@link #OTHER} when it is in none
     */
    public static String groupOf(String name) {
        for (Map.Entry<String, List<String>> group : GROUPS.entrySet()) {
            if (group.getValue().contains(name)) {
                return group.getKey();
            }
        }
        return OTHER;
    }

    /** @return the group headings, in display order */
    public static List<String> groupOrder() {
        return List.copyOf(GROUPS.keySet());
    }

    /**
     * The whole reference, in reading order.
     *
     * <h2>Why the commands come last</h2>
     *
     * <p>They are two thirds of it. Put first, they fill the overlay's opening screen and push the
     * keys, which are the reason the overlay exists, off the bottom; and at the shell's prompt they
     * push the short material off the top of the console instead. Last, the short sections open the
     * panel and the command list ends the printed page, next to the prompt.</p>
     *
     * @param registry where the commands and their descriptions come from; may be {@code null}
     * @param prefix   how a command is invoked where this will be read
     * @return the sections, each ready to be rendered
     */
    public static List<Section> sections(CommandRegistry registry, String prefix) {
        List<Section> sections = new ArrayList<>();
        if (atTheShell(prefix)) {
            sections.add(talkingAndCommanding(prefix));
            sections.add(keyboard());
            sections.add(mouse());
            sections.add(pasting());
        }
        sections.addAll(commands(registry, prefix));
        return sections;
    }

    /**
     * The lines that close the reference, which both surfaces show as notices.
     *
     * @param prefix how a command is invoked where this will be read
     * @return the notes, in order
     */
    public static List<String> footer(String prefix) {
        List<String> notes = new ArrayList<>();
        if (atTheShell(prefix)) {
            notes.add("Results render Markdown: headings, bold, code blocks, lists, links.");
        } else {
            notes.add("Run a command as 'cadet <command>'. Anything that is not a command name is "
                      + "sent to the AI.");
        }
        notes.add("Run '" + prefix + "help <command>' for one command's usage, e.g. '"
                  + prefix + "help grep'.");
        return notes;
    }

    /**
     * Renders one row for a plain-text surface.
     *
     * @param entry the row
     * @param width the column its description starts at
     * @return the line, indented by two columns
     */
    public static String row(Entry entry, int width) {
        String term = entry.term() == null ? "" : entry.term();
        String pad  = term.length() >= width ? "" : " ".repeat(width - term.length());
        return "  " + term + pad + "  " + entry.description();
    }

    /** What a line typed at the shell's prompt means. */
    private static Section talkingAndCommanding(String prefix) {
        List<Entry> entries = List.of(
                new Entry("hello there", "Plain text goes to the AI"),
                new Entry(prefix + "read pom.xml", "A leading '/' runs a command"),
                new Entry("//literal", "'//' escapes, for a message that starts with a slash"));
        return new Section("Talking and commanding", entries, List.of(
                "After '/', the rest of the name is predicted as you type. Right accepts it; "
                + "while several commands still match, the count is shown instead."));
    }

    /**
     * One section per group of commands, skipping a group whose commands are all absent.
     *
     * <p>A trimmed-down build must not print an empty heading, and a command in no group is
     * collected under {@link #OTHER} rather than dropped.</p>
     */
    private static List<Section> commands(CommandRegistry registry, String prefix) {
        Map<String, CommandRegistry.Command> commands =
                registry == null ? null : registry.getCommands();
        if (commands == null) {
            commands = Map.of();
        }
        Set<String>   registered = commands.keySet();
        List<Section> sections   = new ArrayList<>();
        for (Map.Entry<String, List<String>> group : GROUPS.entrySet()) {
            List<Entry> entries = new ArrayList<>();
            for (String name : group.getValue()) {
                CommandRegistry.Command command = commands.get(name);
                if (command != null) {
                    entries.add(new Entry(prefix + name, command.getDescription()));
                }
            }
            if (!entries.isEmpty()) {
                sections.add(new Section(group.getKey(), List.copyOf(entries), List.of()));
            }
        }
        List<String> ungrouped = new ArrayList<>();
        for (String name : registered) {
            if (OTHER.equals(groupOf(name))) {
                ungrouped.add(name);
            }
        }
        if (!ungrouped.isEmpty()) {
            Collections.sort(ungrouped);
            List<Entry> entries = new ArrayList<>();
            for (String name : ungrouped) {
                entries.add(new Entry(prefix + name, commands.get(name).getDescription()));
            }
            sections.add(new Section(OTHER, List.copyOf(entries), List.of()));
        }
        return sections;
    }

    /** What arrives when text or a file is dropped on the terminal. */
    private static Section pasting() {
        return new Section("Pasting and dropping files", List.of(), List.of(
                "A paste of more than one line goes in as a short marker, such as "
                + "[#1: 42 lines]. Type around it, move past it and delete it like any other word; "
                + "what it stands for is sent when you press Enter.",
                "A file dropped on the terminal goes in as its name and is sent as its path, so a "
                + "command can open it.",
                "A dropped PNG, JPEG, GIF or WebP is marked as an image, such as "
                + "[#2: image shot.png], and the picture itself goes to the model with your "
                + "question. Delete the marker before you press Enter and the picture is not sent. "
                + "A model that does not read images is told the path and says so."));
    }

    /** Every key the shell answers to. */
    private static Section keyboard() {
        List<Entry> entries = List.of(
                new Entry("Enter", "Send the line (run the command, or ask the AI)"),
                new Entry("Right", "Accept the predicted command name (at end of line)"),
                new Entry("Up / Down", "Command history (scrolls the focused pane, or picks a "
                                       + "line of the F5 list)"),
                new Entry("Ctrl+Left / Ctrl+Right", "Move a word at a time (Alt+arrow on some "
                                                    + "terminals)"),
                new Entry("Ctrl+W", "Delete the word behind the cursor"),
                new Entry("Tab / Shift+Tab",
                          "View each worker's and each job's output, live (focus mode)"),
                new Entry("Esc", "Back out: a prompt, select mode, a selection, a focused result, "
                                 + "then the line being typed"),
                new Entry("F1", "Toggle this help"),
                new Entry("F2", "Interrupt the running command; /resume carries it on"),
                new Entry("F3", "Print recent command history"),
                new Entry("F4", "Select mode (the mouse selects; clicks do not open a result)"),
                new Entry("F5", "List every worker and every job; Enter opens the picked line"),
                new Entry("Ctrl+U", "Clear the line being typed"),
                new Entry("Ctrl+L", "Clear the console, as /clear does"),
                new Entry("Ctrl+Shift+C", "Copy the current selection (does not quit)"),
                new Entry("Ctrl+Q", "Quit (or type '/quit')"),
                new Entry("Page Up / Page Down", "Scroll the focused result, or the transcript"),
                new Entry("Ctrl+Home / Ctrl+End", "Jump to the top, or follow the bottom"));
        return new Section("Keyboard", entries, List.of());
    }

    /** What the mouse does, including the selection it is mostly used for. */
    private static Section mouse() {
        List<Entry> entries = List.of(
                new Entry("Wheel", "Scroll the console"),
                new Entry("Click a result",
                          "Focus it, which shows the output the live view leaves out"),
                new Entry("Drag",
                          "Select text; release, or Ctrl+Shift+C, copies it to the clipboard"));
        return new Section("Mouse", entries, List.of(
                "Hold at the top or bottom edge while dragging and the transcript scrolls, so a "
                + "selection can cover far more than one screenful. The further past the edge you "
                + "pull, the faster it moves.",
                "F4 gives the mouse over to selecting, so a click that misses does not open the "
                + "result under it. Esc or F4 again leaves."));
    }
}
