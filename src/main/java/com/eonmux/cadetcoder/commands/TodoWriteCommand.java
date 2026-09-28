package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.session.SessionManager;
import picocli.CommandLine.*;

import java.util.*;
import java.util.concurrent.Callable;

@Command (name = "todowrite", description = "Create and manage a structured task list")
public class TodoWriteCommand implements CommandRegistry.Command, Callable<Integer> {

    @Parameters (index = "0", description = "Action: add, update, remove, clear")
    private String action;

    @Parameters (index = "1..*", description = "Task content or ID (for update/remove)")
    private String[] taskParts;

    @Option (names = {"-s", "--status"}, description = "Status: pending, in_progress, completed")
    private String status = null;

    @Option (names = {"-p", "--priority"}, description = "Priority: high, medium, low")
    private String priority = "medium";

    @Option (names = {"-i", "--id"}, description = "Task ID for update operations")
    private String taskId;

    @Override
    public Integer call() throws Exception {
        List<String> args = new ArrayList<>();
        args.add(action);
        if (taskParts != null) {
            Collections.addAll(args, taskParts);
        }
        if (status != null) {
            args.add("--status");
            args.add(status);
        }
        if (priority != null && !priority.equals("medium")) {
            args.add("--priority");
            args.add(priority);
        }
        if (taskId != null) {
            args.add("--id");
            args.add(taskId);
        }
        return execute(args.toArray(new String[0]));
    }

    @Override
    public int execute(String[] args) {
        try {
            if (args.length == 0) {
                OutputFormatter.printError("No action provided. Use: add, update, remove, or clear");
                return 1;
            }

            // Accept -s=<v>/-p=<v>/-i=<v> as well as the space-separated form, so the option syntax
            // the command catalog promises the model actually works here.
            args = CommandOptions.expandInlineValues(args, java.util.Set.of(
                    "-s", "--status", "-p", "--priority", "-i", "--id"));

            String       act          = args[0].toLowerCase();
            List<String> contentParts = new ArrayList<>();
            String       tId          = null;
            String       tStatus      = null;
            String       tPriority    = "medium";

            // Robustness (finding todo-1): if the first token is not one of the known
            // subcommands, treat the whole invocation as an implicit "add <text>". This lets
            // a bare "todowrite <free text>" still create a todo instead of failing, while the
            // explicit "add/update/remove/clear" contract is preserved. The first token is part
            // of the content in that case, so parsing starts at index 0 rather than index 1.
            boolean implicitAdd = !isKnownAction(act);
            int     parseStart  = implicitAdd ? 0 : 1;
            if (implicitAdd) {
                act = "add";
            }

            // Parse arguments
            for (int i = parseStart; i < args.length; i++) {
                if ((args[i].equals("-s") || args[i].equals("--status")) && i + 1 < args.length) {
                    tStatus = args[++i].toLowerCase();
                } else if ((args[i].equals("-p") || args[i].equals("--priority")) && i + 1 < args.length) {
                    tPriority = args[++i].toLowerCase();
                } else if ((args[i].equals("-i") || args[i].equals("--id")) && i + 1 < args.length) {
                    tId = args[++i];
                } else if (looksLikeAnOption(args[i])) {
                    // Without this, a mistyped or invented flag falls through to the content and
                    // becomes a task literally named "--clear-completed". A to-do list nobody
                    // asked for is worse than a refusal.
                    OutputFormatter.printError("Unknown option: " + args[i]);
                    OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                    return 1;
                } else {
                    contentParts.add(args[i]);
                }
            }

            String content = String.join(" ", contentParts);

            switch (act) {
                case "add":
                    return addTodo(content, tStatus != null ? tStatus : "pending", tPriority);
                case "update":
                    return updateTodo(tId != null ? tId : content, tStatus);
                case "remove":
                    return removeTodo(tId != null ? tId : content);
                case "clear":
                    return clearTodos();
                default:
                    // Unreachable in practice: any token that is not a known subcommand is
                    // remapped to "add" above. Retained as a defensive fallback.
                    OutputFormatter.printError("Unknown action: " + act);
                    return 1;
            }
        } catch (Exception e) {
            OutputFormatter.printError("Error managing todos: " + e.getMessage());
            return 1;
        }
    }

