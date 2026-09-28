package com.eonmux.cadetcoder.ui;

/**
 * Whether a TUI currently owns the terminal, and therefore whether text must be free of ANSI
 * escapes.
 *
 * <h2>Why it is one class rather than a method on each formatter</h2>
 *
 * <p>{@code ColorTheme} and {@code ThemedOutputFormatter} both have to know: one decides whether to
 * wrap text in colour codes, the other whether to strip them out again. They each kept a private
 * copy of the answer, and a copy is a second answer waiting to disagree with the first.</p>
 *
 * <p>Both copies also decided it partly by reading the call stack -- true if any frame's class name
 * CONTAINED {@code "InteractiveShell"}, {@code "OutputRouter"} or {@code "dev.tamboui"}. A class
 * name is not a fact about the terminal, and the stack belongs to whoever is calling rather than to
 * the program: every interruptible command runs on a thread the registry made, whose stack has no
 * shell frame at all, so the same message was themed there and stripped when it came from the shell
 * thread. The router, by contrast, is told when the shell takes the terminal and when it gives it
 * back, which is the thing actually being asked about.</p>
 */
public final class TuiMode {

    /**
     * Set this system property to {@code true} to declare TUI mode from outside the program.
     *
     * <p>For a host that draws the screen itself and drives CadetCoder underneath, where nothing
     * has told the router anything.</p>
     */
    public static final String OVERRIDE_PROPERTY = "cadet.tui.mode";

    private TuiMode() {
    }

    /**
     * @return whether text on its way to the screen must be plain
     */
    public static boolean isActive() {
        if ("true".equals(System.getProperty(OVERRIDE_PROPERTY))) {
            return true;
        }
        try {
            return OutputRouter.getInstance().isRouting();
        } catch (RuntimeException e) {
            // Asked on every line of output, including the lines that report a failure. If the
            // router cannot say, there is no TUI to protect and colour is the safe answer; throwing
            // from here would turn a rendering detail into a lost error message.
            return false;
        }
    }
}
