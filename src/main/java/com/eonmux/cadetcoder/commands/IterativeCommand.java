package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.CommandRegistry;

import java.util.Map;
import java.util.Objects;

/**
 * Interface for commands that support iterative, multi-step execution with LLM interaction.
 * Commands implementing this interface can execute in steps, maintaining context between iterations.
 */
public interface IterativeCommand extends CommandRegistry.Command {

    /**
     * The title the run is announced under, or {@code null} to announce nothing.
     *
     * <p>The executor used to print {@code "Starting iterative execution for " + getClass()
     * .getSimpleName()} unconditionally, which both leaked the internal Java type name and produced
     * a second frame for the commands that already announce themselves -- a chat run opened with
     * {@code Starting iterative execution for ChatCommand} immediately followed by
     * {@code Processing your request: ...}.</p>
     *
     * <p>The default names the command, which is what the commands with no announcement of their own
     * (analyze, explain, refactor, search) need. A command that prints its own opening line returns
     * {@code null}.</p>
     *
     * @param args the command's arguments
     * @return the title, or {@code null} for no announcement
     */
    default String getRunTitle(String[] args) {
        String simple = getClass().getSimpleName();
        if (simple.endsWith("Command") && simple.length() > "Command".length()) {
            simple = simple.substring(0, simple.length() - "Command".length());
        }
        return simple.isEmpty() ? null : simple.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Executes a single step of the command with the given context.
     *
     * @param args        The original command arguments
     * @param context     The context from previous steps
     * @param llmResponse The LLM response for this step (null for first step)
     * @return The result of this step execution
     */
    StepResult executeStep(String[] args, Map<String, Object> context, String llmResponse);

    /**
     * Gets the initial prompt for the LLM to start the iterative process.
     *
     * @param args The command arguments
     * @return The initial prompt string
     */
    String getInitialPrompt(String[] args);

    /**
     * Checks if the command supports iterative execution for the given arguments.
     *
     * @param args The command arguments
     * @return true if iterative execution is supported
     */
    default boolean supportsIterativeExecution(String[] args) {
        return true;
    }

    /**
     * The system prompt the executor should use for the LLM calls it makes on follow-up turns.
     *
     * <p>By default this returns {@code null}, and {@link IterativeExecutor} falls back to its own
     * generic system prompt. A command whose first turn establishes a specific contract with the
     * model (an available-command catalog, a required response format, etc.) should override this
     * to return that same system prompt, so every follow-up turn is held to the identical contract
     * instead of degrading to a generic prompt that no longer advertises the catalog or format. A
     * degraded follow-up prompt is what lets a model improvise unparseable output (e.g. a shell
     * {@code cat} or a bare {@code ACTION} header) that ends the loop without completing the task.</p>
     *
     * @return the system prompt for follow-up LLM turns, or {@code null} to use the executor default
     */
    default String getIterativeSystemPrompt() {
        return null;
    }

    /**
     * Represents the result of a command step execution.
     *
     * <p>Null-handling contract:
     * <ul>
     *   <li>{@code context} must not be null; the executor relies on it (e.g. {@code context.putAll(...)}).</li>
     *   <li>{@code output} may be null (callers guard for null before using it).</li>
     *   <li>{@code nextPrompt} may be null, but must not be null when {@code isComplete} is false,
     *       otherwise execution ends with a "no next prompt" warning.</li>
     * </ul>
     */
    class StepResult {

        /**
         * Who a step's next prompt is written for.
         *
         * <p>The executor has to route a prompt to the model or to the person at the terminal, and
         * it used to decide by reading the prompt: a yes/no, a "please specify", an "option 1". That
         * cannot work, because a prompt is not only authored text. {@code ChatCommand} builds its
         * next prompt by embedding the output the last command produced, so a {@code read} or
         * {@code grep} over a file containing any of those phrases turned the model's own instruction
         * into a question aimed at the user — an unanswerable one, since the text explaining it was
         * never shown.</p>
         *
         * <p>Whoever writes the prompt knows the answer without guessing, so they say. This mirrors
         * the fix already applied to {@link #isError()}, which had the same defect in the same shape:
         * a substring scan of captured output, replaced by an explicit flag with a narrow fallback.</p>
         */
        enum Audience {
            /** The prompt states a protocol only the model can satisfy. */
            MODEL,
            /** The prompt is a question for the person at the terminal. */
            USER
        }

        private final boolean             isComplete;
        private final String              output;
        private final Map<String, Object> context;
        private final String              nextPrompt;
        // null = unspecified -> isError() falls back to a heuristic over output.
        private final Boolean             error;
        // null = undeclared -> the executor falls back to reading the prompt.
        private final Audience            audience;
        // A run the user took back: neither a success nor a failure. See interrupted().
        private final boolean             interrupted;

        /**
         * Creates a step result with an unspecified error state (the executor classifies it via
         * the heuristic in {@link #isError()}).
         *
         * @param isComplete whether the iterative execution is complete
         * @param output     human-readable output for this step; may be null
         * @param context    the context to propagate to the next step; must not be null
         * @param nextPrompt the prompt for the next step; may be null, but should be non-null when
         *                   {@code isComplete} is false
         * @throws NullPointerException if {@code context} is null
         */
        public StepResult(boolean isComplete, String output, Map<String, Object> context, String nextPrompt) {
            this(isComplete, output, context, nextPrompt, null);
        }

        /**
         * Creates a step result with an explicit success/error state.
         *
         * @param error {@code Boolean.TRUE} if this step is a failure, {@code Boolean.FALSE} for an
         *              explicit success, or {@code null} to leave it unspecified (heuristic fallback)
         * @throws NullPointerException if {@code context} is null
         */
        public StepResult(boolean isComplete, String output, Map<String, Object> context, String nextPrompt,
                          Boolean error) {
            this(isComplete, output, context, nextPrompt, error, null);
        }

        /**
         * Creates a step result that also declares who its next prompt is addressed to.
         *
         * @param audience {@code null} to leave it undeclared, so the executor reads the prompt
         */
        public StepResult(boolean isComplete, String output, Map<String, Object> context, String nextPrompt,
                          Boolean error, Audience audience) {
            this(isComplete, output, context, nextPrompt, error, audience, false);
        }

        private StepResult(boolean isComplete, String output, Map<String, Object> context,
                           String nextPrompt, Boolean error, Audience audience, boolean interrupted) {
            this.isComplete  = isComplete;
            this.output      = output;
            this.context     = Objects.requireNonNull(context, "context cannot be null");
            this.nextPrompt  = nextPrompt;
            this.error       = error;
            this.audience    = audience;
            this.interrupted = interrupted;
        }

        /**
         * A copy of this result declaring its prompt as one only the model can answer.
         *
         * @return a new result; this one is unchanged
         */
        public StepResult addressedToModel() {
            return new StepResult(isComplete, output, context, nextPrompt, error, Audience.MODEL,
                                  interrupted);
        }

        /**
         * A copy of this result declaring its prompt a question for the person at the terminal.
         *
         * @return a new result; this one is unchanged
         */
        public StepResult addressedToUser() {
            return new StepResult(isComplete, output, context, nextPrompt, error, Audience.USER,
                                  interrupted);
        }

        /**
         * @return who the next prompt is for, or {@code null} when the producer did not say
         */
        public Audience getAudience() {
            return audience;
        }

        /** Terminal success result: complete, explicitly not an error, no further prompt. */
        public static StepResult success(String output, Map<String, Object> context) {
            return new StepResult(true, output, context, null, Boolean.FALSE);
        }

        /** Terminal failure result: complete, explicitly an error, no further prompt. */
        public static StepResult failure(String output, Map<String, Object> context) {
            return new StepResult(true, output, context, null, Boolean.TRUE);
        }

        /**
         * Terminal result for a run the user took back: complete, and neither a success nor a
         * failure.
         *
         * <p>Not an error, because nothing went wrong and the reason is not worth printing in red;
         * not a success either, because the task it was given is not done. The executor reports it
         * as {@link com.eonmux.cadetcoder.ExitCode#INTERRUPTED}, which is what {@code bash} and
         * {@code workers} have always reported for the same event -- while the steps that used to
         * build this out of {@link #success} told the shell the agent had finished the job.</p>
         *
         * @param output  what to say about the run that was stopped
         * @param context the context to carry out of the run; must not be null
         * @return a completed result marked as taken back
         */
        public static StepResult interrupted(String output, Map<String, Object> context) {
            return new StepResult(true, output, context, null, Boolean.FALSE, null, true);
        }

        public boolean isComplete() {
            return isComplete;
        }

        public String getOutput() {
            return output;
        }

        public Map<String, Object> getContext() {
            return context;
        }

        public String getNextPrompt() {
            return nextPrompt;
        }

        /**
         * Whether this result represents a failure, used by the executor to choose the process
         * exit code. An explicit error state (set via {@link #success}, {@link #failure}, or the
         * 5-argument constructor) is authoritative and should be the source of truth for all new code.
         *
         * <p>When the error state is unspecified, the fallback is restricted to a structured
         * {@code ERROR:} sentinel at the start of the output. The previous heuristic substring-scanned
         * the entire (often command-captured) output for words like "Error"/"failed"/"not found",
         * which misclassified perfectly successful output as a failure — e.g. a successful grep/read
         * whose file content merely contains the word "Error", or a listing that mentions a file "not
         * found" elsewhere. The captured exit code is authoritative for command results; this sentinel
         * only flags steps that deliberately mark themselves as errors via the {@code ERROR:} prefix.</p>
         */
        public boolean isError() {
            if (error != null) {
                return error;
            }
            if (output == null) {
                return false;
            }
            String trimmed = output.trim();
            // Legacy success sentinels are never errors.
            if (trimmed.startsWith("Response:") || trimmed.startsWith("SUCCESS:")) {
                return false;
            }
            // Only a structured leading ERROR: sentinel counts as a failure here; arbitrary captured
            // output containing failure-like words must NOT be treated as an error.
            return trimmed.startsWith("ERROR:");
        }

        /**
         * Whether the run this step ended was taken back rather than finished or failed.
         *
         * @return whether the user asked for it to stop
         */
        public boolean isInterrupted() {
            return interrupted;
        }

        /** Whether an explicit success/error state was provided (vs. relying on the heuristic). */
        public boolean hasExplicitErrorState() {
            return error != null;
        }
    }
}