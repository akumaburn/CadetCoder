package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.agents.WorkerRegistry;
import com.eonmux.cadetcoder.agents.WorkerRun;
import com.eonmux.cadetcoder.agents.WorkerTask;
import com.eonmux.cadetcoder.jobs.BackgroundJob;
import com.eonmux.cadetcoder.jobs.JobRegistry;
import com.eonmux.cadetcoder.ui.CollapsedOutput;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.MarkdownRenderer;
import com.eonmux.cadetcoder.ui.TextSelectionModel;
import com.eonmux.cadetcoder.ui.TuiTheme;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Draws the console: the live transcript, or whichever single result or worker has been focused.
 *
 * <h2>Why the console draws itself somewhere other than the shell</h2>
 *
 * <p>Painting the console is the largest single thing the shell did and the least to do with the
 * rest of it. It reads three collaborators -- the transcript, where the console is scrolled to, and
 * the glyph set -- and writes only into the frame it is handed, so it has no business sharing a
 * class with command execution, history, the clipboard and the process's output routing.</p>
 *
 * <h2>Why the mode flags arrive per frame instead of being held</h2>
 *
 * <p>A selection, select mode and the spinner's phase change between frames and are owned by whoever
 * handles input. Held here they would be a second copy that is right only until the next keystroke;
 * passed in as a {@link View} they are the values the frame is being drawn from, and the renderer
 * keeps no state that can disagree with the shell's.</p>
 */
final class ShellConsoleRenderer {

    /** What the shell prefixes a submitted line with when it records it. */
    private static final String ECHO_MARKER = "> ";

    private final ShellTranscript   transcript;
    private final ShellConsoleView  console;
    private final Glyphs            glyphs;

    /**
     * @param transcript what has been printed, oldest first
     * @param console    where the console is scrolled to and what is focused
     * @param glyphs     the marks this terminal can draw
     */
    ShellConsoleRenderer(ShellTranscript transcript, ShellConsoleView console, Glyphs glyphs) {
        this.transcript = transcript;
        this.console    = console;
        this.glyphs     = glyphs;
    }

    /**
     * What the console looks like this frame, beyond what the transcript and the scroll position
     * already say.
     *
     * @param selection    the cells a drag has selected, if any
     * @param selectMode   whether the mouse has been given over to selecting
     * @param spinnerFrame which phase a running mark is on
     */
    record View(TextSelectionModel selection, boolean selectMode, int spinnerFrame, String corner) {
    }

    /**
     * Draws the console into one region.
     *
     * @param frame the frame being built
     * @param rect  the region the console occupies
     * @param theme the active theme
     * @param view  the mode flags for this frame
     */
    void render(Frame frame, Rect rect, TuiTheme theme, View view) {
        if (console.isFocused() && renderFocusedResult(frame, rect, theme, view)) {
            return;
        }
        renderLiveTranscript(frame, rect, theme, view);
    }

    private void renderLiveTranscript(Frame frame, Rect rect, TuiTheme theme, View view) {
        Style borderStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBorder());
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style normalStyle = ShellWidgets.nonNull(theme == null ? null : theme.getTextNormal());

        List<Rect> region = ShellWidgets.titledRegion(rect);
        ShellWidgets.sectionTitle(frame, region.get(0),
                headingRow(consoleTitle(view), view.corner(), region.get(0).width()), borderStyle);

        Block block = ShellWidgets.consoleBlock(windowStyle);
        Rect  content  = region.get(1);
        Rect  textArea = block.inner(content);
        console.setViewportHeight(textArea.height());

        MarkdownRenderer md       = new MarkdownRenderer(theme, glyphs);
        List<Line>       lines    = new ArrayList<>();
        List<Integer>    lineSeg  = new ArrayList<>(); // segment id owning each rendered line
        boolean          firstSeg = true;
        for (ShellTranscript.Snapshot seg : transcript.snapshot()) {
            boolean isResult = seg.kind() == ShellTranscript.Kind.COMMAND;
            if (isResult) {
                if (!firstSeg) {
                    lines.add(Line.empty());
                    lineSeg.add(seg.id());
                }
                for (Line heading : resultHeading(seg, textArea.width(), theme, view)) {
                    lines.add(heading);
                    lineSeg.add(seg.id()); // the heading belongs to the result it introduces
                }
            }
            firstSeg = false;
            int before = lines.size();
            renderSegmentBody(lines, seg, md, theme, textArea.width(), false, isResult);
            for (int k = before; k < lines.size(); k++) {
                lineSeg.add(seg.id());
            }
        }

