package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

/**
 * The single expression of what {@code security.allowedActions} permits.
 *
 * <h2>Why this is not a check each agent path writes for itself</h2>
 *
 * <p>The setting names the only commands an agent may run. It used to be read in one place,
 * {@code SecurityValidator.validateAction}, which screens the classic {@code chat} loop. The default
 * {@code agent} harness never consults that validator: it reads an action, builds a
 * {@code CommandInvocation} and runs it. So a user who narrowed the list to {@code read} and
 * {@code search} narrowed nothing on the path the tool runs by default.</p>
 *
 * <p>Both paths now ask here, so the answer cannot differ between them, and a third path added later
 * has one obvious place to ask.</p>
 *
 * <h2>What the setting names</h2>
 *
 * <p>This tool's own action names, such as {@code read}, {@code write} and {@code bash}. It is not a
 * list of shell programs; {@code security.allowedCommands} is that list, and it screens what
 * {@code bash} may start.</p>
 */
public final class AllowedActions {

    private AllowedActions() {
    }

    /**
     * Whether an action may run at all.
     *
     * @param actionName this tool's name for the action, such as {@code bash}
     * @return {@code true} when the list is empty, or when it names this action
     */
    public static boolean permits(String actionName) {
        String[] allowed = configured();
        if (allowed.length == 0) {
            // The shipped default. No list means no narrowing, rather than nothing permitted.
            return true;
        }
        if (actionName == null || actionName.isBlank()) {
            return false;
        }
        String asked = actionName.trim().toLowerCase();
        for (String entry : allowed) {
            if (entry != null && asked.equals(entry.trim().toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why an action may not run.
     *
     * @param actionName this tool's name for the action
     * @return a sentence naming the action and the setting, or {@code null} when it may run
     */
    public static String reasonToRefuse(String actionName) {
        if (permits(actionName)) {
            return null;
        }
        String asked = actionName == null || actionName.isBlank() ? "" : actionName.trim();
        return "Action '" + asked + "' is not in security.allowedActions";
    }

    /** The configured list, or an empty array when it cannot be read. */
    private static String[] configured() {
        try {
            Configuration config = ConfigManager.getInstance().getConfig();
            Configuration.SecurityConfig security = config == null ? null : config.getSecurity();
            String[] allowed = security == null ? null : security.getAllowedActions();
            return allowed == null ? new String[0] : allowed;
        } catch (RuntimeException unreadable) {
            // A half-initialised configuration must not decide policy by crashing. The shipped
            // default permits everything, so that is what an unreadable one means here too.
            return new String[0];
        }
    }
}
