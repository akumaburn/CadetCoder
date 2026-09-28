package com.eonmux.cadetcoder.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the context for a chat command execution.
 * 
 * This class maintains the state of the chat command execution across multiple steps,
 * including the current step, user request, actions, and error information.
 * It is serialized to a map and passed between steps in the executeStep method.
 */
public class ChatContext {

    /** The current step in the execution flow (e.g., "initial", "analyze_request", "execute_single_action") */
    private String step;
    
    /** The original user request that initiated the chat command */
    private String userRequest;
    
    /** Counter for tracking format retry attempts when AI response doesn't follow expected format */
    private int formatRetryCount;
    
    /** The current action being executed */
    private ChatCommand.AIAction currentAction;
    
    /** The action that failed during execution, used for error handling and recovery */
    private ChatCommand.AIAction failedAction;
    
    /** Output captured from the last command execution */
    private String lastCommandOutput;
    
    /** Error message captured from the last command execution */
    private String lastCommandError;
    
    /** Classification of the last error (e.g., "file_not_found", "permission", "validation") */
    private String lastErrorType;
    
    /** Additional details about the last error for more specific error handling */
    private String lastErrorDetails;
    
    /** Counter for tracking action retry attempts to prevent infinite retry loops */
    private int actionRetryCount;

    /**
     * How many actions in a row the safety screen has refused, with none run in between.
     *
     * <p>Kept apart from {@link #formatRetryCount} because a refused action followed the format: it
     * was read, and refused for what it would do. Counted in the format budget, two refusals ended
     * the run with a message about the format of a block that had none wrong with it.</p>
     */
    private int refusedActionCount;
    
    /** History of executed actions for loop detection and context awareness */
    private List<String> actionHistory = new ArrayList<>();

    /**
     * How many of uber mode's closing questions this run has answered since it last did any work.
     *
     * <p>A streak rather than a total. Running an action sets it back to zero, so the questions go
     * on for as long as the run keeps finding things to do, and the run ends only once the model
     * has answered every question in a row without acting on any of them.</p>
     *
     * <p>Carried through {@link #toMap()} for the same reason the loop guard is: a fresh context is
     * rebuilt on every step, so a count held only as a field would be zero again by the time the
     * next claim arrived and the run could be sent back for ever.</p>
     */
    private int uberChecksPassed;

    /**
     * The ids of the running jobs this run has been asked about when it said it was done.
     *
     * <p>Carried through {@link #toMap()} for the reason {@link #uberChecksPassed} is: held only as
     * a field, it would be empty again by the next claim, and a run choosing to leave its jobs
     * running would be asked about them for ever. See {@link JobsLeftRunning}.</p>
     */
    private List<String> jobsAskedAbout = List.of();

    /**
     * Loop protection for this run.
     *
     * <p>Carried through {@link #toMap()} like {@code currentAction} is, because a fresh
     * {@code ChatContext} is rebuilt from the map on every step: a guard held only as a field would
     * be reset before it had observed anything and could never detect a loop. The map is passed
     * between steps in memory only and is never serialized, so holding a live object in it is safe.</p>
     */
    private ActionLoopGuard loopGuard = new ActionLoopGuard();

    /** Map of found files (filename -> full path) for path resolution and substitution */
    private Map<String, String> foundFiles = new HashMap<>();

    /**
     * Returns this run's loop guard.
     *
     * @return the guard, never {@code null}
     */
    public ActionLoopGuard getLoopGuard() {
        return loopGuard;
    }

    /**
     * Adopts a loop guard carried over from a previous step.
     *
     * @param loopGuard the guard to adopt; ignored when {@code null}
     */
    public void setLoopGuard(ActionLoopGuard loopGuard) {
        if (loopGuard != null) {
            this.loopGuard = loopGuard;
        }
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
    }

    public String getUserRequest() {
        return userRequest;
    }

    public void setUserRequest(String userRequest) {
        this.userRequest = userRequest;
    }

    public int getFormatRetryCount() {
        return formatRetryCount;
    }

    public void setFormatRetryCount(int formatRetryCount) {
        this.formatRetryCount = formatRetryCount;
    }

    public ChatCommand.AIAction getCurrentAction() {
        return currentAction;
    }

    public void setCurrentAction(ChatCommand.AIAction currentAction) {
        this.currentAction = currentAction;
    }

    public ChatCommand.AIAction getFailedAction() {
        return failedAction;
    }

    public void setFailedAction(ChatCommand.AIAction failedAction) {
        this.failedAction = failedAction;
    }

    public String getLastCommandOutput() {
        return lastCommandOutput;
    }

    public void setLastCommandOutput(String lastCommandOutput) {
        this.lastCommandOutput = lastCommandOutput;
    }

    public String getLastCommandError() {
        return lastCommandError;
    }

    public void setLastCommandError(String lastCommandError) {
        this.lastCommandError = lastCommandError;
    }
    