        int scroll = console.resolveScroll(lines.size(), textArea.height());

        // Publish the row -> segment map (for click-to-focus) and cache the raw text of every rendered
        // line (so a drag selection can be extracted for copying).
        int[] ids = new int[lineSeg.size()];
        for (int k = 0; k < ids.length; k++) {
            ids[k] = lineSeg.get(k);
        }
        List<String> raw = new ArrayList<>(lines.size());
        for (Line ln : lines) {
            raw.add(ln.rawContent());
        }
        console.publish(raw, ids, textArea.top(), textArea.left(), textArea.width(),
                        textArea.height(), scroll);

        frame.renderWidget(ShellWidgets.paragraph(lines, block, normalStyle, scroll), content);
        overlaySelectionHighlight(frame, textArea, scroll, theme, view);
    }

    /** Console heading: the region's name, plus a hint while in select mode. */
    private String consoleTitle(View view) {
        if (view.selectMode()) {
            return " Console " + glyphs.dash() + " SELECT MODE ";
        }
        return " Console ";
    }

    /**
     * The console's heading row: what the region is on the left, where you are in it on the right.
     *
     * <h2>Why the corner rather than the bottom line</h2>
     *
     * <p>"Following the tail", "scrolled back 40%" and "focused on result 3 of 7" are statements
     * about the console, and they were made a line below it and at the opposite end of the screen
     * from the text they describe. The corner of the region they belong to is where they are looked
     * for, and it leaves the bottom line to the keys alone.</p>
     *
     * @param title  the region's name, already spaced
     * @param corner where you are in it, or empty
     * @param width  the columns the row has
     * @return the row's text
     */
    static String headingRow(String title, String corner, int width) {
        String right = corner == null || corner.isBlank() ? "" : corner.strip() + " ";
        return ShellWidgets.composeBar(ShellWidgets.clipTo(title, width), right, width);
    }

    /**
     * The heading that introduces a result in the live stream, e.g. {@code ✓ > add a TLDR section}.
     *
     * <h2>Why the request is on the heading rather than under it</h2>
     *
     * <p>It used to be in both places. The heading carried the request cut to 24 columns and the
     * segment's first line echoed it in full, so every result opened with the same sentence twice,
     * once truncated -- and the truncated copy was the one wearing the status mark, so neither line
     * could be dropped without losing something. They are one line now: the mark, and the request as
     * it was typed, wrapped rather than cut. The echo is consumed from the body by
     * {@link #renderSegmentBody}, so a copied transcript still carries the request exactly once.</p>
     *
     * <p>Every result gets one, including the first in the transcript. Only the blank line above it
     * is skipped there, because there is nothing yet to be separated from.</p>
     *
     * @param seg   the result being introduced
     * @param width the columns the console has
     * @param theme what colours it
     * @param view  the frame's mode flags, for the spinner's phase
     * @return the heading's lines, in order
     */
    private List<Line> resultHeading(ShellTranscript.Snapshot seg, int width, TuiTheme theme,
                                     View view) {
        Style accentStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());
        if (width <= 0) {
            return List.of();
        }
        List<Line> heading = new ArrayList<>();
        for (String piece : headingText(statusMark(seg.status(), view.spinnerFrame()),
                                        echoedRequest(seg), width)) {
            heading.add(Line.from(List.of(Span.styled(piece, accentStyle))));
        }
        return heading;
    }

    /**
     * The heading's text, before anything is coloured or drawn.
     *
     * <p>Separated from the drawing because it is arithmetic about columns, and arithmetic that
     * lives inside a renderer can only be checked by starting a terminal.</p>
     *
     * <h2>Why the continuations are wrapped to their own width</h2>
     *
     * <p>Every line after the first is indented under the request rather than under the mark, so
     * the status column stays a column. That indent is part of the row, so those lines have fewer
     * columns for the request than the first line has. Wrapping the whole heading to the console's
     * width and indenting the fragments afterwards gave each of them more text than would fit, and
     * the indent's worth of characters at the end was clipped off -- off a heading which, since the
     * echo below it was dropped, is the only place the request is written down at all.</p>
     *
     * @param mark    the status mark and its trailing space, which may be empty
     * @param request the request as it was submitted, which may be empty
     * @param width   the columns the console has
     * @return the lines to draw, in order; empty when there is nothing to say
     */
    static List<String> headingText(String mark, String request, int width) {
        if (width <= 0) {
            return List.of();
        }
        String lead = mark == null ? "" : mark;
        String said = request == null ? "" : request;
        if (said.isEmpty()) {
            String label = lead.strip();
            return label.isEmpty() ? List.of() : List.of(ShellWidgets.clipTo(label, width));
        }
        int          indent = Math.min(CharWidth.of(lead), Math.max(0, width - 1));
        String       whole  = lead + said;
        String       first  = ShellWidgets.wrapOne(whole, width).get(0);
        List<String> pieces = new ArrayList<>();
        pieces.add(first);
        String rest = whole.substring(first.length()).stripLeading();
        if (!rest.isEmpty()) {
            for (String piece : ShellWidgets.wrapOne(rest, width - indent)) {
                pieces.add(" ".repeat(indent) + piece);
            }
        }
        return pieces;
    }

    /**
     * The line a result echoes its own request on, if it has one.
     *
     * <p>Read from the segment's first line rather than from its title, because the title is the
     * shortened form and this is the verbatim one. Falls back to the title for a restored session
     * whose echo has scrolled out of the segment's buffer.</p>
     *
     * @param seg the result
     * @return the request as it was submitted, or the empty string when the result has none
     */
    private static String echoedRequest(ShellTranscript.Snapshot seg) {
        String echo = echoLine(seg);
        if (echo != null) {
            return echo.strip();
        }
        String title = seg.title();
        return title == null ? "" : ECHO_MARKER + title.strip();
    }

    /**
     * The verbatim echo at the head of a result, or {@code null} when it has none.
     *
     * @param seg the result
     * @return the echo line as it was recorded
     */
    private static String echoLine(ShellTranscript.Snapshot seg) {
        if (seg.kind() != ShellTranscript.Kind.COMMAND || seg.lines().isEmpty()) {
            return null;
        }
        String first = seg.lines().get(0);
        return first != null && first.stripLeading().startsWith(ECHO_MARKER) ? first : null;
    }

    /** Overlay the active text selection on the live transcript by restyling the selected cells. */
    private void overlaySelectionHighlight(Frame frame, Rect textArea, int scroll, TuiTheme theme,
                                           View view) {
        TextSelectionModel selection = view.selection();
        if (selection == null || !selection.isActive() || selection.isEmptySelection()) {
            return;
        }
        Buffer buf = frame.buffer();
        if (buf == null) {
            return;
        }
        Style selStyle = ShellWidgets.nonNull(theme == null ? null : theme.getTextSelected());
        int rows  = textArea.height();
        int left  = textArea.left();
        int right = left + textArea.width();
        List<String> doc = console.documentLines();
        for (int i = 0; i < rows; i++) {
            int docRow = scroll + i;
            String text = (docRow >= 0 && docRow < doc.size()) ? doc.get(docRow) : "";
            int[] span = selection.spanForRow(docRow, text.length());
            if (span == null) {
                continue;
            }
            int x0 = Math.max(left, left + span[0]);
            int x1 = Math.min(right, left + span[1]);
            int w  = x1 - x0;
            if (w > 0) {
                buf.setStyle(new Rect(x0, textArea.top() + i, w, 1), selStyle);
            }
        }
    }

    /**
     * Append a segment's body lines: system chrome (welcome, history) keeps the simple
     * marker-based styling; command and sub-agent results are rendered as Markdown.
     *
     * <p>Command output arrives collapsed. The live stream leaves it out, because a run of a dozen
     * commands is followed by reading what each one DID rather than what each one printed; opening
     * the result shows all of it. See {@link CollapsedOutput}.</p>
     *
     * @param expanded              whether this is the focused view, which shows what the live one
     *                              leaves out
     * @param headingCarriesTheEcho whether the caller has already drawn this result's request line
     */
    private static void renderSegmentBody(List<Line> out, ShellTranscript.Snapshot seg,
                                          MarkdownRenderer md, TuiTheme theme, int width,
                                          boolean expanded, boolean headingCarriesTheEcho) {
        List<String> lines = bodyLines(seg, expanded, headingCarriesTheEcho);
        if (seg.kind() == ShellTranscript.Kind.SYSTEM) {
            ShellWidgets.styledLines(out, lines, theme, width);
        } else {
            out.addAll(md.render(lines, width));
        }
    }

    /**
     * The lines of a segment that this view draws.
     *
     * @param seg                   the segment
     * @param expanded              whether this is the focused view, which shows the collapsed
     *                              output the live one leaves out
     * @param headingCarriesTheEcho whether the caller has already drawn this result's request line
     * @return the lines, in order
     */
    static List<String> bodyLines(ShellTranscript.Snapshot seg, boolean expanded,
                                  boolean headingCarriesTheEcho) {
        List<String> lines = expanded ? CollapsedOutput.expanded(seg.lines())
                                      : CollapsedOutput.visible(seg.lines());
        if (headingCarriesTheEcho && echoLine(seg) != null && !lines.isEmpty()) {
            return lines.subList(1, lines.size());
        }
        return lines;
    }

    /**
     * Render just the focused result/section. Returns {@code false} (so the caller falls back to the
     * live transcript) if the focused segment no longer exists.
     */
    private boolean renderFocusedResult(Frame frame, Rect rect, TuiTheme theme, View view) {
        if (console.focusedPane() != null && renderBackgroundPane(frame, rect, theme, view)) {
            return true;
        }
        ShellTranscript.Snapshot focused = null;
        int index = -1;
        int total = 0;
        List<ShellTranscript.Snapshot> snap = transcript.snapshot();
        for (ShellTranscript.Snapshot seg : snap) {
            total++;
            if (seg.id() == console.focusedSegment()) {
                focused = seg;
                index   = total; // 1-based
            }
        }
        if (focused == null) {
            console.loseFocus();
            return false;
        }

        Style borderStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style normalStyle = ShellWidgets.nonNull(theme == null ? null : theme.getTextNormal());

        String kindMark = focused.kind() == ShellTranscript.Kind.SECTION
                          ? glyphs.sectionMark() + " " : "";
        String title = " " + glyphs.focusMark() + " " + kindMark
                + statusMark(focused.status(), view.spinnerFrame())
                + ShellWidgets.safeTitle(focused.title(), glyphs.ellipsis())
                + "  (" + index + "/" + total + ") ";
        List<Rect> region = ShellWidgets.titledRegion(rect);
        ShellWidgets.sectionTitle(frame, region.get(0),
                headingRow(title, view.corner(), region.get(0).width()), borderStyle);

        Block block    = ShellWidgets.consoleBlock(windowStyle);
        Rect  content  = region.get(1);
        Rect  textArea = block.inner(content);
        console.setViewportHeight(textArea.height());

        List<Line> lines = new ArrayList<>();
        renderSegmentBody(lines, focused, new MarkdownRenderer(theme, glyphs), theme,
                          textArea.width(), true, false);

        int scroll = console.resolveFocusScroll(lines.size(), textArea.height());

        frame.renderWidget(ShellWidgets.paragraph(lines, block, normalStyle, scroll), content);
        return true;
    }

    /**
     * Renders whichever piece of background work is focused.
     *
     * @return whether one was rendered; {@code false} when what was focused is no longer there
     */
    private boolean renderBackgroundPane(Frame frame, Rect rect, TuiTheme theme, View view) {
        BackgroundPane pane = console.focusedPane();
        return switch (pane.kind()) {
            case OVERVIEW -> renderOverview(frame, rect, theme, view);
            case WORKER -> renderFocusedWorker(frame, rect, theme, view);
            case JOB -> renderFocusedJob(frame, rect, theme, view);
        };
    }

    /**
     * Renders the list of every worker and every job.
     *
     * <p>Always renders, even with nothing to list. A list that vanished when the last job ended
     * would drop the reader back into the transcript at the moment they were reading why it
     * ended.</p>
     *
     * @return {@code true}, always
     */
    private boolean renderOverview(Frame frame, Rect rect, TuiTheme theme, View view) {
        Style borderStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style normalStyle = ShellWidgets.nonNull(theme == null ? null : theme.getTextNormal());

        List<BackgroundPanes.Row> rows = BackgroundPanes.rows();
        String title = " " + glyphs.focusMark() + " Background work  ("
                       + rows.size() + ") ";
        List<Rect> region = ShellWidgets.titledRegion(rect);
        ShellWidgets.sectionTitle(frame, region.get(0),
                headingRow(title, view.corner(), region.get(0).width()), borderStyle);

        Block block    = ShellWidgets.consoleBlock(windowStyle);
        Rect  content  = region.get(1);
        Rect  textArea = block.inner(content);
        console.setViewportHeight(textArea.height());

        List<Line> lines = new MarkdownRenderer(theme, glyphs)
                .render(overviewLines(rows, console.overviewRow()), textArea.width());
        int scroll = console.resolveFocusScroll(lines.size(), textArea.height());

        frame.renderWidget(ShellWidgets.paragraph(lines, block, normalStyle, scroll), content);
        return true;
    }

    /**
     * The overview's text, headed by kind and with one line picked out.
     *
     * @param rows     what there is to list
     * @param selected which line is picked out
     * @return the lines to draw
     */
    List<String> overviewLines(List<BackgroundPanes.Row> rows, int selected) {
        if (rows.isEmpty()) {
            return List.of("Nothing is running.",
                           "",
                           "`agent` starts workers; `job start <command>` starts a job.");
        }
        List<String> lines  = new ArrayList<>();
        boolean      onJobs = false;
        for (int i = 0; i < rows.size(); i++) {
            BackgroundPanes.Row row = rows.get(i);
            if (lines.isEmpty()) {
                lines.add(row.pane().isJob() ? "Jobs" : "Workers");
                onJobs = row.pane().isJob();
            } else if (row.pane().isJob() && !onJobs) {
                lines.add("");
                lines.add("Jobs");
                onJobs = true;
            }
            lines.add((i == selected ? " " + glyphs.focusMark() + " " : "   ")
                      + mark(row.liveness(), 0) + " "
                      + ShellWidgets.safeTitle(row.name(), glyphs.ellipsis()) + "  "
                      + row.state() + "  " + row.detail());
        }
        return lines;
    }

    /**
     * @param liveness    how a piece of work is doing
     * @param spinnerFrame which phase a running mark is on
     * @return the mark for it
     */
    private String mark(BackgroundPanes.Liveness liveness, int spinnerFrame) {
        return switch (liveness) {
            case RUNNING -> glyphs.spinner(spinnerFrame);
            case DONE -> glyphs.ok();
            case STOPPED -> glyphs.bullet();
            case FAILED -> glyphs.dash();
        };
    }

    /**
     * Renders one background job's output, live.
     *
     * <p>Read through {@code job.output().since(...)}, which leaves the job's own cursor alone.
     * {@code BackgroundJob.readNew} would not: there is one cursor per job, so watching a job here
     * would consume the lines {@code job output} and the model's own notice were going to show.
     * Watching something must not change it.</p>
     *
     * @return whether a job was rendered; {@code false} when the focused id is no longer a job
     */
    private boolean renderFocusedJob(Frame frame, Rect rect, TuiTheme theme, View view) {
        String id = console.focusedPane().job();
        Optional<BackgroundJob> maybeJob = JobRegistry.find(id);
        if (maybeJob.isEmpty()) {
            console.forgetPane();
            return false;
        }
        BackgroundJob job = maybeJob.get();

        Style borderStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style normalStyle = ShellWidgets.nonNull(theme == null ? null : theme.getTextNormal());

        List<BackgroundPane> panes = BackgroundPanes.all();
        int position = panes.indexOf(console.focusedPane()) + 1;
        String state = BackgroundPanes.liveness(job) == BackgroundPanes.Liveness.RUNNING
                       ? glyphs.spinner(view.spinnerFrame())
                       : mark(BackgroundPanes.liveness(job), view.spinnerFrame());
        String title = " " + glyphs.focusMark() + " " + state + " " + job.id() + "  "
                + BackgroundPanes.state(job) + " " + BackgroundPanes.spoken(job.runtime()) + " "
                + glyphs.dash() + " " + ShellWidgets.safeTitle(job.command(), glyphs.ellipsis())
                + "  (" + Math.max(1, position) + "/" + panes.size() + ") ";

        List<Rect> region = ShellWidgets.titledRegion(rect);
        ShellWidgets.sectionTitle(frame, region.get(0), title, borderStyle);

        Block block    = ShellWidgets.consoleBlock(windowStyle);
        Rect  content  = region.get(1);
        Rect  textArea = block.inner(content);
        console.setViewportHeight(textArea.height());

        List<String> output = job.output().since(0, 0);
        if (output.isEmpty()) {
            // Distinguished, because they mean opposite things: one job is still warming up and the
            // other has finished with nothing to say.
            output = List.of(job.isDone() ? "This job produced no output."
                                          : "Started; nothing printed yet.");
        }
        List<Line> lines = new MarkdownRenderer(theme, glyphs).render(output, textArea.width());

        int scroll = console.resolveFocusScroll(lines.size(), textArea.height());

        frame.renderWidget(ShellWidgets.paragraph(lines, block, normalStyle, scroll), content);
        return true;
    }

    /**
     * Renders one worker's transcript, live.
     *
     * <p>The lines are the worker's own, verbatim: they already carry the markers an agent run
     * prints, and re-decorating them here would stamp a second glyph onto every line of a nested
     * run. The heading carries what the lines cannot say for themselves — which worker this is, what
     * it was asked to do, and whether it is still going.</p>
     *
     * @return whether a worker was rendered; {@code false} when the focused number no longer exists
     */
    private boolean renderFocusedWorker(Frame frame, Rect rect, TuiTheme theme, View view) {
        int worker = console.focusedPane().worker();
        Optional<WorkerRun> maybeRun = WorkerRegistry.mostRecent();
        if (maybeRun.isEmpty()) {
            console.forgetPane();
            return false;
        }
        WorkerRun run = maybeRun.get();
        Optional<WorkerTask> maybeTask = run.taskFor(worker);
        if (maybeTask.isEmpty()) {
            console.forgetPane();
            return false;
        }
        WorkerTask task = maybeTask.get();

        Style borderStyle = ShellWidgets.nonNull(theme == null ? null : theme.getAccent1());
        Style windowStyle = ShellWidgets.nonNull(theme == null ? null : theme.getWindowBackground());
        Style normalStyle = ShellWidgets.nonNull(theme == null ? null : theme.getTextNormal());

        List<BackgroundPane> panes = BackgroundPanes.all();
        int position = panes.indexOf(console.focusedPane()) + 1;
        String state = run.resultFor(worker)
                .map(r -> switch (r.status()) {
                    case COMPLETED -> glyphs.ok();
                    case INTERRUPTED -> glyphs.bullet();
                    default -> glyphs.dash();
                })
                .orElseGet(() -> glyphs.spinner(view.spinnerFrame()));
        String title = " " + glyphs.focusMark() + " " + state + " " + task.name() + " "
                + glyphs.dash() + " " + ShellWidgets.safeTitle(task.label(), glyphs.ellipsis())
                + "  (" + Math.max(1, position) + "/" + panes.size() + ") ";

        List<Rect> region = ShellWidgets.titledRegion(rect);
        ShellWidgets.sectionTitle(frame, region.get(0), title, borderStyle);

        Block block    = ShellWidgets.consoleBlock(windowStyle);
        Rect  content  = region.get(1);
        Rect  textArea = block.inner(content);
        console.setViewportHeight(textArea.height());

        List<String> output = CollapsedOutput.expanded(run.outputSoFar(worker));
        if (output.isEmpty()) {
            // Distinguished, because they mean opposite things: one worker is still warming up and
            // the other has finished with nothing to say.
            output = List.of(run.resultFor(worker).isPresent()
                             ? "This worker produced no output."
                             : "Started; nothing printed yet.");
        }
        List<Line> lines = new MarkdownRenderer(theme, glyphs).render(output, textArea.width());

        int scroll = console.resolveFocusScroll(lines.size(), textArea.height());

        frame.renderWidget(ShellWidgets.paragraph(lines, block, normalStyle, scroll), content);
        return true;
    }

    /**
     * The mark that says how a result ended, or that it has not.
     *
     * @param status       the result's state, or {@code null} when it has none
     * @param spinnerFrame which phase a running mark is on
     * @return the mark and a trailing space, or empty when nothing is worth marking
     */
    String statusMark(ShellTranscript.Status status, int spinnerFrame) {
        if (status == null) {
            return "";
        }
        switch (status) {
            case OK:
                return glyphs.ok() + " ";
            case ERROR:
                return glyphs.error() + " ";
            case RUNNING:
                return glyphs.spinner(spinnerFrame) + " ";
            case NONE:
            default:
                return "";
        }
    }
}
