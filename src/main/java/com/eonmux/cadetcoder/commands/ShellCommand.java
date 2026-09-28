package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;
import com.eonmux.cadetcoder.OutputFormatter;
import picocli.CommandLine.Command;

@Command (name = "shell", description = "Open the interactive shell")
public class ShellCommand implements CommandRegistry.Command {

    /**
     * System property that explicitly forces interactivity on/off. When set to
     * {@code false} the interactive TUI is refused even if a console is present;
     * when set to {@code true} the {@link System#console()} probe is bypassed
     * (useful for tests/embedding). Unset -> fall back to console detection.
     */
    static final String INTERACTIVE_PROPERTY = "cadet.interactive";

    @Override
    public int execute(String[] args) {
        // Guard (shell-iface-1): the interactive REPL grabs raw terminal mode and
        // blocks on real keystrokes. An agentic loop / LLM emitting the "shell" verb,
        // or any redirected / piped invocation, has no usable TTY. Fast-fail with a
        // clear message instead of attaching a raw-mode TUI to a non-interactive
        // context (which would hang or corrupt the surrounding session).
        if (!isInteractive()) {
            OutputFormatter.printError(
                    "The interactive shell requires a real terminal (TTY) and cannot run in a "
                            + "non-interactive, redirected, or agentic context.");
            OutputFormatter.printInfo(
                    "Run 'cadet shell' directly in a terminal, or set -D" + INTERACTIVE_PROPERTY
                            + "=true to override.");
            return 1;
        }

        try {
            InteractiveShell shell = new InteractiveShell();
            return shell.runShell();
        } catch (Exception e) {
            OutputFormatter.printError("Error initializing interactive shell: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return 1;
        }
    }

    /**
     * Decide whether the current process can host a raw-mode interactive TUI.
     *
     * <p>An explicit {@code -Dcadet.interactive} property always wins (so callers can
     * force-enable in tests/embedding or force-disable in CI). Otherwise interactivity
     * is inferred from {@link System#console()}, which is {@code null} when stdin/stdout
     * is redirected or there is no controlling terminal.</p>
     *
     * <p>Package-private and overridable so tests can exercise both branches without a
     * real terminal.</p>
     */
    boolean isInteractive() {
        String explicit = System.getProperty(INTERACTIVE_PROPERTY);
        if (explicit != null) {
            return Boolean.parseBoolean(explicit.trim());
        }
        return System.console() != null;
    }


    @Override
    public String getUsage() {
        return "shell";
    }
}
