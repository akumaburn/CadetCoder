package com.eonmux.cadetcoder.ui;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Clipboard helpers for the TUI. Provides two complementary ways of getting text out of
 * the terminal:
 *
 * <ul>
 *   <li>{@link #osc52(String)} builds an OSC-52 terminal escape sequence. The <em>caller</em>
 *       is responsible for writing the returned string to the terminal; this class never
 *       touches stdout/stderr itself.</li>
 *   <li>{@link #systemCopy(String)} performs a best-effort copy to the host OS clipboard by
 *       spawning a small platform tool and feeding the text on its stdin.</li>
 * </ul>
 *
 * <p>This class is a stateless utility: it holds no mutable state and exposes only pure or
 * side-effect-isolated static methods.</p>
 *
 * <p><strong>Security:</strong> the clipboard contents may be sensitive. None of these methods
 * print, log, or otherwise echo the supplied text. The spawned process has its stdout and
 * stderr discarded, and the text only ever travels over the child process's stdin.</p>
 */
public final class Clipboard {

    /** Escape (ESC, 0x1B) introducing the OSC sequence. */
    private static final char ESC = '\u001b';

    /** Bell (BEL, 0x07) terminating the OSC sequence (the "ST" string terminator). */
    private static final char BEL = '\u0007';

    /** Prefix of an OSC-52 "set clipboard" sequence: {@code ESC ] 52 ; c ;}. */
    private static final String OSC52_PREFIX = ESC + "]52;c;";

    /** Maximum time to wait for a spawned clipboard tool before giving up. */
    private static final long PROCESS_TIMEOUT_SECONDS = 2L;

    private Clipboard() {
        // Utility class: not instantiable.
    }

    /**
     * Builds the full OSC-52 escape sequence that sets the system clipboard (selection
     * {@code "c"}) to {@code text}.
     *
     * <p>Format: {@code ESC ] 52 ; c ; <base64(UTF-8 bytes of text)> BEL}, where {@code ESC}
     * is {@code 0x1B} and {@code BEL} is {@code 0x07}. The base64 is the standard
     * (RFC 4648) encoding of the UTF-8 bytes of {@code text}.</p>
     *
     * <p>Returns an empty string if {@code text} is {@code null} or empty.</p>
     *
     * <p>Note: very large selections may exceed some terminals' OSC-52 length limits; this
     * method does not chunk or cap the payload.</p>
     *
     * @param text the text to place on the clipboard; may be {@code null}
     * @return the OSC-52 sequence, or {@code ""} for null/empty input
     */
    public static String osc52(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String encoded = Base64.getEncoder()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        return OSC52_PREFIX + encoded + BEL;
    }

    /**
     * Best-effort copy to the OS clipboard by spawning a platform tool and feeding
     * {@code text} to it on stdin.
     *
     * <p>The tools tried, in order, by platform:</p>
     * <ul>
     *   <li>macOS: {@code pbcopy}</li>
     *   <li>Windows: {@code clip}</li>
     *   <li>Linux/other: {@code wl-copy} (Wayland), then
     *       {@code xclip -selection clipboard}, then {@code xsel --clipboard --input}</li>
     * </ul>
     *
     * <p>Returns {@code true} as soon as a tool is found and exits {@code 0}; returns
     * {@code false} if no tool succeeds. Never throws; a missing binary, spawn failure, or
     * timeout simply moves on to the next candidate (or returns {@code false}).</p>
     *
     * <p>The child's stdout and stderr are discarded and the text is never printed or logged.</p>
     *
     * @param text the text to copy; {@code null} or empty is treated as a no-op success-free call
     * @return {@code true} if a tool was found and exited {@code 0}, {@code false} otherwise
     */
    public static boolean systemCopy(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (List<String> command : clipboardCommands()) {
            if (runCopy(command, text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any supported system clipboard tool appears available on this machine.
     *
     * <p>The check is cheap: it probes {@code PATH} for the candidate binaries using the
     * platform's locator ({@code where} on Windows, {@code command -v} elsewhere). Never
     * throws.</p>
     *
     * @return {@code true} if at least one candidate tool is locatable, {@code false} otherwise
     */
    public static boolean hasSystemClipboard() {
        for (List<String> command : clipboardCommands()) {
            String tool = command.get(0);
            if (isOnPath(tool)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * The ordered list of clipboard tool invocations appropriate to the current OS. Each entry
     * is a full command line; index 0 is the binary name used for PATH probing.
     */
    private static List<List<String>> clipboardCommands() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of(List.of("pbcopy"));
        }
        if (os.contains("win")) {
            return List.of(List.of("clip"));
        }
        // Linux and everything else: prefer Wayland, then X11 tools.
        return List.of(
                List.of("wl-copy"),
                List.of("xclip", "-selection", "clipboard"),
                List.of("xsel", "--clipboard", "--input"));
    }

    /**
     * Spawns {@code command}, writes {@code text} (UTF-8) to its stdin, then waits up to
     * {@link #PROCESS_TIMEOUT_SECONDS} for a clean exit. Any failure (missing binary, I/O
     * error, timeout, non-zero exit) yields {@code false}. Never throws.
     */
    private static boolean runCopy(List<String> command, String text) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(text.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
            // try-with-resources closes stdin above, signalling EOF to the tool.

            boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        } catch (Exception e) {
            // Missing binary, I/O failure, etc. Do not surface the text in any message.
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    /**
     * Cheaply checks whether {@code tool} resolves on the system {@code PATH}. Uses
     * {@code where} on Windows and {@code command -v} elsewhere. Never throws.
     */
    private static boolean isOnPath(String tool) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> locator = os.contains("win")
                ? List.of("where", tool)
                : List.of("sh", "-c", "command -v " + tool);
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(locator);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            // The locator does not read stdin; close it promptly to avoid any blocking.
            process.getOutputStream().close();

            boolean exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }
}