    public String getLastErrorType() {
        return lastErrorType;
    }
    
    public void setLastErrorType(String lastErrorType) {
        this.lastErrorType = lastErrorType;
    }
    
    public String getLastErrorDetails() {
        return lastErrorDetails;
    }
    
    public void setLastErrorDetails(String lastErrorDetails) {
        this.lastErrorDetails = lastErrorDetails;
    }

    /** @return how many closing questions this run has answered since it last did any work */
    public int getUberChecksPassed() {
        return uberChecksPassed;
    }

    public void setUberChecksPassed(int uberChecksPassed) {
        this.uberChecksPassed = Math.max(0, uberChecksPassed);
    }

    /** @return the ids of the running jobs this run has been asked about when it said it was done */
    public List<String> getJobsAskedAbout() {
        return jobsAskedAbout;
    }

    public void setJobsAskedAbout(List<String> jobsAskedAbout) {
        this.jobsAskedAbout = jobsAskedAbout == null ? List.of() : List.copyOf(jobsAskedAbout);
    }

    public int getActionRetryCount() {
        return actionRetryCount;
    }

    public void setActionRetryCount(int actionRetryCount) {
        this.actionRetryCount = actionRetryCount;
    }

    public int getRefusedActionCount() {
        return refusedActionCount;
    }

    public void setRefusedActionCount(int refusedActionCount) {
        this.refusedActionCount = Math.max(0, refusedActionCount);
    }

    public List<String> getActionHistory() {
        return actionHistory;
    }

    public void setActionHistory(List<String> actionHistory) {
        this.actionHistory = actionHistory;
    }
    
    /**
     * Trims the action history to the specified maximum size.
     * This helps prevent the context from growing too large over time.
     * 
     * @param maxSize The maximum number of actions to keep in history
     */
    public void trimActionHistory(int maxSize) {
        if (actionHistory != null && actionHistory.size() > maxSize) {
            // Copy the retained tail first (subList is a view backed by the
            // original list, so it must be materialized before clearing), then
            // mutate the existing list in place. Reassigning the field instead
            // would leave any caller that already holds a reference to this
            // list pointing at the untrimmed instance.
            List<String> retained = new ArrayList<>(actionHistory.subList(
                actionHistory.size() - maxSize, actionHistory.size()));
            actionHistory.clear();
            actionHistory.addAll(retained);
        }
    }

    public Map<String, String> getFoundFiles() {
        return foundFiles;
    }

    public void setFoundFiles(Map<String, String> foundFiles) {
        this.foundFiles = foundFiles;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        if (step != null) {
            map.put("step", step);
        }
        if (userRequest != null) {
            map.put("userRequest", userRequest);
        }
        map.put("formatRetryCount", formatRetryCount);
        if (currentAction != null) {
            map.put("currentAction", currentAction);
        }
        if (failedAction != null) {
            map.put("failedAction", failedAction);
        }
        if (lastCommandOutput != null) {
            map.put("lastCommandOutput", lastCommandOutput);
        }
        if (lastCommandError != null) {
            map.put("lastCommandError", lastCommandError);
        }
        if (lastErrorType != null) {
            map.put("lastErrorType", lastErrorType);
        }
        if (lastErrorDetails != null) {
            map.put("lastErrorDetails", lastErrorDetails);
        }
        map.put("actionRetryCount", actionRetryCount);
        map.put("refusedActionCount", refusedActionCount);
        map.put("uberChecksPassed", uberChecksPassed);
        map.put(JobsLeftRunning.ASKED_KEY, jobsAskedAbout);
        if (actionHistory != null) {
            map.put("actionHistory", actionHistory);
        }
        map.put("loopGuard", loopGuard);
        if (foundFiles != null) {
            map.put("foundFiles", foundFiles);
        }
        return map;
    }

