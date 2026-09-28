package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.agents.WorkerRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.DebugLogger;
import com.eonmux.cadetcoder.logging.SessionLogger;
import com.eonmux.cadetcoder.session.SessionManager;
import com.eonmux.cadetcoder.ui.AnsiStripper;
import com.eonmux.cadetcoder.ui.Glyphs;
import com.eonmux.cadetcoder.ui.InputHandler;
import com.eonmux.cadetcoder.ui.OutputRouter;
import com.eonmux.cadetcoder.ui.TextSelectionModel;
import com.eonmux.cadetcoder.ui.TuiTheme;
import com.eonmux.cadetcoder.ui.TuiThemeManager;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.MouseEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.tui.event.TickEvent;
import dev.tamboui.widgets.input.TextInputState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Interactive shell rendered with the TamboUI immediate-mode TUI framework.
 *
 * <p>The shell drives a {@link TuiRunner}: every frame is rebuilt from the shell's state by
 * {@link #render(Frame)} and input is handled by {@link #handleEvent(Event, TuiRunner)}. Command
 * output captured from {@code System.out}/{@code System.err} (via {@link OutputRouter}) is appended
 * to a thread-safe {@link ShellTranscript} and surfaced on the next render tick.</p>
 *
 * <p>Output is organised as a <b>transcript of result segments</b> — one per command — and any
 * sub-header a command prints (an AI agent's {@code -- Step n of m --}, say) opens a nested section.
 * This drives two display modes:</p>
 * <ul>
 *   <li><b>Live mode</b> (default) — the whole transcript scrolls together and auto-follows the
 *       newest output, with a thin rule separating consecutive results.</li>
 *   <li><b>Focus mode</b> ({@code Tab}/{@code Shift+Tab}) — a single result (or sub-agent section)
 *       fills the console and scrolls in isolation; {@code Esc} returns to live mode.</li>
 * </ul>
 *
 * <p>The interface is composed of four regions plus a modal help overlay: a header bar
 * ({@code provider/model} + git branch), the console, a mode-aware status bar, and a prompt-aware
 * input line. Glyphs degrade to ASCII on non-Unicode terminals ({@link Glyphs}).</p>
 */
public class InteractiveShell implements InputHandler {

    private static final String APP_VERSION        = "v1.0";
    private static final int    MAX_SEGMENTS       = 500;
    private static final int    PER_SEGMENT_LINES  = 4000;

    /** Unique sentinel (compared by identity) used to cancel a pending prompt on interrupt/quit. */

    private final CommandRegistry registry;
    private final SessionManager  sessionManager;
    private final CommandHistory  commandHistory;
    private       int             historyIndex;

    /**
     * Command names already announced by {@link #noteShadowedCommand(String)}, so the "commands start
     * with '/'" hint appears once per name rather than on every message.
     */
    private final Set<String> shadowHintsShown = new HashSet<>();

    private final Glyphs glyphs = Glyphs.system();

    /** The modal help panel: whether it is up, how far it is scrolled, and what it says. */
    private final ShellHelp help;

    /** The top line: model, branch, rates, and what is running. */
    private final ShellHeaderBar header;

    /** The bottom region: what is being typed, and what it is being typed in answer to. */
    private final ShellInputLine inputLine;

    /** The keys that move the console, under a divider of their own. */
    private final ShellStatusBar status;

    /**
     * What the status line says between pressing Enter and the running command printing its first
     * sub-header. Resolved once, at Enter, because the label must not depend on execution having
     * started.
     */
    private volatile String initialActivity = "";

    // Output transcript (mutated from background command threads and the render thread)
    private final ShellTranscript transcript = new ShellTranscript(MAX_SEGMENTS, PER_SEGMENT_LINES);

    // Input state and the TamboUI runner
    private final TextInputState inputState = new TextInputState();

    /** What the markers in the input line stand for; see {@link ShellPastes}. */
    private final ShellPastes pastes = new ShellPastes();
    private volatile TuiRunner   runner;

    /** What the shell asks of the terminal itself: mouse ownership, and the clipboard. */
    private final ShellTerminal terminal = new ShellTerminal(() -> {
        TuiRunner active = runner;
        return active == null ? null : active.backend();
    });

    /**
     * Where the console is: how far the transcript is scrolled, what is focused, and what the last
     * frame put on which row.
     *
     * <p>Tab cycles WORKERS AND JOBS rather than transcript sections. Sections are one per command
     * and one per sub-heading, and an agent loop emits a sub-heading per iteration, so Tab used to
     * walk "Iteration 1, Iteration 2, ..." past the thing anyone actually wanted to look at. Both
     * kinds of background work are the case with no other route at all: a worker's output is
     * collected per worker instead of streamed, a job's belongs to another process entirely, and
     * {@code workers show <n>} and {@code job output <id>} both need a free prompt -- which during
     * a blocking run means interrupting the very thing you wanted to watch. F5 lists them
     * together.</p>
     */
    private final ShellConsoleView console = new ShellConsoleView();

    /** Draws whichever of the two console views is current. */
    private final ShellConsoleRenderer consoleRenderer =
            new ShellConsoleRenderer(transcript, console, glyphs);

    // Text selection (live transcript): a drag selects cells; release copies them to the clipboard.
    private volatile TextSelectionModel selection = TextSelectionModel.EMPTY;
    private volatile boolean            selecting = false; // a left-button drag is in progress
    private          int                pressX    = -1;    // where the left button went down...
    private          int                pressY    = -1;    // ...so a no-drag release is treated as a click
    private volatile int                dragX     = -1;    // where the pointer is now, so a drag held
    private volatile int                dragY     = -1;    // ...at an edge keeps scrolling on each tick

    // "Select mode" (F4): the mouse is given over to selecting, so a mis-click does not open a result.
    private volatile boolean selectMode = false;

    // Transient status flash (e.g. "copied N chars"), shown for a few ticks then cleared.
    private volatile String flashMessage = null;
    private volatile int    flashTicks   = 0;

    // Redraw + processing state
    private volatile boolean dirty               = false;
    private volatile boolean processing          = false;
    private volatile String  currentCommand      = null;
    private volatile int     spinnerFrame        = 0;
    private volatile boolean interruptRequested  = false;
    /** Set between a Ctrl+C that asked whether to quit and the press that answers it. */
    private volatile boolean quitAsked           = false;

    private volatile ShellTranscript.Segment currentSegment;

    /**
     * The shell's single input line, when a command needs an answer from the person at the terminal.
     *
     * <p>Extracted: this was three loose fields with nothing tying them together, and two threads
     * asking at once overwrote one another and stranded the loser. See {@link PromptHandshake}.</p>
     */
    private final PromptHandshake promptHandshake = new PromptHandshake();

    public InteractiveShell() throws Exception {
        try {
            this.registry       = new CommandRegistry();
            this.sessionManager = SessionManager.getInstance();
            this.help           = new ShellHelp(glyphs, registry);
            this.header         = new ShellHeaderBar(glyphs, "CadetCoder " + APP_VERSION);
            this.status         = new ShellStatusBar(glyphs);
            this.inputLine      = new ShellInputLine(glyphs);

            // Setup history file
            String baseDir = ConfigManager.getInstance().getConfig().getBaseDir();
            Path   logsDir = Paths.get(baseDir, "logs");
            try {
                if (!Files.exists(logsDir)) {
                    Files.createDirectories(logsDir);
                }
            } catch (IOException e) {
                logsDir = Paths.get(System.getProperty("user.home"), ".cadet");
            }
            this.commandHistory = new CommandHistory(logsDir.resolve("history.log"));
            this.historyIndex   = commandHistory.size();

            // Configure the theme from the user's configuration
            TuiThemeManager.applyConfiguredTheme();

            // Prime the header-bar context
            this.header.refreshBranch();

            // Seed the output area with the welcome text
            displayWelcome();

            // ...and, on a resumed session, with what was on screen before it.
            replayRestoredConversation();

            // Saving pulls the scrollback from here; see SessionManager#setTranscriptSource.
            sessionManager.setTranscriptSource(
                    () -> transcript.persistable(PERSISTED_SEGMENTS, PERSISTED_LINES));

            // Route captured System.out/err into this shell's output buffer
            OutputRouter.getInstance().setShell(this);
            OutputRouter.getInstance().startRouting();

            SessionLogger.getInstance().logSessionEvent("InteractiveShell", "TUI session initialized successfully");
        } catch (Exception e) {
            try {
                OutputRouter.getInstance().stopRouting();
            } catch (Exception cleanupEx) {
                // Initialization already failed; nothing more we can do here
            }
            throw e;
        }
    }

    private void displayWelcome() {
        String sessionId = sessionManager.getCurrentSessionId();
        // Ephemeral: the shell prints this at every start, so carrying it in the saved
        // scrollback would stack another copy on the ones already there at every resume.
        transcript.beginEphemeralSegment(ShellTranscript.Kind.SYSTEM, "Welcome");
        for (String line : ShellWelcome.banner(APP_VERSION, System.getProperty("user.dir"),
                                               sessionId == null ? "new session" : sessionId,
                                               glyphs,
                                               ConfigManager.getInstance().getConfig().getSecurity())) {
            appendOutput(line);
        }
    }

    /**
     * How much scrollback is carried across a restart.
     *
     * <p>Far smaller than what is held live. The session file is rewritten after every exchange, so
     * what goes in it is bounded by what is worth writing that often, not by what the shell can
     * display — and the newest regions are the ones a reopened session is for.</p>
     */
    private static final int PERSISTED_SEGMENTS = 40;
    private static final int PERSISTED_LINES    = 2_000;

    /**
     * Puts a resumed session's conversation back on screen.
     *
     * <p>{@code --continue} restored the conversation into the MODEL's context and nowhere else, so
     * the shell opened on an empty transcript: the model knew what had been discussed and the person
     * it was talking to had nothing to scroll back through. Everything needed was already loaded and
     * simply never shown.</p>
     *
     * <p>Replayed under its own heading, and bounded. A restored history runs to whatever share of
     * the context window the resume budget allows, and pouring all of it into the scrollback would
     * push the banner — the part saying which session this even is — out of reach on the way in.
     * When turns are dropped, the count is stated rather than left to be inferred from a
     * conversation that appears to begin mid-sentence.</p>
     */
    private void replayRestoredConversation() {
        if (!sessionManager.isResumed()) {
            return;
        }

        // The saved scrollback is the faithful form: raw lines, restored into real segments, so it
        // renders at this terminal's width in the theme in force now and reads exactly like the
        // session that was closed -- commands, their output and all.
        List<com.eonmux.cadetcoder.session.TranscriptEntry> saved = sessionManager.getTranscript();
        if (!saved.isEmpty()) {
            transcript.restore(saved);
            transcript.beginEphemeralSegment(ShellTranscript.Kind.SYSTEM, "Resumed");
            appendOutput("Session resumes here.");
            appendOutput("");
            return;
        }

        // Nothing saved: a session from before transcripts were kept, or one that only ever ran
        // outside the shell. The conversation is still there, so show that rather than nothing.
        List<String> lines = ShellWelcome.resumedConversation(
                sessionManager.getConversationHistory(), ShellWelcome.REPLAYED_TURNS, glyphs);
        if (lines.isEmpty()) {
            return;
        }
        transcript.beginSegment(ShellTranscript.Kind.SYSTEM, "Resumed conversation");
        for (String line : lines) {
            appendOutput(line);
        }
    }


    /**
     * Run the interactive shell event loop. Blocks until the user quits.
     *
     * @return 0 on a clean exit, 1 on a fatal error
     */
    public int runShell() {
        try {
            TuiConfig config = TuiConfig.builder()
                    .rawMode(true)
                    .alternateScreen(true)
                    .hideCursor(false)
                    .mouseCapture(true)
                    .bracketedPaste(true)
                    .tickRate(Duration.ofMillis(100))
                    .build();

            try (TuiRunner r = TuiRunner.create(config)) {
                this.runner = r;
                r.run(this::handleEvent, this::render);
            } finally {
                this.runner = null;
            }
            return 0;
        } catch (Exception e) {
            // Restore the console before reporting, so the error is visible
            try {
                OutputRouter.getInstance().stopRouting();
            } catch (Exception ignored) {
                // best effort
            }
            OutputFormatter.printError("Interactive shell error: " + e.getMessage());
            return 1;
        } finally {
            // Every step here catches Throwable, not Exception. Shutdown is exactly where a
            // LinkageError shows up -- a class not loaded until now cannot be loaded any more -- and
            // an Error is not an Exception, so these guards let it past and the shell exited with a
            // stack trace instead of exiting. Nothing in this block is worth failing the exit for.
            cleanUp("log session end",
                    () -> SessionLogger.getInstance().logSessionEvent("InteractiveShell",
                                                                      "TUI session ending"));
            cleanUp("restore the console", () -> OutputRouter.getInstance().stopRouting());
            cleanUp("save the session", sessionManager::saveSession);
            cleanUp("log session cleanup",
                    () -> SessionLogger.getInstance().logSessionEvent("InteractiveShell",
                                                                      "Session cleanup completed"));
        }
    }

    /**
     * Runs one shutdown step, absorbing anything it throws.
     *
     * <p>{@link Throwable}, not {@link Exception}: the failures that reach a cleanup block are as
     * likely to be {@link Error}s as exceptions, and none of them is a reason to abandon the rest of
     * the shutdown or to end the session with a stack trace. What went wrong is recorded in the
     * debug log, where it can be read afterwards.</p>
     *
     * @param what a short description of the step, for the log
     * @param step the step to run
     */
    private static void cleanUp(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            try {
                DebugLogger.getInstance().error("InteractiveShell", "Could not " + what + " on exit",
                                                t instanceof Exception ? (Exception) t
                                                                       : new RuntimeException(t));
            } catch (Throwable ignored) {
                // The logger itself is not worth failing the exit for either.
            }
        }
    }

    // ---------------------------------------------------------------------
    // Event handling (runs on the TamboUI render thread)
    // ---------------------------------------------------------------------

    /** What the shell does with a line typed while it may or may not be busy. */
    enum Admission { RUN, REFUSE_STILL_RUNNING, REFUSE_STILL_STOPPING }

    /**
     * Decides whether a newly typed line may start now.
     *
     * <p>Busy is busy. This used to admit a line as soon as an interrupt had been REQUESTED, which
     * is not the same as the command having stopped: {@link #startProcessing} would then clear
     * {@code interruptRequested} and the shared {@link com.eonmux.cadetcoder.InterruptSignal},
     * withdrawing
     * the interrupt before the first command could observe it, and two commands would run at once
     * over the same singleton {@code Command} objects. Commands that never poll for interruption
     * made the window last as long as they did.</p>
     *
     * <p>Static and pure so the decision is testable without standing up a TUI, as the key
     * classifications in {@link ShellKeys} are.</p>
     *
     * @param processing         whether a command thread is still running
     * @param interruptRequested whether that command has already been asked to stop
     * @return what to do with the line
     */
    static Admission classifyNewCommand(boolean processing, boolean interruptRequested) {
        if (!processing) {
            return Admission.RUN;
        }
        return interruptRequested ? Admission.REFUSE_STILL_STOPPING : Admission.REFUSE_STILL_RUNNING;
    }

    private boolean handleEvent(Event event, TuiRunner r) {
        if (event instanceof TickEvent) {
            boolean redraw = false;
            if (processing) {
                spinnerFrame++;
                redraw = true; // animate the spinner and pick up streamed output
            }
            if (flashTicks > 0) {
                flashTicks--;
                if (flashTicks == 0) {
                    flashMessage = null;
                }
                redraw = true; // refresh (and eventually clear) the status flash
            }
            if (dragHeldAtEdge()) {
                redraw = true; // a drag pinned to an edge keeps reaching further on its own
            }
            if (console.isFocused() && console.focusedPane() != null) {
                // A worker's transcript and a job's output both grow without touching `dirty`:
                // nothing in this process writes them, and a job is a separate process entirely. So
                // a pane watching live work froze on screen the moment the shell went idle. Redrawn
                // on the tick instead, which also turns the spinner in the heading.
                redraw = true;
            }
            if (dirty) {
                dirty = false;
                redraw = true;
            }
            return redraw;
        }

        if (event instanceof ResizeEvent) {
            return true; // re-render against the new terminal dimensions
        }

        if (event instanceof MouseEvent) {
            // The overlay is modal for the mouse too. It was modal only for keys, so a wheel scroll
            // moved the transcript hidden behind it and a drag started a selection against the hit
            // map of a view nobody could see -- releasing it copied text chosen at random.
            if (help.isVisible()) {
                boolean scrolled = help.onMouse((MouseEvent) event, ShellPointer.WHEEL_LINES);
                dirty |= scrolled;
                return scrolled;
            }
            return handleMouse((MouseEvent) event);
        }

        if (event instanceof PasteEvent) {
            if (help.isVisible()) {
                return false; // typing into a field that is not on screen
            }
            String pasted = ((PasteEvent) event).text();
            if (pasted != null && !pasted.isEmpty()) {
                inputState.insert(pastes.insertionFor(pasted));
                dirty = true;
            }
            return true;
        }

        if (!(event instanceof KeyEvent)) {
            return false;
        }
        KeyEvent k = (KeyEvent) event;

        // The help overlay is modal.
        if (help.isVisible()) {
            help.onKey(k, console.page());
            dirty = true;
            return true;
        }
        ShellKeys.Action action = ShellKeys.classify(k, keyState());
        if (ShellKeys.endsTheQuestion(k)) {
            quitAsked = false;
        }
        return perform(action, k);
    }

    /** The part of the shell's state a keystroke's meaning depends on. */
    private ShellKeys.State keyState() {
        return new ShellKeys.State(processing, promptHandshake.isActive(),
                                   selection.isActive() && !selection.isEmptySelection(),
                                   quitAsked);
    }

    /**
     * Confirms that Ctrl+C is meant to end the session, and remembers that it asked.
     *
     * <p>Said in the transcript rather than as a status line, so it survives whatever is redrawn
     * next and is still on screen when the second press arrives.</p>
     */
    private void askWhetherToQuit() {
        quitAsked = true;
        appendOutput(glyphs.warningMarker() + " Press Ctrl+C again to exit, or Ctrl+Q. "
                     + "Anything else carries on.");
        dirty = true;
    }

    /**
     * Carries out what a keystroke meant.
     *
     * @param action what it meant
     * @param k      the keystroke, for the one action that is the keystroke itself
     * @return whether it was consumed
     */
    private boolean perform(ShellKeys.Action action, KeyEvent k) {
        if (action == ShellKeys.Action.NONE) {
            return handleTextEditing(k);
        }
        if (!changeMode(action)) {
            moveAbout(action);
        }
        return true;
    }

    /**
     * The keys that change what mode the shell is in.
     *
     * @param action what the keystroke meant
     * @return whether it was one of these
     */
    private boolean changeMode(ShellKeys.Action action) {
        switch (action) {
            case IGNORE:
                return true;
            case OPEN_HELP:
                help.open();
                dirty = true;
                return true;
            case DISMISS:
                dismiss();
                return true;
            case INTERRUPT:
                requestInterrupt();
                return true;
            case SHOW_HISTORY:
                showCommandHistory();
                return true;
            case TOGGLE_SELECT_MODE:
                setSelectMode(!selectMode);
                return true;
            case COPY_SELECTION:
                copySelectionToClipboard();
                return true;
            case COPY_SELECTION_THEN_CLEAR:
                copySelectionToClipboard();
                clearSelection();
                return true;
            case CONFIRM_QUIT:
                askWhetherToQuit();
                return true;
            case QUIT:
                requestQuit();
                return true;
            case CLEAR_OUTPUT:
                clearConsole();
                return true;
            case CLEAR_INPUT:
                clearInput();
                return true;
            default:
                return false;
        }
    }

    /** The keys that move around the console, or send what has been typed. */
    private void moveAbout(ShellKeys.Action action) {
        switch (action) {
            case FOCUS_NEXT:
                enterOrMoveFocus(1);
                break;
            case FOCUS_PREVIOUS:
                enterOrMoveFocus(-1);
                break;
            case PAGE_UP:
                scrollBy(-console.page());
                break;
            case PAGE_DOWN:
                scrollBy(console.page());
                break;
            case GO_TOP:
                jumpTo(true);
                break;
            case GO_TAIL:
                jumpTo(false);
                break;
            case LINE_UP:
                scrollOrRecall(-1);
                break;
            case LINE_DOWN:
                scrollOrRecall(1);
                break;
            case SUBMIT:
                onEnter();
                break;
            default:
                break;
        }
    }

    /**
     * Escape: backs out of whatever is innermost.
     *
     * <p>Which one that is, is decided by {@link ShellKeys#dismissal}, where the order can be asked
     * about without a terminal. Ctrl+U clears the line whatever else is going on; here it is what
     * Escape means once there is no mode left to leave.</p>
     */
    private void dismiss() {
        switch (ShellKeys.dismissal(promptHandshake.isActive(), selectMode, selection.isActive(),
                                    console.isFocused(), !inputState.text().isEmpty())) {
            case PROMPT:
                cancelPendingPrompt();
                inputState.clear();
                dirty = true;
                break;
            case SELECT_MODE:
                setSelectMode(false);
                break;
            case SELECTION:
                clearSelection();
                break;
            case FOCUS:
                exitFocus();
                break;
            case INPUT:
                clearInput();
                break;
            case NOTHING:
            default:
                break;
        }
    }

    /**
     * Throws away the line being typed.
     *
     * <p>Recall starts from the newest command again. What Up means depends on where in the history
     * the line came from, and after this the line came from nowhere -- leaving the index where it
     * was made the next Up step back from a command that is no longer on screen.</p>
     *
     * @return whether there was anything to clear
     */
    private boolean clearInput() {
        if (inputState.text().isEmpty()) {
            return false;
        }
        inputState.clear();
        historyIndex = commandHistory.size();
        dirty        = true;
        return true;
    }

    /** Ctrl+Home / Ctrl+End: to the end of the focused result, or of the whole transcript. */
    private void jumpTo(boolean top) {
        if (console.isFocused()) {
            if (top) {
                console.focusTop();
            } else {
                console.focusTail();
            }
        } else if (top) {
            console.jumpToTop();
        } else {
            console.followTail();
        }
        dirty = true;
    }

    /**
     * Up / Down: scrolls the focused result, or walks back through what has been typed.
     *
     * <p>The two never both apply: command history is recalled into the input line, and the input
     * line is not what Up is for while a result is filling the console.</p>
     *
     * @param direction -1 for up, 1 for down
     */
    private void scrollOrRecall(int direction) {
        if (showingOverview()) {
            // The overview is a list of things to open, so Up and Down pick one. Scrolling it would
            // be the same key doing nothing on a list that fits, which is most of them.
            console.moveOverviewRow(direction, BackgroundPanes.rows().size());
            dirty = true;
        } else if (console.isFocused()) {
            console.focusScrollBy(direction);
            dirty = true;
        } else if (!promptHandshake.isActive()) {
            navigateHistory(direction);
        }
    }

    private boolean handleTextEditing(KeyEvent k) {
        // Ctrl and Alt both, because terminals disagree about which one carries a word-wise key:
        // xterm and most Linux terminals send Ctrl+Left, and the macOS ones send Alt+Left.
        boolean byWord = k.modifiers().ctrl() || k.modifiers().alt();
        switch (k.code()) {
            case BACKSPACE:
                if (byWord) {
                    deleteWordBefore();
                    return true;
                }
                inputState.deleteBackward();
                return true;
            case DELETE:
                inputState.deleteForward();
                return true;
            case LEFT:
                if (byWord) {
                    moveCaretTo(InputEditing.previousWord(inputState.text(),
                                                          inputState.cursorPosition()));
                    return true;
                }
                inputState.moveCursorLeft();
                return true;
            case RIGHT:
                if (byWord) {
                    moveCaretTo(InputEditing.nextWord(inputState.text(),
                                                      inputState.cursorPosition()));
                    return true;
                }
                // At the end of the line there is no cursor movement left to make, so Right accepts
                // the predicted command name -- the convention shells with inline suggestions use.
                // Tab is not available for this: it moves between results.
                if (inputState.cursorPosition() >= inputState.length() && acceptCompletion()) {
                    return true;
                }
                inputState.moveCursorRight();
                return true;
            case HOME:
                inputState.moveCursorToStart();
                return true;
            case END:
                inputState.moveCursorToEnd();
                return true;
            case CHAR:
                if (k.modifiers().ctrl() || k.modifiers().alt()) {
                    // Ctrl+W deletes the word behind the caret, as it does in every shell. Alt+B
                    // and Alt+F are the other spelling of a word-wise move, for a terminal that
                    // sends those rather than a modified arrow.
                    int chord = Character.toLowerCase(k.codePoint());
                    if (k.modifiers().ctrl() && chord == 'w') {
                        deleteWordBefore();
                        return true;
                    }
                    if (k.modifiers().alt() && chord == 'b') {
                        moveCaretTo(InputEditing.previousWord(inputState.text(),
                                                              inputState.cursorPosition()));
                        return true;
                    }
                    if (k.modifiers().alt() && chord == 'f') {
                        moveCaretTo(InputEditing.nextWord(inputState.text(),
                                                          inputState.cursorPosition()));
                        return true;
                    }
                    return false;
                }
                int cp = k.codePoint();
                if (cp >= 32 && cp != 127) {
                    inputState.insert(k.string());
                    return true;
                }
                return false;
            default:
                return false;
        }
    }

    /**
     * Walks the caret to an index.
     *
     * <p>One step at a time because that is what the field offers: it exposes where the caret is
     * and how to move it by one, and moving it by one is how it keeps its own view of the line
     * scrolled to where the caret now is.</p>
     *
     * @param target where the caret should end up
     */
    private void moveCaretTo(int target) {
        int at = inputState.cursorPosition();
        while (at > target) {
            inputState.moveCursorLeft();
            at--;
        }
        while (at < target) {
            inputState.moveCursorRight();
            at++;
        }
    }

    /** Deletes from the caret back to the start of the word behind it. */
    private void deleteWordBefore() {
        int caret = inputState.cursorPosition();
        int from  = InputEditing.previousWord(inputState.text(), caret);
        for (int i = caret; i > from; i--) {
            inputState.deleteBackward();
        }
    }

    private void onEnter() {
        // Prompt mode: feed the line back to the blocked command thread
        if (promptHandshake.isActive()) {
            String resp = inputState.text();
            inputState.clear();
            promptHandshake.submit(pastes.expand(resp));
            return;
        }

        String typed = inputState.text().trim();
        if (typed.isEmpty()) {
            // Only with an empty line, so Enter never opens a pane instead of running what was
            // typed. A command is still submitted while the overview is up.
            if (showingOverview()) {
                openSelectedPane();
            }
            return;
        }

        Admission admission = classifyNewCommand(processing, interruptRequested);
        if (admission != Admission.RUN) {
            appendSystemNotice(admission == Admission.REFUSE_STILL_STOPPING
                    ? "The previous command is still stopping. Press Ctrl+Q to quit if it will not."
                    : "A command is running. Press F2 to interrupt it first.");
            return;
        }

        inputState.clear();

        // What RUNS is the line with each marker put back to the text it stands for. What is shown
        // and kept is the line as it was typed: a marker is there because the thing it stands for
        // was too big to read at the prompt, and it is no more readable as a result heading, a
        // history entry or a line of the transcript.
        String command = pastes.expand(typed);

        // What is RECORDED is not always what was typed. The session log, history.log and the saved
        // transcript all outlive the session and none is owner-restricted, so a key typed at the
        // prompt would be persisted somewhere more readable than the config file it was headed for.
        // The line still EXECUTES as typed; only the copy that is kept is masked.
        String recorded = com.eonmux.cadetcoder.security.SecretRedactor.redactCommandLine(typed);
        SessionLogger.getInstance().logUserInput(recorded);
        commandHistory.add(recorded);
        historyIndex = commandHistory.size();

        // Each command opens a fresh result segment; browsing returns to live so the user
        // sees the command they just launched.
        exitFocus();
        currentSegment = transcript.beginSegment(ShellTranscript.Kind.COMMAND, recorded);
        currentSegment.setStatus(ShellTranscript.Status.RUNNING);
        appendOutput("> " + recorded);
        startProcessing(recorded);

        // The pictures this line still asks for, read from the line rather than from what was
        // dropped: a marker deleted before Enter is a screenshot the user thought better of. They
        // are attached for the turn and dropped again when it ends, so a picture is never carried
        // into a later, unrelated question.
        java.util.List<com.eonmux.cadetcoder.ai.PromptImage> pictures = pastes.picturesIn(typed);

        Thread commandThread = new Thread(() -> {
            try {
                com.eonmux.cadetcoder.ai.PromptAttachments.attach(pictures);
                executeCommand(typed, command);
            } finally {
                com.eonmux.cadetcoder.ai.PromptAttachments.clear();
                stopProcessing();
            }
        });
        commandThread.setDaemon(true);
        commandThread.start();
    }

    /**
     * Runs one line of shell input.
     *
     * <p>Routing is delegated to {@link InputRouter} in {@link InputRouter.Mode#CONVERSATION}: a
     * leading {@code /} means "command", anything else is a message for the AI. This replaced a
     * {@code split("\\s+")} whose first token was dispatched whenever it happened to name a
     * registered command, which meant an ordinary sentence was executed as a tool call --
     * {@code write a test for Foo} created a file named {@code a}, {@code commit the changes} made a
     * real git commit -- while a sentence starting with any other word went to the model. The
     * tokenizer is now quote-aware too, so {@code /write notes.txt "hello world"} passes two
     * arguments rather than three.</p>
     *
     * <p>Both lines are handed over, because the slash that names a command has to be one the
     * person typed. A dropped file's marker stands for an absolute path, so putting it back at the
     * start of a line writes a slash nobody typed, and a question about a dropped screenshot was
     * dispatched as a command named after the file.</p>
     *
     * @param typed the line as the person typed it, markers and all
     * @param input the same line with each paste marker put back to what it stands for
     */
    private void executeCommand(String typed, String input) {
        // The segment this command's outcome belongs to is the one that was open when it started.
        // Read back out of the field at the end instead, it was whatever the command had left
        // there: /session load opens a fresh region of its own, so the load's result was stamped on
        // that region and the command's own segment stayed marked as still running for the rest of
        // the session.
        ShellTranscript.Segment mine   = currentSegment;
        ShellTranscript.Status  status = ShellTranscript.Status.OK;
        try {
            InputRouter.Routed routed = InputRouter.route(
                    typed, input, registry.getCommands().keySet(), InputRouter.Mode.CONVERSATION);

            if (routed.isEmpty()) {
                return;
            }

            String commandName;
            int    result;

            if (routed.isCommand()) {
                commandName = routed.getName();
                // Explicit "/name": never forward an unregistered name to the model.
                result = registry.executeCommand(commandName, routed.getArgs(), false);
            } else {
                noteShadowedCommand(routed.getShadowedCommand());
                commandName = "chat";
                // The whole line is ONE argument so the request reaches the model intact; ChatCommand
                // joins its arguments with spaces, which would otherwise collapse the user's spacing.
                result = registry.executeCommand(commandName, new String[] {routed.getText()}, false);
            }

            // A theme change takes effect on the next render (the renderer reads the
            // current theme each frame); just request a redraw.
            if ("theme".equals(commandName) && result == 0) {
                dirty = true;
            }

            // Both failure notices carry the error marker. Without one they classified as NORMAL and
            // rendered as ordinary body text -- the one line saying the command had failed looked
            // exactly like the command's own output.
            if (result != 0) {
                status = ShellTranscript.Status.ERROR;
                appendOutput(glyphs.errorMarker() + " Command [" + commandName
                             + "] failed with exit code " + result);
            }
        } catch (Exception e) {
            status = ShellTranscript.Status.ERROR;
            appendOutput(glyphs.errorMarker() + " Error executing command: " + e.getMessage());
        } finally {
            if (mine != null) {
                mine.setStatus(status);
            }
        }
    }

    /**
     * Tells the user, at most once per command name per session, that the sentence they just typed
     * begins with a word that is also a command.
     *
     * <p>The message is deliberately not a prompt: the line is still sent to the AI, because that is
     * what the user asked for by omitting the slash. Repeating the notice on every message would be
     * noise, so each name is announced once.</p>
     *
     * @param shadowedCommand the command name the message's first word matches, or {@code null}
     */
    private void noteShadowedCommand(String shadowedCommand) {
        if (shadowedCommand == null || !shadowHintsShown.add(shadowedCommand)) {
            return;
        }
        appendSystemNotice("Sent to the AI. Commands start with '/' " + glyphs.dash() + " use '/"
                + shadowedCommand + "' to run the " + shadowedCommand + " command instead.");
    }

    /**
     * The prediction for what is currently typed.
     *
     * <p>Recomputed rather than cached: it is a prefix scan over a few dozen names, run once per
     * frame, and a cache would have to be invalidated on every keystroke, paste, history navigation
     * and clear.</p>
     */
    private CommandCompletion currentCompletion() {
        if (promptHandshake.isActive()) {
            return CommandCompletion.NONE;
        }
        return CommandCompletion.of(inputState.text(), registry.getCommands().keySet());
    }

    /**
     * Appends the predicted remainder of the command name.
     *
     * @return {@code true} when something was appended
     */
    private boolean acceptCompletion() {
        CommandCompletion completion = currentCompletion();
        if (!completion.hasSuffix()) {
            return false;
        }
        inputState.insert(completion.getSuffix());
        return true;
    }

    private void navigateHistory(int direction) {
        if (commandHistory.isEmpty()) {
            return;
        }

        historyIndex += direction;
        if (historyIndex < 0) {
            historyIndex = 0;
        } else if (historyIndex >= commandHistory.size()) {
            historyIndex = commandHistory.size();
            inputState.setText("");
            dirty = true;
            return;
        }

        if (historyIndex < commandHistory.size()) {
            inputState.setText(commandHistory.get(historyIndex));
            dirty = true;
        }
    }

    /**
     * Scrolls whichever region the console is showing.
     *
     * @param lines negative for up, positive for down
     */
    private void scrollBy(int lines) {
        if (console.isFocused()) {
            console.focusScrollBy(lines);
        } else if (selecting) {
            // The wheel works mid-drag, and it is the quick way to cover a long stretch: scroll to
            // where you want the selection to end, then move the pointer there. It must not release
            // the hold the drag put on the view, or the transcript snaps back to its newest line and
            // every row moves out from under the anchor.
            console.scrollWhileHeld(lines);
            followPointerWithSelection();
        } else {
            console.scrollLines(lines);
        }
        dirty = true;
    }

    // ---------------------------------------------------------------------
    // Focus mode (single-result isolation)
    // ---------------------------------------------------------------------

    /** Enter focus mode on a worker or a job, or move to the next/previous one. */
    private void enterOrMoveFocus(int direction) {
        if (!console.enterOrMoveFocus(direction, BackgroundPanes.all())) {
            flash("Nothing is running. F5 lists workers and jobs", 20);
            return;
        }
        clearSelection(); // a live-mode selection has no meaning once we isolate one pane
        dirty = true;
    }

    /**
     * Shows every worker and every job at once.
     *
     * <p>Opened even when there is nothing to list, which is an answer rather than a refusal:
     * "nothing is running" is the thing a reader wanted to know, and flashing it in the corner
     * would be gone before the next glance.</p>
     */
    private void openOverview() {
        console.openOverview();
        clearSelection();
        dirty = true;
    }

    /**
     * Opens whichever line of the overview is picked out.
     *
     * @return whether a pane was opened
     */
    private boolean openSelectedPane() {
        List<BackgroundPanes.Row> rows = BackgroundPanes.rows();
        int row = console.overviewRow();
        if (row < 0 || row >= rows.size()) {
            return false;
        }
        console.focusOn(rows.get(row).pane());
        dirty = true;
        return true;
    }

    /** @return whether the console is showing the list of workers and jobs */
    private boolean showingOverview() {
        return console.isFocused() && console.focusedPane() != null
               && console.focusedPane().isOverview();
    }

    private void exitFocus() {
        if (console.exitFocus()) {
            dirty = true;
        }
    }

    // ---------------------------------------------------------------------
    // Mouse interaction
    // ---------------------------------------------------------------------

    private boolean handleMouse(MouseEvent m) {
        switch (ShellPointer.classify(m, pointerState())) {
            case SCROLL_UP:
                scrollBy(-ShellPointer.WHEEL_LINES);
                return true;
            case SCROLL_DOWN:
                scrollBy(ShellPointer.WHEEL_LINES);
                return true;
            case BEGIN_SELECT:
                beginSelect(m);
                return true;
            case EXTEND_SELECT:
                extendSelect(m);
                return true;
            case CLICK:
                selecting = false;
                clearSelection();
                return focusSegmentAt(pressX, pressY);
            case COPY_SELECTION:
                selecting = false;
                copyToClipboard(selection.extract(console.documentLines()));
                // The selection stays highlighted after release so what was copied can be seen, and
                // copied again with Ctrl+Shift+C; Esc or a fresh press clears or replaces it.
                dirty = true;
                return true;
            case ABANDON_SELECT:
                selecting = false;
                clearSelection();
                return true;
            case CONSUME:
                return true;
            case NONE:
            default:
                return false;
        }
    }

    /** The part of the shell's state a mouse report's meaning depends on. */
    private ShellPointer.State pointerState() {
        return new ShellPointer.State(selectMode, promptHandshake.isActive(), console.isFocused(),
                                      selecting, selection.isEmptySelection());
    }

    /** Anchors a selection where the button went down. */
    private void beginSelect(MouseEvent m) {
        pressX = m.x();
        pressY = m.y();
        dragX  = m.x();
        dragY  = m.y();
        int row = console.documentRowAt(m.y());
        if (row < 0) {
            return;
        }
        selection = selection.start(row, console.documentColumnAt(m.x()));
        selecting = true;
        // Freeze the viewport for the duration of the drag so streamed output cannot auto-scroll the
        // transcript out from under the anchor (which would otherwise remap screen rows to different
        // document rows and copy the wrong text).
        console.freezeWhereItIs();
        dirty = true;
    }

    /** Moves the far end of the selection to where the pointer now is. */
    private void extendSelect(MouseEvent m) {
        dragX = m.x();
        dragY = m.y();
        console.scrollWhileHeld(console.dragScrollLines(dragY));
        followPointerWithSelection();
    }

    /**
     * Scrolls a drag that is being held against the top or bottom edge, and brings the selection
     * with it.
     *
     * <p>Called on every render tick, because a pointer that has stopped moving stops producing
     * reports. Without it a drag would scroll only while the pointer kept jiggling, which is not
     * what holding against an edge means.</p>
     *
     * @return whether anything moved
     */
    private boolean dragHeldAtEdge() {
        if (!selecting || !console.scrollWhileHeld(console.dragScrollLines(dragY))) {
            return false;
        }
        followPointerWithSelection();
        return true;
    }

    /**
     * Puts the far end of the selection under the pointer.
     *
     * <p>Resolved against the nearest visible row rather than the exact one, so a drag that has left
     * the console -- over the input line, or above the heading -- still moves the selection. The
     * terminal keeps reporting a drag anywhere in its window while the button is down.</p>
     */
    private void followPointerWithSelection() {
        int row = console.documentRowNear(dragY);
        if (row < 0) {
            return;
        }
        selection = selection.extendTo(row, console.documentColumnAt(dragX));
        dirty     = true;
    }

    /** Clear any active or in-progress selection, requesting a redraw if something changed. */
    private void clearSelection() {
        selecting = false;
        // The freeze exists for the selection; with the selection gone, so is the reason to hold the
        // viewport still. The HOLD is released rather than the tail followed: a reader who was at
        // the bottom when they started selecting resumes following on the next draw, and one who
        // had deliberately scrolled back before selecting stays where they were.
        console.releaseHold();
        if (selection.isActive()) {
            selection = TextSelectionModel.EMPTY;
            dirty     = true;
        }
    }

    /** Focus the result segment under the given console cell, if any. Returns whether it handled it. */
    private boolean focusSegmentAt(int x, int y) {
        if (!console.focusSegmentAt(x, y)) {
            return false;
        }
        dirty = true;
        return true;
    }

    // ---------------------------------------------------------------------
    // Select mode + clipboard
    // ---------------------------------------------------------------------

    /**
     * Enter or leave "select mode".
     *
     * <p>The mode gives the mouse over to selecting. Dragging selects either way, so what changes is
     * that a click which never moved no longer opens the result under it: somebody who pressed F4 is
     * about to drag, and a mis-click that jumped into a result would throw that away.</p>
     *
     * <p>Entering clears any selection already on screen, so the mode starts from nothing rather
     * than from a highlight left over from before.</p>
     */
    private void setSelectMode(boolean on) {
        if (selectMode == on) {
            return;
        }
        selectMode = on;
        clearSelection();
        dirty = true;
    }

    /**
     * Copy selected text to the clipboard, and say how much was copied.
     *
     * <p>The count is the point of the flash: the two routes the copy takes report nothing back, so
     * a number the user can compare against what they highlighted is the only confirmation there
     * is that anything was copied at all.</p>
     */
    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        terminal.copy(text);
        int n = text.length();
        flash(glyphs.ok() + " copied " + n + (n == 1 ? " char" : " chars"), FLASH_TICKS);
    }

    /**
     * Copy the current live-transcript selection to the clipboard in response to an explicit
     * Ctrl+Shift+C. This never quits the shell: if nothing is selected it just shows a brief hint.
     */
    private void copySelectionToClipboard() {
        if (selection.isActive() && !selection.isEmptySelection()
                && !console.documentLines().isEmpty()) {
            copyToClipboard(selection.extract(console.documentLines()));
        } else {
            flash(glyphs.bullet() + " nothing selected " + glyphs.dash() + " drag to select first",
                  FLASH_TICKS);
        }
    }

    /** How long a transient status message stays up, in ticks. */
    private static final int FLASH_TICKS = 20;

    /** Show a transient status message for {@code ticks} ticks (0 = until explicitly cleared). */
    private void flash(String message, int ticks) {
        flashMessage = (message == null || message.isEmpty()) ? null : message;
        flashTicks   = ticks;
        dirty        = true;
    }

    // ---------------------------------------------------------------------
    // Processing indicator / interruption
    // ---------------------------------------------------------------------

    private void startProcessing(String command) {
        currentCommand     = command;
        initialActivity    = ShellActivityLine.opening(command, registry.getCommands().keySet());
        processing         = true;
        interruptRequested = false;
        spinnerFrame       = 0;
        com.eonmux.cadetcoder.InterruptSignal.clear();
    }

    private void stopProcessing() {
        processing      = false;
        currentCommand  = null;
        initialActivity = "";
        header.refreshBranch(); // a command may have changed branches
        dirty          = true;
    }

    /**
     * Request interruption of the currently running command.
     */
    public void requestInterrupt() {
        if (processing) {
            interruptRequested = true;
            com.eonmux.cadetcoder.InterruptSignal.request();
            appendSystemNotice("Command interruption requested");
            // If the command is blocked waiting on a prompt, release it so the
            // interrupt actually unwedges the command thread.
            cancelPendingPrompt();
            inputState.clear();
            dirty = true;
        }
    }

    /**
     * Cancel a pending {@link #getUserInput(String)} prompt (if any), unblocking the
     * waiting command thread with an empty response.
     */
    private void cancelPendingPrompt() {
        promptHandshake.cancel();
    }

    /**
     * Whether an interrupt has been requested for the running command.
     */
    public boolean isInterruptRequested() {
        return interruptRequested;
    }

    /**
     * Request that the shell exit its event loop. Thread-safe.
     */
    public void requestQuit() {
        // Release any command thread blocked on a prompt before stopping the loop.
        cancelPendingPrompt();
        TuiRunner r = runner;
        if (r != null) {
            r.quit();
        }
    }

    // ---------------------------------------------------------------------
    // Output transcript
    // ---------------------------------------------------------------------

    private void appendOutput(String text) {
        pushText(text + "\n");
    }

    /** Append a shell-generated system notice ({@code *** ... ***}, styled dim). */
    private void appendSystemNotice(String message) {
        appendOutput("*** " + message + " ***");
    }

    /**
     * Append text directly to the output transcript (called by {@link OutputRouter} from a
     * background command thread). The text is ANSI-stripped, split into lines, routed to the active
     * result segment (capped to {@value #PER_SEGMENT_LINES} lines each), and a redraw is requested.
     */
    public void appendOutputDirect(String text) {
        if (text == null) {
            return;
        }
        pushText(text);
    }

    private void pushText(String text) {
        transcript.append(stripAnsiCodes(text));
        // New output does NOT drag the view back to the bottom. A reader already following is kept
        // there by the draw itself, so this could only ever act on a reader who had deliberately
        // scrolled back -- and it did: paging up while a command was printing snapped the view to
        // the tail on the next chunk, which made scrolling back impossible for as long as anything
        // was running. Ctrl+End follows again, which is what the status bar has always offered.
        dirty = true;
    }

    /** Empties the transcript altogether, for a console that is about to show another session. */
    private void clearOutput() {
        transcript.clear();
        resetConsoleView();
    }

    /**
     * Clears the console, for {@code /clear} and Ctrl+L.
     *
     * <p>Only the view: the regions are kept for the scrollback saved with the session, and the
     * session log is never touched by the console at all. See {@link ShellTranscript#clearView()}.</p>
     */
    public void clearConsole() {
        transcript.clearView();
        resetConsoleView();
    }

    private void resetConsoleView() {
        currentSegment = null;
        console.reset();
        selection      = TextSelectionModel.EMPTY;
        selecting      = false;
        dirty          = true;
    }

    /**
     * Strip ANSI color/control codes for clean display in the TUI.
     */
    private String stripAnsiCodes(String text) {
        return AnsiStripper.strip(text);
    }

    // ---------------------------------------------------------------------
    // Rendering (runs on the TamboUI render thread)
    // ---------------------------------------------------------------------

    private void render(Frame frame) {
        TuiTheme theme = TuiThemeManager.getCurrentTheme();
        Rect     area  = frame.area();

        if (help.isVisible()) {
            help.render(frame, area, theme);
            return;
        }

        // The input region carries a divider, its heading and the field itself; the row added for
        // the divider comes out of the console rather than out of the field.
        List<Rect> rows = Layout.vertical()
                .constraints(Constraint.length(1), Constraint.fill(),
                        Constraint.length(2), Constraint.length(4))
                .split(area);

        header.render(frame, rows.get(0), theme, processing ? this::liveStatus : null);
        consoleRenderer.render(frame, rows.get(1), theme, consoleView());
        status.render(frame, rows.get(2), theme, statusView());
        inputLine.render(frame, rows.get(3), theme, inputState, inputView());
    }

    /**
     * What the shell is doing right now: the command, how long it has been running, and what the
     * request has cost so far.
     *
     * <p>Assembled in one place and shown in one place. The spinner animated in both the console
     * heading and the status bar at once, and the command being run was named in the status bar as
     * well -- three renderings of "busy" for one request, while the command itself was already
     * visible in the transcript directly above.</p>
     *
     * @return the status text
     */
    private String liveStatus(int width) {
        // A spinner while the shell is waiting on a HUMAN would be a lie about who is holding things
        // up, so prompt mode gets a static mark instead.
        String mark = promptHandshake.isActive() ? "?" : glyphs.spinner(spinnerFrame);

        // Workers collect their own output, so the transcript reports nothing until each finishes.
        // Their progress replaces the section title for as long as they run; otherwise the bar would
        // hold the launch line unchanged for minutes, which reads exactly like a hang.
        String activity = com.eonmux.cadetcoder.agents.WorkerActivity.describe()
                .orElseGet(() -> transcript.activeSectionTitleFor(currentSegment));
        if (activity.isEmpty()) {
            activity = initialActivity;
        }

        String timing = com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder.getInstance()
                .inFlightStatus()
                .map(com.eonmux.cadetcoder.ai.metrics.RequestMetricsRecorder.InFlightStatus::render)
                .orElse("");

        return ShellActivityLine.compose(mark, activity, timing, width, glyphs.bullet(),
                                         glyphs.ellipsis());
    }

    /** What the input line has to be drawn from this frame. */
    private ShellInputLine.View inputView() {
        return new ShellInputLine.View(promptHandshake.isActive(), promptHandshake.text(),
                                       currentCompletion().ghostText());
    }

    /** What the console has to be drawn from this frame, beyond the transcript and the scroll. */
    private ShellConsoleRenderer.View consoleView() {
        return new ShellConsoleRenderer.View(selection, selectMode, spinnerFrame, corner());
    }

    /**
     * What the console's top-right corner says: where you are in the view.
     *
     * <p>Worked out by the status bar, which is where this text has always been written, and drawn
     * in the console's own corner. While a result is focused the heading on the left of that row
     * already names it, so the corner is left to a transient notice.</p>
     *
     * @return the label, or empty when the heading already says it
     */
    private String corner() {
        boolean flashing = flashTicks > 0 && flashMessage != null && !flashMessage.isEmpty();
        if (console.isFocused() && !flashing) {
            return "";
        }
        return status.state(statusView());
    }

    /** What the bottom line has to say about where the console currently is. */
    private ShellStatusBar.View statusView() {
        return new ShellStatusBar.View(flashTicks > 0 ? flashMessage : null,
                                       promptHandshake.isActive(), selectMode, focusView(),
                                       console.isFollowingTail(), console.scroll(),
                                       console.maxScroll());
    }

    /** What is focused, or null in the live view. */
    private ShellStatusBar.Focus focusView() {
        if (!console.isFocused()) {
            return null;
        }
        BackgroundPane pane = console.focusedPane();
        if (pane != null && pane.isOverview()) {
            List<BackgroundPanes.Row> rows = BackgroundPanes.rows();
            return new ShellStatusBar.Focus(ShellStatusBar.Of.OVERVIEW,
                                            console.overviewRow() + 1, rows.size(), "");
        }
        if (pane != null) {
            List<BackgroundPane> panes = BackgroundPanes.all();
            return new ShellStatusBar.Focus(
                    pane.isJob() ? ShellStatusBar.Of.JOB : ShellStatusBar.Of.WORKER,
                    panes.indexOf(pane) + 1, panes.size(), "");
        }
        List<Integer> ids   = transcript.segmentIds();
        String        title = transcript.titleOf(console.focusedSegment());
        return new ShellStatusBar.Focus(ShellStatusBar.Of.RESULT,
                                        ids.indexOf(console.focusedSegment()) + 1, ids.size(),
                                        ShellWidgets.shortenCommand(title, glyphs.ellipsis()));
    }

    /**
     * Prints the recent command lines into a segment of their own.
     *
     * <p>The segment it opens becomes the transcript's active sink, so whatever was printing when
     * the key was pressed is filed under it from that moment on. F3 can be pressed at any time,
     * including mid-run, so the previous sink is put back before this returns: without that, the
     * tail of a running command's answer landed under a "Command History" heading, rendered as
     * system chrome rather than as the model's prose, opened no further sub-sections, froze the
     * header's activity line, and left the run's OK mark stamped on a region that had stopped
     * mid-stream. Every press added another such segment, each one saved into the session file and
     * replayed on resume.</p>
     */
    private void showCommandHistory() {
        ShellTranscript.Where previous = transcript.activeWhere();
        transcript.beginSegment(ShellTranscript.Kind.SYSTEM, "Command History");
        appendOutput(glyphs.subheaderMarker() + " Command History" + glyphs.subheaderCloser());
        for (int i = Math.max(0, commandHistory.size() - 20); i < commandHistory.size(); i++) {
            appendOutput((i + 1) + ": " + commandHistory.get(i));
        }
        transcript.resumeWhere(previous);
    }

    // ---------------------------------------------------------------------
    // InputHandler implementation
    // ---------------------------------------------------------------------

    @Override
    public String getUserInput(String prompt) {
        TuiRunner r = runner;
        if (r == null || !r.isRunning()) {
            // Fallback to console input if the TUI is not active
            System.out.print(prompt);
            return System.console() != null ? System.console().readLine() : "";
        }

        return promptHandshake.ask(prompt, () -> {
            TuiRunner cur = runner;
            return cur != null && cur.isRunning();
        }, () -> dirty = true);
    }

    /**
     * Prompts for a yes/no confirmation on the shell's input line and returns whether the
     * response begins with "y".
     */
    @Override
    public boolean getConfirmation(String message) {
        String response = getUserInput(message + " (y/n): ");
        return response != null && response.trim().toLowerCase().startsWith("y");
    }

    @Override
    public void showMessage(String message) {
        appendOutput(message);
    }

    /**
     * Rebuilds the console for a session that has just been switched.
     *
     * <p>A session is the conversation, the todo list AND what is on screen, so changing which one
     * is open has to move all three. Left out, {@code /session new} would open an empty session
     * behind the previous one's scrollback — and the next save would then write that scrollback
     * into the new session, quietly undoing the separation the command exists to make.</p>
     */
    public void reloadSession() {
        clearOutput();
        displayWelcome();
        replayRestoredConversation();
        // The command that asked for this has more to say -- which session was started, and how to
        // get back to the one being left. Opening a region for it keeps that out of the banner,
        // which is reprinted at every start and therefore never saved.
        currentSegment = transcript.beginSegment(ShellTranscript.Kind.SYSTEM, "Session");
        dirty          = true;
    }
}