    private int addTodo(String content, String statusStr, String priorityStr) {
        if (content.isEmpty()) {
            OutputFormatter.printError("Todo content cannot be empty");
            return 1;
        }

        // Validate content length
        if (content.length() > 1000) {
            OutputFormatter.printError("Todo content exceeds maximum length (1000 characters)");
            return 1;
        }

        try {
            TodoReadCommand.TodoItem.Status   status   =
                    TodoReadCommand.TodoItem.Status.named(statusStr);
            TodoReadCommand.TodoItem.Priority priority =
                    TodoReadCommand.TodoItem.Priority.valueOf(priorityStr.toUpperCase());

            String                   id   = generateId();
            TodoReadCommand.TodoItem todo = new TodoReadCommand.TodoItem(id, content, status, priority);

            // Use synchronized access to prevent race conditions
            synchronized (SessionManager.getInstance()) {
                List<TodoReadCommand.TodoItem> todos = new ArrayList<>(SessionManager.getInstance().getTodoList());
                todos.add(todo);
                SessionManager.getInstance().setTodoList(todos);
                SessionManager.getInstance().saveSession();
            }

            OutputFormatter.printSuccess("Added todo [" + id + "]: " + content);
            return 0;
        } catch (IllegalArgumentException e) {
            OutputFormatter.printError("Invalid status or priority value. "
                    + "Valid status: pending, in_progress, completed. "
                    + "Valid priority: high, medium, low");
            return 1;
        }
    }

    private int updateTodo(String idOrContent, String statusStr) {
        if (statusStr == null) {
            OutputFormatter.printError("update requires -s <status> "
                    + "(pending, in_progress, completed)");
            return 1;
        }

        try {
            TodoReadCommand.TodoItem.Status newStatus =
                    TodoReadCommand.TodoItem.Status.named(statusStr);

            // Use synchronized access to prevent race conditions
            synchronized (SessionManager.getInstance()) {
                List<TodoReadCommand.TodoItem> todos = SessionManager.getInstance().getTodoList();

                TodoReadCommand.TodoItem todoToUpdate;
                try {
                    todoToUpdate = resolveTarget(todos, idOrContent);
                } catch (AmbiguousMatchException e) {
                    OutputFormatter.printError(e.getMessage());
                    return 1;
                }

                if (todoToUpdate == null) {
                    OutputFormatter.printError("Todo not found: " + idOrContent);
                    return 1;
                }

                // Build a new list with a replacement TodoItem for the target id, leaving every
                // existing TodoItem instance untouched. TodoItem is immutable, so the status
                // change is performed by rebuilding via withStatus; the session's in-memory
                // state therefore changes only when the new list is committed.
                final String updateId = todoToUpdate.getId();
                List<TodoReadCommand.TodoItem> updatedTodos = new ArrayList<>(todos.size());
                for (TodoReadCommand.TodoItem todo : todos) {
                    if (todo.getId().equals(updateId)) {
                        updatedTodos.add(todo.withStatus(newStatus));
                    } else {
                        updatedTodos.add(todo);
                    }
                }
                SessionManager.getInstance().setTodoList(updatedTodos);
                SessionManager.getInstance().saveSession();

                OutputFormatter.printSuccess("Updated todo [" + todoToUpdate.getId() + "] to " + newStatus);
                return 0;
            }
        } catch (IllegalArgumentException e) {
            OutputFormatter.printError("Invalid status value: " + statusStr
                    + ". Valid status: pending, in_progress, completed");
            return 1;
        }
    }