    /**
     * The context a step was left in, rebuilt from the map the executor carries between steps.
     *
     * <h2>Why every read is type-checked</h2>
     *
     * <p>The map is a plain {@code Map<String, Object>} crossing a step boundary, so nothing in the
     * type system says the value under {@code "step"} is still a {@code String}. An unchecked cast
     * would fail deep inside a later step, in a message naming a field this class never mentions. A
     * value of the wrong type is dropped and reported by name here instead.</p>
     *
     * <h2>Why the collections are copied element by element</h2>
     *
     * <p>{@code instanceof List} says nothing about what is in the list: generics are erased, so a
     * cast to {@code List<String>} succeeds over a list of anything at all and only fails later, at
     * whichever unrelated line first reads an element. Guarding that cast with
     * {@code catch (ClassCastException)} -- which is what this used to do -- therefore caught
     * nothing. Copying out only the elements of the expected type moves the failure here, where it
     * can be named.</p>
     *
     * @param map what the previous step left behind, or {@code null} before any step has run
     * @param log where a value of the wrong type is reported
     * @return the rebuilt context; never null
     */
    static ChatContext fromMap(Map<String, Object> map, LoggingCommandSupport log) {
        ChatContext context = new ChatContext();
        if (map == null) {
            return context;
        }
        context.setStep(text(map, "step", log, context.getStep()));
        context.setUserRequest(text(map, "userRequest", log, context.getUserRequest()));
        context.setLastCommandOutput(
                text(map, "lastCommandOutput", log, context.getLastCommandOutput()));
        context.setLastCommandError(
                text(map, "lastCommandError", log, context.getLastCommandError()));
        context.setLastErrorType(text(map, "lastErrorType", log, context.getLastErrorType()));
        context.setLastErrorDetails(
                text(map, "lastErrorDetails", log, context.getLastErrorDetails()));

        context.setFormatRetryCount(count(map, "formatRetryCount", log, context.getFormatRetryCount()));
        context.setActionRetryCount(count(map, "actionRetryCount", log, context.getActionRetryCount()));
        context.setRefusedActionCount(
                count(map, "refusedActionCount", log, context.getRefusedActionCount()));
        context.setUberChecksPassed(
                count(map, "uberChecksPassed", log, context.getUberChecksPassed()));

        context.setCurrentAction(action(map, "currentAction", log, context.getCurrentAction()));
        context.setFailedAction(action(map, "failedAction", log, context.getFailedAction()));

        List<String> history = strings(map, "actionHistory", log);
        if (history != null) {
            context.setActionHistory(history);
        }
        List<String> askedAbout = strings(map, JobsLeftRunning.ASKED_KEY, log);
        if (askedAbout != null) {
            context.setJobsAskedAbout(askedAbout);
        }
        Map<String, String> files = pairs(map, "foundFiles", log);
        if (files != null) {
            context.setFoundFiles(files);
        }

        // The loop guard must survive across steps: a fresh ChatContext is built here on EVERY step,
        // so a guard that were only a field would be reset before it had observed anything.
        if (map.get("loopGuard") instanceof ActionLoopGuard) {
            context.setLoopGuard((ActionLoopGuard) map.get("loopGuard"));
        }
        return context;
    }

    /** The value under {@code key} when it is text, otherwise {@code fallback} and a warning. */
    private static String text(Map<String, Object> map, String key, LoggingCommandSupport log,
                               String fallback) {
        Object value = map.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        wrongType(value, key, log);
        return fallback;
    }

    /** The value under {@code key} when it is a whole number, otherwise {@code fallback}. */
    private static int count(Map<String, Object> map, String key, LoggingCommandSupport log,
                             int fallback) {
        Object value = map.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        wrongType(value, key, log);
        return fallback;
    }

    /** The value under {@code key} when it is an action, otherwise {@code fallback}. */
    private static ChatCommand.AIAction action(Map<String, Object> map, String key,
                                               LoggingCommandSupport log,
                                               ChatCommand.AIAction fallback) {
        Object value = map.get(key);
        if (value instanceof ChatCommand.AIAction) {
            return (ChatCommand.AIAction) value;
        }
        wrongType(value, key, log);
        return fallback;
    }

    /**
     * The text elements of the list under {@code key}, or {@code null} when there is no list there.
     *
     * <p>An element of another type is dropped rather than carried, so the caller cannot be handed a
     * {@code List<String>} that throws on iteration.</p>
     */
    private static List<String> strings(Map<String, Object> map, String key,
                                        LoggingCommandSupport log) {
        Object value = map.get(key);
        if (!(value instanceof List)) {
            wrongType(value, key, log);
            return null;
        }
        List<String> kept = new ArrayList<>();
        for (Object element : (List<?>) value) {
            if (element instanceof String) {
                kept.add((String) element);
            } else {
                wrongType(element, key + " entry", log);
            }
        }
        return kept;
    }

    /**
     * The text-to-text entries of the map under {@code key}, or {@code null} when there is no map
     * there. An entry of another type is dropped, for the reason {@link #strings} gives.
     */
    private static Map<String, String> pairs(Map<String, Object> map, String key,
                                             LoggingCommandSupport log) {
        Object value = map.get(key);
        if (!(value instanceof Map)) {
            wrongType(value, key, log);
            return null;
        }
        Map<String, String> kept = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                kept.put((String) entry.getKey(), (String) entry.getValue());
            } else {
                wrongType(entry.getValue(), key + " entry", log);
            }
        }
        return kept;
    }

    /**
     * Reports a value that is present but of the wrong type. An absent value is not wrong -- most of
     * these keys are only written once the run reaches the step that sets them.
     */
    private static void wrongType(Object value, String key, LoggingCommandSupport log) {
        if (value != null && log != null) {
            log.logWarning("Context Deserialization",
                           "Ignoring " + key + ": expected a different type, got "
                           + value.getClass().getSimpleName());
        }
    }
}
