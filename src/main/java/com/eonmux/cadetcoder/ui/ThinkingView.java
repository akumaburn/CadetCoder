package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The thinking tokens a reasoning model produces beside its answer, and whether they are shown.
 *
 * <h2>Where thinking arrives</h2>
 *
 * <p>Two forms, and neither reached the screen before this class. A provider that separates the
 * two -- DeepSeek's API, the OpenCode gateways serving it -- puts the reasoning in a field of its
 * own ({@code reasoning_content}); when the reply also carries content, that field was read by
 * nothing and thrown away. A model that instead writes its thought into the reply itself wraps it
 * in a {@code <think>} block, which the parsers strip before acting on it -- so the thought
 * reached every reader except the person watching.</p>
 *
 * <p>The two are carried differently and shown the same way. The provider field arrives by a
 * thread-local side channel -- the completion call returns one string, and the reasoning is not
 * part of it -- reported by the backend that saw it on the wire, and drained at the same funnel
 * every call passes through. The inline blocks are read out of the reply text at display time.
 * Neither is altered: the reply the callers parse, record and send back to the model is the reply
 * it always was, and showing the thinking changes what the user sees, nothing else.</p>
 *
 * <h2>The toggle</h2>
 *
 * <p>In the shell the thinking is printed as a collapsed run, the same way a command's output is:
 * on screen by default, opened with a click or {@code Tab} when wanted. That is the ordinary
 * toggle. To print it inline everywhere, without opening anything:</p>
 *
 * <ul>
 *   <li>For one run: {@code -Dcadet.showThinking=true}</li>
 *   <li>Persistently: {@code cadet config ui.showThinking true}</li>
 * </ul>
 *
 * <p>On a plain console, where nothing can be opened, hidden is the default and the setting
 * shows it. The system property wins over the saved preference when set, and has to spell
 * {@code true} or {@code false}; anything else is not an answer, and the configured preference
 * stands -- the same rule {@link CommandOutputVisibility} applies to command output.</p>
 *
 * <h2>Why the judges never show it</h2>
 *
 * <p>The safety gate and the stand-in answerer ask their questions through the unrecorded path,
 * and their answers are nobody's conversation. A verdict's reasoning printed into the run it was
 * judging would be the model speaking in a voice it was not asked to speak in; rendering happens
 * only on the recorded path, which is where the caller asked to hear from the model.</p>
 */
public final class ThinkingView {

    /** System property that overrides the configured preference for one run. */
    public static final String PROPERTY = "cadet.showThinking";

    /**
     * A reasoning block a model writes into its reply, which is its thought and not its answer.
     * Matched lazily, so two adjacent blocks are two and not one spanning both.
     */
    private static final Pattern THINKING_BLOCK =
            Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    /**
     * The reasoning the provider reported for the request this thread is on, beside the reply
     * rather than inside it. One slot, last attempt wins: the reply that comes back comes from the
     * last attempt, so the thinking shown beside it is that attempt's.
     */
    private static final ThreadLocal<String> REPORTED = new ThreadLocal<>();

    private ThinkingView() {
    }

    /* ------------------------------------------------------------------ *
     * The side channel                                                    *
     * ------------------------------------------------------------------ */

    /**
     * Files the reasoning a backend saw on the wire beside the reply it is about to return.
     *
     * <p>Reported only when the reply carries its own content: when the reasoning is all the model
     * produced, the ordinary completion fallback returns it as the reply, and showing it a second
     * time would print the same words in two places.</p>
     *
     * @param reasoning the provider's reasoning field, or {@code null} when it reported none
     */
    public static void reported(String reasoning) {
        if (reasoning != null && !reasoning.isBlank()) {
            REPORTED.set(reasoning);
        }
    }

    /**
     * Takes the reported reasoning, leaving nothing behind.
     *
     * @return what was reported, or {@code null} when the wire carried no separate reasoning
     */
    public static String drain() {
        String reasoning = REPORTED.get();
        REPORTED.remove();
        return reasoning;
    }

