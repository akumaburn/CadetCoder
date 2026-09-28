package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.session.SessionManager;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.concurrent.Callable;

@Command (name = "todoread", description = "Read the current to-do list for the session")
public class TodoReadCommand implements CommandRegistry.Command, Callable<Integer> {

    /** The shared glyph set, so these marks degrade with the rest of the console. */
    private static final com.eonmux.cadetcoder.ui.Glyphs GLYPHS =
            com.eonmux.cadetcoder.ui.Glyphs.system();

    @Override
    public Integer call() throws Exception {
        return execute(new String[0]);
    }

    @Override
    public int execute(String[] args) {
        try {
            List<TodoItem> todos = SessionManager.getInstance().getTodoList();

            if (todos.isEmpty()) {
                OutputFormatter.println("No todos in the current session");
                return 0;
            }

            OutputFormatter.printHeader("Current Todo List");

            // Group by status
            OutputFormatter.printSubheader("In Progress:");
            printTodosByStatus(todos, TodoItem.Status.IN_PROGRESS);

            OutputFormatter.printSubheader("Pending:");
            printTodosByStatus(todos, TodoItem.Status.PENDING);

            OutputFormatter.printSubheader("Completed:");
            printTodosByStatus(todos, TodoItem.Status.COMPLETED);

            // Summary
            long completed  = todos.stream().filter(t -> t.getStatus() == TodoItem.Status.COMPLETED).count();
            long inProgress = todos.stream().filter(t -> t.getStatus() == TodoItem.Status.IN_PROGRESS).count();
            long pending    = todos.stream().filter(t -> t.getStatus() == TodoItem.Status.PENDING).count();

            OutputFormatter.printInfo(String.format("Summary: %d completed, %d in progress, %d pending",
                    completed, inProgress, pending));

            return 0;
        } catch (NullPointerException e) {
            OutputFormatter.printError("Todo data corrupted: missing required field");
            return 1;
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            OutputFormatter.printError("Error reading todo list: " + detail);
            return 1;
        }
    }

    private void printTodosByStatus(List<TodoItem> todos, TodoItem.Status status) {
        todos.stream()
             .filter(t -> t.getStatus() == status)
             .forEach(todo -> {
                 String priority = todo.getPriority() != null ? todo.getPriority().toString() : "UNKNOWN";
                 String marker   = getStatusMarker(todo.getStatus());
                 OutputFormatter.printf("  %s [%s] %s - %s%n",
                         marker, todo.getId(), priority, todo.getContent());
             });
    }

    /**
     * The mark shown against a to-do, drawn from the shared glyph set.
     *
     * <p>{@code COMPLETED} used to be the literal {@code "[OK]"}. That is now the ASCII spelling of
     * the console's success marker, and the row is printed with a two-space indent that the
     * classifier strips -- so every completed to-do was being read as a success line and coloured
     * green in the shell, alongside the box characters used for the other states, which were not
     * classified at all and had no ASCII fallback.</p>
     */
    private String getStatusMarker(TodoItem.Status status) {
        boolean unicode = GLYPHS.isUnicode();
        switch (status) {
            case COMPLETED:
                return unicode ? "\u25c9" : "[x]";
            case IN_PROGRESS:
                return unicode ? "\u25d0" : "[~]";
            case PENDING:
                return unicode ? "\u25cb" : "[ ]";
            default:
                return unicode ? "\u25cc" : "[?]";
        }
    }


    @Override
    public String getUsage() {
        return "todoread";
    }

    // TodoItem class for todo management.
    //
    // Immutable value object: all fields are final and there are no setters. Any change
    // (for example a status transition) is performed by rebuilding a new instance via the
    // with* helpers, so shared in-memory state is never mutated in place. Jackson rebuilds
    // instances through the @JsonCreator constructor during session deserialization.
    public static final class TodoItem {
        private final String   id;
        private final String   content;
        private final Status   status;
        private final Priority priority;

        @com.fasterxml.jackson.annotation.JsonCreator
        public TodoItem(
                @com.fasterxml.jackson.annotation.JsonProperty ("id") String id,
                @com.fasterxml.jackson.annotation.JsonProperty ("content") String content,
                @com.fasterxml.jackson.annotation.JsonProperty ("status") Status status,
                @com.fasterxml.jackson.annotation.JsonProperty ("priority") Priority priority) {
            this.id       = id;
            this.content  = content;
            this.status   = status;
            this.priority = priority;
        }

        // Getters
        public String getId() {
            return id;
        }

        public String getContent() {
            return content;
        }

        public Status getStatus() {
            return status;
        }

        public Priority getPriority() {
            return priority;
        }

        // Immutable-rebuild helper: returns a new TodoItem with the given status, leaving
        // this instance untouched.
        public TodoItem withStatus(Status newStatus) {
            return new TodoItem(this.id, this.content, newStatus, this.priority);
        }

        public enum Status {
            PENDING, IN_PROGRESS, COMPLETED;

            /**
             * The status a text names.
             *
             * <p>Read in any case, with a hyphen or a space in place of the underscore, and with
             * {@code done} and {@code complete} for {@link #COMPLETED}: a model wrote {@code done},
             * and each of these forms names one status only.</p>
             *
             * @param text the status as written
             * @return the status
             * @throws IllegalArgumentException when the text names none
             */
            public static Status named(String text) {
                String name = text == null ? "" : text.strip().toUpperCase(java.util.Locale.ROOT)
                                                      .replace('-', '_').replace(' ', '_');
                if (name.equals("DONE") || name.equals("COMPLETE")) {
                    return COMPLETED;
                }
                return valueOf(name);
            }
        }

        public enum Priority {
            HIGH, MEDIUM, LOW
        }
    }
}