    private int removeTodo(String idOrContent) {
        // Use synchronized access to prevent race conditions
        synchronized (SessionManager.getInstance()) {
            List<TodoReadCommand.TodoItem> todos = SessionManager.getInstance().getTodoList();

            TodoReadCommand.TodoItem todoToRemove;
            try {
                todoToRemove = resolveTarget(todos, idOrContent);
            } catch (AmbiguousMatchException e) {
                OutputFormatter.printError(e.getMessage());
                return 1;
            }

            if (todoToRemove == null) {
                OutputFormatter.printError("Todo not found: " + idOrContent);
                return 1;
            }

            // Remove the todo
            final String removeId = todoToRemove.getId();
            todos = new ArrayList<>(todos); // Create a copy to avoid modifying the original list
            todos.removeIf(todo -> todo.getId().equals(removeId));
            SessionManager.getInstance().setTodoList(todos);
            SessionManager.getInstance().saveSession();

            OutputFormatter.printSuccess("Removed todo [" + todoToRemove.getId() + "]: " + todoToRemove.getContent());
            return 0;
        }
    }

    private int clearTodos() {
        synchronized (SessionManager.getInstance()) {
            SessionManager.getInstance().setTodoList(new ArrayList<>());
            SessionManager.getInstance().saveSession();
            OutputFormatter.printSuccess("Cleared all todos");
            return 0;
        }
    }

    /**
     * Whether a token reads as an option rather than as task text.
     *
     * <p>A single {@code -} and a bare negative number are ordinary content; two or more leading
     * dashes, or a dash followed by a letter, are somebody trying to pass a flag.</p>
     *
     * @param token one argument
     * @return true when the token should be treated as an option
     */
    private static boolean looksLikeAnOption(String token) {
        if (token == null || token.length() < 2 || token.charAt(0) != '-') {
            return false;
        }
        return token.charAt(1) == '-' || Character.isLetter(token.charAt(1));
    }

    private static boolean isKnownAction(String act) {
        return act.equals("add") || act.equals("update")
                || act.equals("remove") || act.equals("clear");
    }

    /**
     * Resolves the single todo targeted by an id-or-content selector.
     *
     * <p>Finding todo-3: matching on content alone could silently act on the wrong todo when
     * several share the same content. Resolution therefore prefers an exact id match (ids are
     * unique); only when no id matches does it fall back to content. A content selector must
     * match exactly one todo, otherwise an {@link AmbiguousMatchException} is raised so the
     * caller can report the ambiguity instead of guessing.
     *
     * @return the matched todo, or {@code null} when nothing matches.
     */
    private TodoReadCommand.TodoItem resolveTarget(List<TodoReadCommand.TodoItem> todos,
                                                   String idOrContent)
            throws AmbiguousMatchException {
        for (TodoReadCommand.TodoItem todo : todos) {
            if (todo.getId().equals(idOrContent)) {
                return todo;
            }
        }

        List<TodoReadCommand.TodoItem> contentMatches = new ArrayList<>();
        for (TodoReadCommand.TodoItem todo : todos) {
            if (todo.getContent().equals(idOrContent)) {
                contentMatches.add(todo);
            }
        }

        if (contentMatches.size() > 1) {
            throw new AmbiguousMatchException(
                    "Ambiguous match: " + contentMatches.size() + " todos have content \""
                            + idOrContent + "\". Specify the todo id with -i <id> instead.");
        }

        return contentMatches.isEmpty() ? null : contentMatches.get(0);
    }

    /** Signals that a content selector matched more than one todo. */
    private static final class AmbiguousMatchException extends Exception {

        private static final long serialVersionUID = 1L;

        AmbiguousMatchException(String message) {
            super(message);
        }
    }

    private String generateId() {
        // Use full UUID to reduce collision probability
        return UUID.randomUUID().toString();
    }


    @Override
    public String getUsage() {
        return "todowrite add <content> [options]\n" +
               "todowrite update <id|content> [options]\n" +
               "todowrite remove <id|content>\n" +
               "todowrite clear\n" +
               "todowrite <content>            (shorthand for add <content>)\n" +
               "  -s, --status <status>   pending, in_progress or completed\n" +
               "  -p, --priority <level>  high, medium or low (default: medium)\n" +
               "  -i, --id <id>           Name the task by id instead of by its text";
    }
}