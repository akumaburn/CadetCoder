package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.metrics.RequestMetrics;
import com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.ai.UberMode;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.timers.TimerRegistry;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.TuiTheme;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The shell's top line: which model is answering, on which branch, at what rate, and -- while a
 * request is in flight -- what it is doing.
 *
 * <h2>Why everything that moves is in one corner</h2>
 *
 * <p>Model, branch, rates and the live indicator all describe the same run, and they were split
 * across the two ends of the bar: the moving part at one end and the context it belongs to at the
 * other, so following a run meant reading both ends of one line at once. They are one group now,
 * and the application's name -- decoration that never changes -- is what gives way when the terminal
 * is too narrow to hold both.</p>
 *
 * <h2>Why the live indicator is budgeted first</h2>
 *
 * <p>Only the timing group used to be measured, so a long provider/model name ran the bar past its
 * own width and the paragraph widget clipped the far end -- the spinner and the timings, on exactly
 * the narrow terminal where they matter most. The indicator takes what it needs and the context
 * takes what is left.</p>
 */
final class ShellHeaderBar {

    /** How far up the tree to look for a repository before giving up. */
    private static final int GIT_SEARCH_DEPTH = 12;

    private final Glyphs glyphs;
    private final String appLabel;

    /** Cached because reading it means touching the filesystem, and a frame is drawn per tick. */
    private volatile String branch = "";

    /**
     * @param glyphs   what draws the separators, degraded for the terminal
     * @param appLabel the application's name and version, without padding
     */
    ShellHeaderBar(Glyphs glyphs, String appLabel) {
        if (glyphs == null || appLabel == null) {
            throw new IllegalArgumentException("the header bar needs glyphs and something to call the"
                                               + " application");
        }
        this.glyphs   = glyphs;
        this.appLabel = appLabel;
    }

    /** The branch as of the last refresh; empty outside a repository. */
    String branch() {
        return branch;
    }

    /** Re-reads the branch, which a command may have changed. */
    void refreshBranch() {
        branch = readGitBranch();
    }

