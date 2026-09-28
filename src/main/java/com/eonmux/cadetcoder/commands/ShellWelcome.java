package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.ui.Glyphs;

import java.util.ArrayList;
import java.util.List;

/**
 * What the shell has on screen before anybody types: the banner, and a resumed session's
 * conversation.
 *
 * <h2>Why these are lines rather than printing</h2>
 *
 * <p>Both were written straight into the transcript from inside the shell's constructor, and that
 * constructor takes over the process's output routing -- so "does the banner still say how to reach
 * the help" could not be asked without starting a terminal UI. Returned as lines they are ordinary
 * values, and the shell's only remaining part in it is opening a region and appending them.</p>
 */
final class ShellWelcome {

    /** How many restored turns are replayed into the scrollback at most. */
    static final int REPLAYED_TURNS = 200;

    private ShellWelcome() {
    }

    /**
     * The opening banner.
     *
     * @param version    what this build calls itself
     * @param workingDir where commands will run
     * @param sessionId  which session this is, already resolved to something printable
     * @param glyphs     the marker set for this terminal
     * @param security   what this run has been forbidden; {@code null} means nothing is known and
     *                   therefore nothing is claimed
     * @return the lines, in order
     */
    static List<String> banner(String version, String workingDir, String sessionId, Glyphs glyphs,
                               Configuration.SecurityConfig security) {
        List<String> out = new ArrayList<>();
        out.add(glyphs.headerMarker() + " CadetCoder " + version + " " + glyphs.dash()
                + " AI-powered coding assistant" + glyphs.headerCloser());
        out.add("");
        out.add("Getting started:");
        out.add("  " + glyphs.bullet() + " Just type to talk to the AI " + glyphs.dash()
                + " e.g. \"explain how sessions are stored\"");
        out.add("  " + glyphs.bullet() + " Start with '/' to run a command " + glyphs.dash()
                + " '/help' is the whole reference, '/help <name>' explains one command");
        out.add("  " + glyphs.bullet()
                + " '/login' connects a provider, '/models select' picks a model");
        out.add("  " + glyphs.bullet() + " F1 help  " + glyphs.bullet()
                + "  F5 what is running  " + glyphs.bullet() + "  Tab view panes  "
                + glyphs.bullet() + "  Ctrl+L clear  " + glyphs.bullet() + "  Ctrl+Q quit");
        out.add("  " + glyphs.bullet() + " Drag to select, holding at an edge to reach further  "
                + glyphs.bullet() + "  F4 select mode");
        out.add("");
        out.add("Working dir: " + workingDir);
        out.add("Session: " + sessionId + "  " + glyphs.dash()
                + "  '/session list' to see earlier ones, '/session new' to start fresh");
        String restrictions = restrictionsIn(security);
        if (!restrictions.isEmpty()) {
            out.add(glyphs.warningMarker() + " Restricted: " + restrictions);
        }
        out.add("");
        return out;
    }

    /**
     * What this run has been forbidden, in one line.
     *
     * <h2>Why the banner carries this at all</h2>
     *
     * <p>Each of these changes what the tool will agree to do, and none of them used to leave a
     * mark on screen: a session started read-only looked exactly like an ordinary one until a write
     * was refused. They can also come from the configuration file rather than the command line, so
     * the user need not have chosen any of them in this run to be working under them -- which is
     * precisely the case where being told is worth a line.</p>
     *
     * <p>Absent when nothing is restricted. A line that is always there is a line nobody reads.</p>
     *
     * @param security what this run has been forbidden, or {@code null}
     * @return the restrictions, comma-separated, or {@code ""} when there are none
     */
    private static String restrictionsIn(Configuration.SecurityConfig security) {
        if (security == null) {
            return "";
        }
        List<String> on = new ArrayList<>(2);
        if (security.isReadOnlyMode()) {
            on.add("read-only (nothing in the project is changed)");
        }
        if (security.isSandboxMode()) {
            on.add("sandbox (bash limited to allowed programs)");
        }
        // Not security.allowOutsideProject: files are confined to the project by default, so that
        // restriction is the ordinary state rather than one worth a line.
        return String.join(", ", on);
    }

    /**
     * Renders a restored history as transcript lines.
     *
     * @param history the restored entries, recorded as {@code "User: ..."} / {@code "AI: ..."}
     * @param max     how many of the most recent turns to show
     * @param glyphs  the marker set for this terminal
     * @return the lines to append, empty when there is nothing to replay
     */
    static List<String> resumedConversation(List<String> history, int max, Glyphs glyphs) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int dropped = Math.max(0, history.size() - Math.max(1, max));

        List<String> out = new ArrayList<>();
        out.add(glyphs.headerMarker() + " Resumed conversation" + glyphs.headerCloser());
        if (dropped > 0) {
            // Stated rather than left to be inferred from a conversation that appears to begin
            // mid-sentence. The model still has these; only the scrollback is trimmed.
            out.add(dropped + " earlier turn"
                    + (dropped == 1 ? "" : "s") + " not shown; the model still has them.");
        }
        out.add("");
        for (String entry : history.subList(dropped, history.size())) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            // Recorded with a speaker prefix. Turned back into the marker the live transcript uses,
            // so a restored turn reads like one from this session rather than a quoted log of one.
            if (entry.startsWith("User: ")) {
                out.add("> " + entry.substring("User: ".length()));
            } else if (entry.startsWith("AI: ")) {
                out.add(entry.substring("AI: ".length()));
            } else {
                out.add(entry);
            }
            out.add("");
        }
        out.add("Session resumes here.");
        out.add("");
        return out;
    }
}
