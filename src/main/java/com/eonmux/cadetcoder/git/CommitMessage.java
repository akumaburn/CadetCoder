package com.eonmux.cadetcoder.git;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Renders the configured auto-commit message template.
 *
 * <h2>Why this is not done at the call site</h2>
 *
 * <p>It was, in three places, and each one substituted a different placeholder. The shipped default
 * is {@code "Auto-commit on {date}"}; {@code edit} and {@code multiedit} substituted only
 * {@code {changeSummary}}, so every commit either of them made was literally titled
 * <em>Auto-commit on {date}</em>. The scheduler substituted only {@code {date}}, so a user who
 * changed the template to mention {@code {changeSummary}} got that word in their history instead.
 * Neither failed, and a commit message nobody reads until later is exactly the kind of thing that
 * stays wrong.</p>
 *
 * <p>So every placeholder is substituted here, whatever the caller knows about. A caller with no
 * change summary passes {@code null} and gets a template with the placeholder removed rather than
 * printed.</p>
 */
public final class CommitMessage {

    /** Used when the configured template is missing or blank. */
    public static final String DEFAULT_TEMPLATE = "Auto-commit on {date}";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private CommitMessage() {
    }

    /**
     * Renders {@code template} with the placeholders it supports.
     *
     * <p>Supported: {@code {date}}, {@code {time}}, {@code {changeSummary}}.</p>
     *
     * @param template      the configured template; {@code null} or blank falls back to the default
     * @param changeSummary what changed, or {@code null} when the caller does not know
     * @return a non-blank commit message
     */
    public static String render(String template, String changeSummary) {
        String text = template == null || template.isBlank() ? DEFAULT_TEMPLATE : template;
        String summary = changeSummary == null || changeSummary.isBlank() ? "" : changeSummary;

        String rendered = text
                .replace("{date}", LocalDate.now().toString())
                .replace("{time}", LocalTime.now().format(TIME))
                .replace("{changeSummary}", summary)
                .trim();

        // A template that was nothing but {changeSummary} leaves an empty message, and git refuses
        // one; the fallback keeps the commit rather than failing an edit that already succeeded.
        return rendered.isEmpty() ? render(DEFAULT_TEMPLATE, null) : rendered;
    }
}