    /**
     * Draws the bar.
     *
     * @param frame the frame being built
     * @param rect  the single row it occupies
     * @param theme what colours it
     * @param live  what the in-flight indicator says, given the columns it may use, or {@code null}
     *              when nothing is running
     */
    void render(Frame frame, Rect rect, TuiTheme theme, IntFunction<String> live) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        Style titleStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowTitle());
        Style ctxStyle   = ShellWidgets.nonNull(theme == null ? null : theme.getStatus());

        String right  = " " + rightGroup(rect.width(), live) + " ";
        int    rightW = Math.min(right.length(), rect.width());

        String padded = " " + appLabel + " ";
        int    leftW  = Math.max(0, rect.width() - rightW);
        String left   = leftW >= padded.length() ? padded : "";

        List<Rect> columns = Layout.horizontal()
                .constraints(Constraint.fill(), Constraint.length(rightW))
                .split(rect);

        frame.renderWidget(
                ShellWidgets.bar(ShellWidgets.padRightTo(left, columns.get(0).width()), titleStyle),
                columns.get(0));
        frame.renderWidget(ShellWidgets.bar(right, ctxStyle), columns.get(1));
    }

    /**
     * The right-hand group: model, branch, rates, and the live indicator beside them.
     *
     * @param width the columns available to the whole bar
     * @param live  what the in-flight indicator says, or {@code null} when nothing is running
     * @return the text, without the padding spaces {@link #render} adds
     */
    String rightGroup(int width, IntFunction<String> live) {
        int budget = Math.max(0, width - 2); // the spaces the caller pads the group with
        if (live == null) {
            return context(budget);
        }
        String separator = separator();
        String indicator = live.apply(budget);
        String context   = context(Math.max(0, budget - indicator.length() - separator.length()));
        return composeRight(context, indicator, separator);
    }

    /**
     * Joins the bar's right-hand pieces.
     *
     * @param context   the model and branch, always shown
     * @param live      the in-flight indicator, empty when idle
     * @param separator what goes between them
     * @return the joined text
     */
    static String composeRight(String context, String live, String separator) {
        String shown = context == null ? "" : context;
        if (live == null || live.isEmpty()) {
            return shown;
        }
        return shown.isEmpty() ? live : shown + separator + live;
    }

    /**
     * The stable half: which model is answering, on which branch, at what rate.
     *
     * @param width the columns available to this group
     * @return the text, empty when there is no room for even the model
     */
    String context(int width) {
        String provider = "";
        String model    = "";
        try {
            Configuration.AiConfig ai = ConfigManager.getInstance().getConfig().getAi();
            provider = ShellWidgets.nz(ai.getProvider());
            model    = ShellWidgets.nz(ai.getModel());
        } catch (Exception unavailable) {
            // No configuration to read; show what can be shown.
        }
        return fitContext(modes(), modelPart(provider, model), ShellWidgets.nz(branch),
                          sessionRates(), separator(), width,
                          text -> ShellWidgets.elide(text, width, glyphs.ellipsis()));
    }

    /**
     * What has been switched on that changes how a run behaves.
     *
     * <h2>Why these two are on the bar at all</h2>
     *
     * <p>All three are invisible otherwise and all three change what happens next. Uber mode
     * outlives the session it was turned on in, so without a standing reminder the first sign of it
     * is a run that will not stop when it is expected to. A timer is set by the model several steps
     * back and fires into a later prompt, so a run that suddenly changes subject has no explanation
     * on screen unless the count is somewhere -- and the count is also the only warning that one
     * was left running after the thing it was watching finished. A background job is a process on
     * this machine that outlives the step that started it: it is using the computer whether or not
     * anybody remembers it, and the count is the only sign of it between the line that started it
     * and the line that says it has ended.</p>
     *
     * <p>Words rather than symbols: this is a line people read at a glance and neither state has an
     * icon anyone would recognise, so a mark would have to be learned before it meant anything.</p>
     *
     * @return the modes in force, or an empty string when none are
     */
    static String modes() {
        StringBuilder shown = new StringBuilder();
        if (UberMode.isOn()) {
            shown.append("uber");
        }
        int timers = TimerRegistry.active().size();
        if (timers > 0) {
            if (shown.length() > 0) {
                shown.append(' ');
            }
            shown.append(timers).append(timers == 1 ? " timer" : " timers");
        }
        int jobs = JobRegistry.running().size();
        if (jobs > 0) {
            if (shown.length() > 0) {
                shown.append(' ');
            }
            shown.append(jobs).append(jobs == 1 ? " job" : " jobs");
        }
        return shown.toString();
    }

    /** How the provider and model are named together, and what stands in when neither is set. */
    private static String modelPart(String provider, String model) {
        if (!model.isEmpty() && !provider.isEmpty()) {
            return provider + "/" + model;
        }
        if (!model.isEmpty()) {
            return model;
        }
        return provider.isEmpty() ? "no model set" : provider;
    }

    private String separator() {
        return "  " + glyphs.bullet() + "  ";
    }

    /**
     * Fits the context group into the columns it has.
     *
     * <p>Pieces go in order of how easily the reader can do without them, and the LAST one standing
     * is the model. Clipping the joined text instead would have cut whichever piece happened to be
     * on the end -- which is the rates, then the branch, then the model's own name, so a narrow
     * terminal ended up showing the front half of {@code anthropic/claude-} and nothing else.</p>
     *
     * <p>The modes lead but are given up before the model, which is the one ordering that is right
     * in both directions: a mode nobody can see is a behaviour nobody expects, so it goes where the
     * eye starts; and a terminal too narrow for both is still more usefully told which model is
     * answering than that something is switched on.</p>
     *
     * @param modes     what has been switched on that changes how a run behaves; may be empty
     * @param modelPart the provider and model, or a stand-in when none is set
     * @param branch    the git branch; may be empty
     * @param rates     the throughput figures; may be empty
     * @param separator what goes between the pieces
     * @param width     the columns available
     * @param elide     shortens the model part when even that will not fit
     * @return the text to show
     */
    static String fitContext(String modes, String modelPart, String branch, String rates,
                             String separator, int width,
                             java.util.function.UnaryOperator<String> elide) {
        String everything = join(separator, modes, modelPart, branch, rates);
        if (everything.length() <= width) {
            return everything;
        }
        String withBranch = join(separator, modes, modelPart, branch);
        if (withBranch.length() <= width) {
            return withBranch;
        }
        String withModes = join(separator, modes, modelPart);
        if (withModes.length() <= width) {
            return withModes;
        }
        return modelPart.length() <= width ? modelPart : elide.apply(modelPart);
    }

    /** Joins the non-empty parts with {@code separator}. */
    private static String join(String separator, String... parts) {
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(part);
        }
        return joined.toString();
    }

    /**
     * Session throughput, aggregated across the shell and every worker.
     *
     * <p>Both halves come from one process-wide recorder, so a worker run's requests are counted
     * here exactly as the shell's own are -- which is the point: during a worker run the shell issues
     * no requests at all, and a rate that ignored workers would read zero while the session was at
     * its busiest.</p>
     *
     * <p>Shown only once something has actually been measured, so an idle session is not decorated
     * with two zeroes.</p>
     *
     * @return e.g. {@code "1.2k in/s  340 out/s"}, or empty when nothing has moved recently
     */
    static String sessionRates() {
        RequestMetricsRecorder recorder = RequestMetricsRecorder.getInstance();
        long in  = Math.round(recorder.windowInputTokensPerSecond());
        long out = Math.round(recorder.windowTokensPerSecond());
        if (in <= 0 && out <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        if (in > 0) {
            text.append(RequestMetrics.formatCompact(in)).append(" in/s");
        }
        if (out > 0) {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append(RequestMetrics.formatCompact(out)).append(" out/s");
        }
        return text.toString();
    }

    /**
     * The current git branch, read by walking up for a {@code .git/HEAD}.
     *
     * @return the branch name, a short hash on a detached HEAD, or empty outside a repository
     */
    static String readGitBranch() {
        try {
            Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            for (int i = 0; i < GIT_SEARCH_DEPTH && dir != null; i++) {
                Path head = dir.resolve(".git").resolve("HEAD");
                if (Files.exists(head)) {
                    String content = Files.readString(head).trim();
                    if (content.startsWith("ref:")) {
                        return content.substring(content.lastIndexOf('/') + 1);
                    }
                    return content.length() >= 7 ? content.substring(0, 7) : content;
                }
                dir = dir.getParent();
            }
        } catch (Exception unreadable) {
            // Best effort: a bar with no branch on it is better than a shell that will not draw.
        }
        return "";
    }
}
