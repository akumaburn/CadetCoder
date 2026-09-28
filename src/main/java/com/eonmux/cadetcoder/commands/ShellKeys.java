package com.eonmux.cadetcoder.commands;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;

/**
 * What a keystroke means to the shell, decided without reference to anything on screen.
 *
 * <h2>Why the decision is separated from doing it</h2>
 *
 * <p>It was a two-hundred-line chain of {@code if}s inside the event loop, and every branch of it
 * both decided and acted. So the only way to ask "what does Ctrl+C do while a command is running and
 * text is selected" was to start a terminal UI and press it -- which is why the one guarantee that
 * matters here, that copying never exits the shell, went unverified across the terminals that
 * deliver the chord differently.</p>
 *
 * <h2>Why some actions are named for the key and not the effect</h2>
 *
 * <p>{@link Action#LINE_UP} and {@link Action#GO_TOP} do different things depending on whether a
 * result is focused, and that is not a fact about the keyboard. Deciding it here would mean this
 * class knowing where the console is scrolled to, which is exactly the state that cannot be reached
 * without a terminal. So the key says which way and how far, and the shell says what is being
 * moved.</p>
 */
final class ShellKeys {

    /** What the shell should do about a keystroke. */
    enum Action {
        /** Not a shell key: what it means is for the line being typed. */
        NONE,
        /** A shell key with nothing to do in the current mode; swallowed rather than typed. */
        IGNORE,
        OPEN_HELP,
        /**
         * Escape: back out of whatever is innermost -- a prompt, select mode, a selection, a
         * focused result, or, with none of those, the line being typed.
         */
        DISMISS,
        INTERRUPT,
        SHOW_HISTORY,
        TOGGLE_SELECT_MODE,
        /** Show every worker and every job at once, instead of the transcript. */
        OPEN_OVERVIEW,
        /** Copy, and leave the selection up so it can be copied again. */
        COPY_SELECTION,
        /** Copy, then drop the selection so the next press of the same chord quits or interrupts. */
        COPY_SELECTION_THEN_CLEAR,
        /** Ask whether to quit; the next press of the same chord does it. */
        CONFIRM_QUIT,
        QUIT,
        CLEAR_OUTPUT,
        /** Throw away the line being typed, leaving the console as it is. */
        CLEAR_INPUT,
        FOCUS_NEXT,
        FOCUS_PREVIOUS,
        PAGE_UP,
        PAGE_DOWN,
        GO_TOP,
        GO_TAIL,
        LINE_UP,
        LINE_DOWN,
        SUBMIT
    }

    /** What Ctrl+C means, given what else is going on. */
    enum CtrlCAction { NONE, COPY_SELECTION, INTERRUPT, CONFIRM_QUIT, QUIT }

    /** What one press of Escape backs out of. */
    enum Dismissal {
        /** Cancel the question a running command asked, and drop the answer being typed. */
        PROMPT,
        /** Give the mouse back to the application. */
        SELECT_MODE,
        /** Drop the highlighted text. */
        SELECTION,
        /** Return from a focused result to the live view. */
        FOCUS,
        /** Throw away the line being typed. */
        INPUT,
        /** Nothing to back out of. */
        NOTHING
    }

    /**
     * What Escape backs out of, taken in the order these were entered.
     *
     * <p>One key for five ways of being somewhere other than a plain live view with an empty
     * prompt. Dropping all of them at once would, on the press that cancels a question, also throw
     * away a selection made before the question appeared. The line being typed comes last because
     * it is the only one that is not a mode: it is what Escape means when there is no mode left to
     * leave.</p>
     *
     * @param prompting    whether a running command is waiting for an answer
     * @param selectMode   whether the mouse has been given over to selecting
     * @param hasSelection whether text is highlighted
     * @param focused      whether one result fills the console
     * @param typing       whether anything has been typed on the input line
     * @return what to back out of
     */
    static Dismissal dismissal(boolean prompting, boolean selectMode, boolean hasSelection,
                               boolean focused, boolean typing) {
        if (prompting) {
            return Dismissal.PROMPT;
        }
        if (selectMode) {
            return Dismissal.SELECT_MODE;
        }
        if (hasSelection) {
            return Dismissal.SELECTION;
        }
        if (focused) {
            return Dismissal.FOCUS;
        }
        return typing ? Dismissal.INPUT : Dismissal.NOTHING;
    }