    /** Empties the channel, so a request that never displays leaves nothing for the next one. */
    public static void clear() {
        REPORTED.remove();
    }

    /* ------------------------------------------------------------------ *
     * The inline blocks                                                   *
     * ------------------------------------------------------------------ */

    /**
     * The thinking blocks a reply carries written into it.
     *
     * @param reply the raw model reply, may be {@code null}
     * @return the blocks' text, without the {@code <think>} tags, in reply order
     */
    public static List<String> blocksIn(String reply) {
        List<String> blocks = new ArrayList<>();
        if (reply == null) {
            return blocks;
        }
        Matcher matcher = THINKING_BLOCK.matcher(reply);
        while (matcher.find()) {
            String body = matcher.group()
                    .replaceFirst("(?s)^<think>", "")
                    .replaceFirst("(?s)</think>$", "")
                    .strip();
            if (!body.isEmpty()) {
                blocks.add(body);
            }
        }
        return blocks;
    }

    /* ------------------------------------------------------------------ *
     * The toggle                                                          *
     * ------------------------------------------------------------------ */

    /**
     * Whether thinking should be printed inline rather than collapsed.
     *
     * @return {@code true} when it should stand in the output like any other text
     */
    public static boolean isShownInline() {
        Boolean override = asBoolean(System.getProperty(PROPERTY));
        if (override != null) {
            return override;
        }
        try {
            Configuration.UiConfig ui = ConfigManager.getInstance().getConfig().getUi();
            return ui != null && ui.isShowThinking();
        } catch (RuntimeException e) {
            // A missing or half-initialised configuration must not decide policy by crashing.
            return false;
        }
    }

    /**
     * Reads the override, refusing anything that is not one of the two answers, for the same
     * reason {@code CommandOutputVisibility} refuses it: a value nobody can act on must not turn
     * the setting into its opposite.
     *
     * @param value the property as it was given; {@code null}, blank and unrecognised mean
     *              "not an answer"
     * @return what it says, or {@code null} when it says nothing
     */
    private static Boolean asBoolean(String value) {
        if (value == null) {
            return null;
        }
        String said = value.trim();
        if ("true".equalsIgnoreCase(said)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(said)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /* ------------------------------------------------------------------ *
     * Rendering                                                           *
     * ------------------------------------------------------------------ */

    /**
     * Shows the thinking that arrived beside this reply, in the mode the toggle asks for.
     *
     * <p>Called at the one funnel every recorded completion passes through, after the reply is in
     * hand and before anything acts on it -- so the thinking stands above the reply it belongs to,
     * wherever that reply is printed. The judges' path never reaches here.</p>
     *
     * <p>Collapsed by default where a console can open it again; inline when the setting says so;
     * nowhere at all on a plain console that did not ask. The reply is passed for its inline
     * blocks and is not changed by this.</p>
     *
     * @param reply the raw model reply, which is read and left alone
     */
    public static void renderAlongside(String reply) {
        String reasoning = drain();
        List<String> blocks = blocksIn(reply);
        if (isBlank(reasoning) && blocks.isEmpty()) {
            return;
        }
        if (isShownInline()) {
            print(reasoning, blocks);
            return;
        }
        if (!CollapsedOutput.isSupported()) {
            return;
        }
        ThemedOutputFormatter.routeOutput(CollapsedOutput.OPEN);
        try {
            print(reasoning, blocks);
        } finally {
            // Closed even when the printing threw, or the rest of the run would be filed into a
            // run of hidden lines and vanish from the live console.
            ThemedOutputFormatter.routeOutput(CollapsedOutput.CLOSE);
        }
    }

    /** Prints the thinking itself: one header, then the thought, provider's field first. */
    private static void print(String reasoning, List<String> blocks) {
        OutputFormatter.printSubheader("thinking");
        if (!isBlank(reasoning)) {
            OutputFormatter.println(reasoning.strip());
        }
        for (String block : blocks) {
            if (!isBlank(reasoning)) {
                OutputFormatter.println();
            }
            OutputFormatter.println(block);
        }
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
