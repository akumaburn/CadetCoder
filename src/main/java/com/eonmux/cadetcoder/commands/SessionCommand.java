package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.session.SessionState;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Shows, switches and starts sessions.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Everything behind it was already built and none of it was reachable. Sessions are archived on
 * every save, kept fifty deep, pruned oldest-first, and can be reopened by identifier — but nothing
 * ever listed them, so {@code --resume &lt;id&gt;} asked for an identifier the user had no way to
 * learn, and {@code --continue} was the only door into an archive it could only ever open one file
 * of.</p>
 *
 * <p>And there was no way to finish a session. Every run continues whatever is in
 * {@code session.json}, which is right for a tool used mostly one command at a time and means a
 * conversation accumulates for as long as the install does. A resumed run spends a quarter of its
 * context window on that history, so with no way to draw a line, that quarter eventually goes on
 * work from weeks ago.</p>
 */
@picocli.CommandLine.Command(name = "session",
        description = "Show, list, switch or start sessions")
public class SessionCommand implements CommandRegistry.Command {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public int execute(String[] args) {
        String action = args == null || args.length == 0 || args[0] == null || args[0].isBlank()
                        ? "status"
                        : args[0].trim().toLowerCase();

        switch (action) {
            case "status":
                return showStatus();
            case "list":
                return list(args);
            case "new":
                return startNew();
            case "resume":
                return resume(args);
            default:
                OutputFormatter.printError("Unknown action: " + action);
                OutputFormatter.printInfo("Usage: " + CommandUsage.render(getUsage()));
                return 1;
        }
    }

    private int showStatus() {
        SessionManager sessions = SessionManager.getInstance();
        SessionState   state    = sessions.getSessionState();

        OutputFormatter.printHeader("Current Session");
        UnifiedOutput.println("  Id           " + state.getSessionId());
        UnifiedOutput.println("  Started      " + when(state.getCreatedAt()));
        UnifiedOutput.println("  Turns        " + sessions.getConversationHistory().size());
        UnifiedOutput.println("  Scrollback   " + sessions.getTranscript().size() + " saved region(s)");
        UnifiedOutput.println("  Todo items   " + sessions.getTodoList().size());
        UnifiedOutput.println("  Resumed      " + (sessions.isResumed() ? "yes" : "no"));
        UnifiedOutput.println("");
        OutputFormatter.printInfo("'" + CommandUsage.prefix() + "session list' shows earlier "
                + "sessions; '" + CommandUsage.prefix() + "session new' starts a fresh one.");
        return 0;
    }

    /**
     * How many sessions {@code session list} shows.
     *
     * <p>Every session unless a count is given. The list stopped at ten without saying so, and a
     * session older than the tenth could not be found in the list meant for finding it.</p>
     *
     * @param args the arguments, the first being {@code list}
     * @return the count, {@link Integer#MAX_VALUE} for every session, or {@code -1} when the count
     *         given is not a number
     */
    static int listLimit(String[] args) {
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Math.max(1, Integer.parseInt(args[1].trim()));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private int list(String[] args) {
        int limit = listLimit(args);
        if (limit < 0) {
            OutputFormatter.printError("Not a number: " + args[1]);
            return 1;
        }

        SessionManager     sessions = SessionManager.getInstance();
        List<SessionState> recent   = sessions.listRecentSessions(limit);
        if (recent.isEmpty()) {
            OutputFormatter.printInfo("No sessions have been archived yet.");
            return 0;
        }

        String current = sessions.getCurrentSessionId();
        OutputFormatter.printHeader(limit == Integer.MAX_VALUE ? "Sessions" : "Recent Sessions");
        for (SessionState state : recent) {
            String mark = state.getSessionId().equals(current) ? "*" : " ";
            UnifiedOutput.println(String.format("%s %-24s  %s  %4d turns  %s",
                    mark, state.getSessionId(), when(state.getLastModified()),
                    state.getConversationHistory().size(), preview(state)));
        }
        UnifiedOutput.println("");
        OutputFormatter.printInfo("'" + CommandUsage.prefix() + "session resume <id>' opens one; "
                + "'*' marks the current session.");
        return 0;
    }

    private int startNew() {
        // Ending one session and opening another is a decision about the session rather than about
        // the work, so it belongs to the person whose session it is. A model reaching this would be
        // discarding its own context and the user's along with it.
        if (ModelDispatch.isModelDriven()) {
            OutputFormatter.printError("Starting a new session is the user's call, not the model's.");
            return 1;
        }

        SessionManager sessions = SessionManager.getInstance();
        String         previous = sessions.getCurrentSessionId();
        String         started  = sessions.startNewSession();

        // The console holds the previous session's scrollback; see InteractiveShell#reloadSession.
        boolean bannerRedrawn = OutputRouter.getInstance().requestSessionReload();

        // A shell that took the reload redraws its banner, and the banner names the session. Saying
        // it again here put the same identifier on two consecutive lines. Nothing redraws on the
        // command line, so there the new session is named here or nowhere.
        if (!bannerRedrawn) {
            OutputFormatter.printSuccess("Started session " + started);
        }
        OutputFormatter.printInfo("The previous session (" + previous
                                  + ") is kept; reopen it with 'session resume " + previous + "'.");
        return 0;
    }

    private int resume(String[] args) {
        if (ModelDispatch.isModelDriven()) {
            OutputFormatter.printError("Switching sessions is the user's call, not the model's.");
            return 1;
        }
        if (args == null || args.length < 2 || args[1] == null || args[1].isBlank()) {
            OutputFormatter.printError("Which session? Run '" + CommandUsage.prefix()
                    + "session list' to see the identifiers.");
            return 1;
        }

        if (!SessionManager.getInstance().loadSessionById(args[1].trim())) {
            return 1;
        }
        OutputRouter.getInstance().requestSessionReload();
        return 0;
    }

    /**
     * The first thing said in a session, whole, on one line.
     *
     * <p>Not cut to fit a column: two sessions begun with the same opening words were listed alike
     * and could not be told apart by what they asked for.</p>
     *
     * @param state the session
     * @return its first instruction
     */
    static String preview(SessionState state) {
        for (String entry : state.getConversationHistory()) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String text = entry.startsWith("User: ") ? entry.substring("User: ".length()) : entry;
            return text.replaceAll("\\s+", " ").trim();
        }
        return "(nothing said)";
    }

    private static String when(long epochMillis) {
        return epochMillis <= 0 ? "unknown" : WHEN.format(Instant.ofEpochMilli(epochMillis));
    }


    @Override
    public String getUsage() {
        return "session [status | list [count] | new | resume <id>]";
    }
}