    /**
     * The part of the shell's state a keystroke's meaning depends on.
     *
     * @param processing   whether a command is running
     * @param prompting    whether a running command is waiting for an answer on the input line
     * @param hasSelection whether text is selected in the live transcript
     * @param quitAsked    whether the shell has already asked whether to quit and is waiting for
     *                     the second press that would confirm it
     */
    record State(boolean processing, boolean prompting, boolean hasSelection, boolean quitAsked) {
    }

    private ShellKeys() {
    }

    /**
     * What a keystroke means.
     *
     * @param k     the keystroke
     * @param state what else is going on
     * @return the action, {@link Action#NONE} when the key belongs to the line being typed
     */
    static Action classify(KeyEvent k, State state) {
        KeyCode code = k.code();
        boolean ctrl = k.modifiers().ctrl();

        if (code == KeyCode.F1) {
            return Action.OPEN_HELP;
        }
        if (code == KeyCode.ESCAPE) {
            return Action.DISMISS;
        }
        if (code == KeyCode.F2) {
            return Action.INTERRUPT;
        }
        if (code == KeyCode.F3) {
            return Action.SHOW_HISTORY;
        }
        if (code == KeyCode.F4) {
            return Action.TOGGLE_SELECT_MODE;
        }
        // Not Tab, which walks the panes one at a time: this is the one place that answers "what is
        // running" without walking anything. Shift+Tab, the obvious other candidate, already walks
        // that same cycle backwards.
        if (code == KeyCode.F5) {
            return Action.OPEN_OVERVIEW;
        }

        Action copyOrExit = classifyCtrlCKey(k, state);
        if (copyOrExit != Action.NONE) {
            return copyOrExit;
        }
        if (ctrl && code == KeyCode.CHAR) {
            int cp = k.codePoint();
            if (cp == 'q' || cp == 'Q') {
                return Action.QUIT;
            }
            if (cp == 'l' || cp == 'L') {
                return Action.CLEAR_OUTPUT;
            }
            // The shells' own key for this. Ctrl+C is the other habit, and here it asks whether to
            // quit instead, so without this the only way to drop a long line was to hold Backspace.
            if (cp == 'u' || cp == 'U') {
                return Action.CLEAR_INPUT;
            }
        }

        // Result navigation: Tab / Shift+Tab move between results and sub-agent sections.
        if (code == KeyCode.TAB || k.isFocusNext() || k.isFocusPrevious()) {
            if (state.prompting()) {
                return Action.IGNORE; // the answer is what Tab would move away from
            }
            boolean previous = k.isFocusPrevious() || (code == KeyCode.TAB && k.hasShift());
            return previous ? Action.FOCUS_PREVIOUS : Action.FOCUS_NEXT;
        }

        if (code == KeyCode.PAGE_UP) {
            return Action.PAGE_UP;
        }
        if (code == KeyCode.PAGE_DOWN) {
            return Action.PAGE_DOWN;
        }
        if (ctrl && code == KeyCode.HOME) {
            return Action.GO_TOP;
        }
        if (ctrl && code == KeyCode.END) {
            return Action.GO_TAIL;
        }
        if (code == KeyCode.UP) {
            return Action.LINE_UP;
        }
        if (code == KeyCode.DOWN) {
            return Action.LINE_DOWN;
        }
        if (code == KeyCode.ENTER) {
            return Action.SUBMIT;
        }
        return Action.NONE;
    }

    /** The C chord, as an {@link Action}; {@link Action#NONE} when this is not that chord. */
    private static Action classifyCtrlCKey(KeyEvent k, State state) {
        switch (classifyCtrlC(k, state.processing(), state.hasSelection(), state.quitAsked())) {
            case COPY_SELECTION:
                // Clearing after a bare Ctrl+C is what makes a second press quit or interrupt as
                // expected; an explicit Ctrl+Shift+C leaves the selection visible to copy again.
                return isCopySelectionChord(k)
                       ? Action.COPY_SELECTION : Action.COPY_SELECTION_THEN_CLEAR;
            case INTERRUPT:
                return Action.INTERRUPT;
            case CONFIRM_QUIT:
                return Action.CONFIRM_QUIT;
            case QUIT:
                return Action.QUIT;
            case NONE:
            default:
                return Action.NONE;
        }
    }

    /**
     * Whether a keystroke withdraws a pending "press again to exit".
     *
     * <p>Everything except the chord being answered. The question is about what the NEXT press
     * means, so a user who goes back to typing has answered it by doing so -- and a stale yes left
     * armed across half a line of input would end the session on a keystroke that had nothing to do
     * with it.</p>
     *
     * @param k the keystroke
     * @return {@code true} when the shell should stop waiting for a confirming press
     */
    static boolean endsTheQuestion(KeyEvent k) {
        return !isCtrlCChord(k);
    }

    /** Whether {@code k} is Ctrl+Shift+C as reported by a terminal that reports the Shift bit. */
    static boolean isCopySelectionChord(KeyEvent k) {
        return k.hasCtrl() && k.hasShift() && k.code() == KeyCode.CHAR
                && (k.codePoint() == 'c' || k.codePoint() == 'C');
    }

    /**
     * Whether {@code k} is the bare Ctrl+C chord as the terminal delivers it. Because most terminals
     * emit the same {@code 0x03} for Ctrl+C and Ctrl+Shift+C (the Shift bit is dropped for control
     * characters), this also matches Ctrl+Shift+C on those terminals.
     */
    static boolean isCtrlCChord(KeyEvent k) {
        return k.isCtrlC() || (k.hasCtrl() && k.code() == KeyCode.CHAR
                && (k.codePoint() == 'c' || k.codePoint() == 'C'));
    }

    /**
     * Decide what the C key chord should do, given whether a command is running and whether text is
     * selected. The guarantee the user cares about — <b>copying selected text never exits the
     * shell</b> — is implemented here, robustly across terminals:
     * <ul>
     *   <li>Ctrl+Shift+C (when Shift is reported) always copies.</li>
     *   <li>Ctrl+C copies the selection when one is active and no command is running. This is the
     *       standard terminal "Ctrl+C copies when text is selected" convention and also covers
     *       terminals that deliver Ctrl+Shift+C as a bare Ctrl+C with no Shift bit.</li>
     *   <li>Otherwise Ctrl+C interrupts a running command; on an idle shell it asks whether to
     *       quit, and the press after that quits.</li>
     * </ul>
     *
     * <p>The asking is the second guarantee: in every other shell and REPL this chord cancels what
     * is in hand, so it is the key a user reaches for out of habit -- and reaching for it here used
     * to end the session on the spot, taking the conversation's live context with it. Ctrl+Q is the
     * advertised, deliberate quit and still goes on the first press.</p>
     *
     * @param k            the keystroke
     * @param processing   whether a command is running
     * @param hasSelection whether text is selected in the live transcript
     * @param quitAsked    whether the shell is already waiting for a confirming press
     * @return what the chord means
     */
    static CtrlCAction classifyCtrlC(KeyEvent k, boolean processing, boolean hasSelection,
                                     boolean quitAsked) {
        if (isCopySelectionChord(k)) {
            return CtrlCAction.COPY_SELECTION;
        }
        if (isCtrlCChord(k)) {
            if (!processing && hasSelection) {
                return CtrlCAction.COPY_SELECTION;
            }
            if (processing) {
                return CtrlCAction.INTERRUPT;
            }
            return quitAsked ? CtrlCAction.QUIT : CtrlCAction.CONFIRM_QUIT;
        }
        return CtrlCAction.NONE;
    }
}
