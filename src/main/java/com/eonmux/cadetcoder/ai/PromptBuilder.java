package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.metrics.TokenEstimator;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.context.ProjectContext;
import org.magicwerk.brownies.collections.GapList;

import java.util.*;

/**
 * Builds prompts for AI models with templates and token budget management
 */
public class PromptBuilder implements Comparable<PromptBuilder> {
    // System prompt templates
    private static final Map<String, String> SYSTEM_TEMPLATES = new HashMap<>();

    static {
        // Initialize system prompt templates
        SYSTEM_TEMPLATES.put("analyze",
                "You are an expert software engineer analyzing code. " +
                "Provide a detailed analysis of the code, including: " +
                "1. Overall structure and architecture " +
                "2. Code quality and potential issues " +
                "3. Performance considerations " +
                "4. Security concerns " +
                "Be specific and provide examples from the code.");

        SYSTEM_TEMPLATES.put("explain",
                "You are an expert software engineer explaining code. " +
                "Provide a clear explanation of what the code does, including: " +
                "1. Purpose and functionality " +
                "2. Key components and how they interact " +
                "3. Important algorithms or patterns used " +
                "4. Any non-obvious aspects that need explanation " +
                "Use simple language and examples where appropriate.");

        SYSTEM_TEMPLATES.put("suggest",
                "You are an expert software engineer suggesting improvements. " +
                "Provide specific, actionable suggestions to improve the code, including: " +
                "1. Code quality improvements " +
                "2. Performance optimizations " +
                "3. Better design patterns or approaches " +
                "4. Bug fixes or edge case handling " +
                "For each suggestion, provide a brief explanation and example code.");

        SYSTEM_TEMPLATES.put("refactor",
                "You are an expert software engineer refactoring code. " +
                "Provide a complete refactored version of the code that: " +
                "1. Improves readability and maintainability " +
                "2. Follows best practices and design patterns " +
                "3. Maintains the same functionality " +
                "4. Is well-structured and organized " +
                "Explain the key changes you made and why.");
        SYSTEM_TEMPLATES.put("edit",
                "You are a Java expert. Respond ONLY with code.\n" +
                "Project: {projectName}\n" +
                "Active: {currentFiles}\n" +
                "Recent: {lastCommitMessage}\n\n" +
                "Rules:\n" +
                "1. Return full file content with changes\n" +
                "2. Mark edits with // [CADET] comment\n" +
                "3. Preserve original formatting\n" +
                "4. No explanatory text");
    }

    private final String              commandType;
    private final List<String>        codeFiles;
    private final List<String>        contextFiles;
    private final Map<String, Object> parameters;
    private final List<String> userDialogues   = new GapList<>();
    private final List<String> systemDialogues = new GapList<>();
    private       int                 tokenBudget;
    private       String              customSystemPrompt;
    private       String              customSystemReminder;
    private       String       context;
    private       Template     template;

    public PromptBuilder(String commandType, String customSystemPrompt, String customSystemReminder) {
        this(commandType);
        this.customSystemPrompt   = customSystemPrompt;
        this.customSystemReminder = customSystemReminder;
    }

    /**
     * Creates a new PromptBuilder for a specific command type
     *
     * @param commandType The type of command (analyze, explain, suggest, refactor)
     */
    public PromptBuilder(String commandType) {
        this.commandType  = commandType;
        this.codeFiles    = new ArrayList<>();
        this.contextFiles = new ArrayList<>();
        this.parameters   = new HashMap<>();
        // The INPUT budget, not the completion limit. These were the same field, so the space
        // available for a prompt was whatever max_tokens happened to be.
        this.tokenBudget  = ContextWindow.tokens();
        // Use configured template
        String chatTemplateName = ConfigManager.getInstance().getConfig().getAi().getChatTemplate();
        this.template = mapChatTemplateToEnum(chatTemplateName);
    }

    /**
     * Maps chat template name to legacy Template enum
     */
    private Template mapChatTemplateToEnum(String chatTemplateName) {
        if (chatTemplateName == null) {
            return Template.CHATML;
        }
        switch (chatTemplateName.toLowerCase()) {
            case "alpaca":
                return Template.ALPACA;
            case "alpaca-system":
                return Template.ALPACA_SYSTEM;
            case "llama2":
                return Template.LLAMA_V2;
            case "llama3":
                return Template.LLAMA3;
            case "openchat":
                return Template.OPENCHAT;
            case "deepseek":
                return Template.DEEPSEEK;
            case "tekken":
                return Template.V7_TEKKEN;
            case "intel-neural":
                return Template.INTEL_NEURALCHAT;
            case "chatml":
            case "chatml-advanced":
            default:
                return Template.CHATML;
        }
    }

    /**
     * Adds a primary code file to the prompt
     *
     * @param filename The filename
     * @param content  The file content
     * @return This PromptBuilder for chaining
     */
    public PromptBuilder addCodeFile(String filename, String content) {
        codeFiles.add(formatCodeFile(filename, content));
        return this;
    }

    /**
     * Formats a code file for inclusion in the prompt, at most as many lines as context allows.
     *
     * <h2>Why a file is cut by lines here</h2>
     *
     * <p>{@code context.maxLinesPerFile} is how a user says how much of any one file is worth putting
     * in front of a model. It was settable, documented and read by nothing: a file went in whole,
     * however long, and the only thing that ever removed any of it was {@link #truncateToTokenBudget},
     * which cuts the finished prompt at a character count -- so an oversized file did not lose its own
     * tail, it took everything after it with it, silently.</p>
     *
     * <p>The cut is announced and it names the file. A model shown the first stretch of a file with
     * no sign that there is more concludes the file ends there, and writes an edit against a class it
     * believes it has read.</p>
     *
     * @param filename the file's name as the model should see it
     * @param content  the file's content
     * @return the formatted file, cut to the configured line allowance if it was longer
     */
    private String formatCodeFile(String filename, String content) {
        return "File: " + filename + "\n```\n" + withinLineAllowance(filename, content) + "\n```";
    }

    /**
     * The leading lines of a file, as many as {@code context.maxLinesPerFile} allows.
     *
     * <p>An allowance of zero or less is no allowance to keep: it means the file goes in whole.</p>
     *
     * @param filename the file being shown, named in the note when there is one
     * @param content  the file's content
     * @return the content, or its first lines followed by a note saying what was left out
     */
    private static String withinLineAllowance(String filename, String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        int allowance = ConfigManager.getInstance().getConfig().getContext().getMaxLinesPerFile();
        if (allowance <= 0) {
            return content;
        }
        int lines = content.endsWith("\n") ? 0 : 1;
        for (int at = 0; at < content.length(); at++) {
            if (content.charAt(at) == '\n') {
                lines++;
            }
        }
        if (lines <= allowance) {
            return content;
        }
        int cut = 0;
        for (int shown = 0; shown < allowance; shown++) {
            cut = content.indexOf('\n', cut) + 1;
        }
        return content.substring(0, cut)
               + "[" + (lines - allowance) + " further lines of " + filename + " are not shown.]";
    }

    /**
     * Adds a context file to the prompt
     *
     * @param filename The filename
     * @param content  The file content
     * @return This PromptBuilder for chaining
     */
    public PromptBuilder addContextFile(String filename, String content) {
        contextFiles.add(formatCodeFile(filename, content));
        return this;
    }

    /**
     * Sets a parameter for the AI model
     *
     * @param key   The parameter key
     * @param value The parameter value
     * @return This PromptBuilder for chaining
     */
    public PromptBuilder setParameter(String key, Object value) {
        parameters.put(key, value);
        return this;
    }

    /**
     * Sets the token budget for the prompt
     *
     * @param tokenBudget The token budget
     * @return This PromptBuilder for chaining
     */
    public PromptBuilder setTokenBudget(int tokenBudget) {
        this.tokenBudget = tokenBudget;
        return this;
    }

    /**
     * Builds the complete prompt for AI processing.
     *
     * @param editRequest The edit request text
     * @param snippets    Relevant code snippets
     * @param fileContent The content of the target file
     * @param config      The configuration settings
     * @return The complete prompt
     */
    public String buildPrompt(String editRequest, List<String> snippets, String fileContent, Configuration config) {
        String systemPrompt = buildSystemPrompt();
        String userPrompt   = buildUserPrompt(editRequest);

        // Append additional context details to the user prompt.
        if (fileContent != null && !fileContent.isEmpty()) {
            userPrompt += "\nCONTEXT DETAILS:\n" + fileContent;
        }

        // Manage token budget
        userPrompt = truncateToTokenBudget(userPrompt);

        // Return the built prompt
        return userPrompt;
    }

    /**
     * Builds the system prompt based on the command type
     *
     * @return The system prompt
     */
    public String buildSystemPrompt() {
        if (customSystemPrompt != null && !customSystemPrompt.isEmpty()) {
            return customSystemPrompt + "\n" + (customSystemReminder != null ? customSystemReminder : "");
        }
        String systemTemplateContent = SYSTEM_TEMPLATES.getOrDefault(commandType,
                "You are an expert software engineer helping with code.");

        return systemTemplateContent;
    }

    /**
     * Builds the user prompt with code files and context
     *
     * @return The user prompt
     */
    public String buildUserPrompt(String request) {
        StringBuilder prompt = new StringBuilder();

        // Add command-specific instructions
        prompt.append("I need help with the following code ");
        switch (commandType) {
            case "analyze":
                prompt.append("analysis. Please analyze the structure, quality, and potential issues.");
                break;
            case "explain":
                prompt.append("explanation. Please explain what this code does and how it works.");
                break;
            case "suggest":
                prompt.append("improvements. Please suggest ways to improve this code.");
                break;
            case "refactor":
                prompt.append("refactoring. Please provide a refactored version with improvements.");
                break;
            default:
                prompt.append("task.");
                break;
        }
        prompt.append("\n\n");

        prompt.append(request).append("\n\n");

        // Where the work stands, when the user asked to be told; a few lines, so it goes before
        // anything that can be crowded out.
        prompt.append(com.eonmux.cadetcoder.git.RepositoryContext.forPrompt());

        // Add project context if available, bounded so it cannot crowd out the code.
        ProjectContext projectContext = ProjectContext.getInstance();
        if (projectContext.hasProjectContext()) {
            prompt.append("PROJECT CONTEXT:\n");
            prompt.append(boundProjectContext(projectContext.getProjectContext())).append("\n\n");
        }

        // Add primary code files
        prompt.append("PRIMARY CODE FILES:\n");
        for (String codeFile : codeFiles) {
            prompt.append(codeFile).append("\n\n");
        }

        // Add context files if available and within token budget
        if (!contextFiles.isEmpty()) {
            prompt.append("CONTEXT FILES:\n");
            for (String contextFile : contextFiles) {
                prompt.append(contextFile).append("\n\n");
            }
        }

        return prompt.toString();
    }

    /**
     * Truncates the prompt to fit within the token budget
     *
     * @param prompt The prompt to truncate
     * @return The truncated prompt
     */
    /**
     * Share of the prompt budget the project context may occupy.
     *
     * <p>{@code CADET.md} is background, and the code the user asked about is the subject. Because
     * the context is appended before the files, an oversized one is not merely large -- it pushes the
     * files past the budget, and {@link #truncateToTokenBudget} then removes them, leaving a prompt
     * that describes the project and contains none of the code. A project document that has grown to
     * the size of the whole budget is not hypothetical: this repository's own reached it.</p>
     */
    private static final double PROJECT_CONTEXT_BUDGET_SHARE = 0.25;

    /** Tokens held back from the budget for the system prompt and the model's reply. */
    private static final int RESERVED_TOKENS = 500;

    /**
     * Trims the project context to its share of the budget.
     *
     * @param context the project context document
     * @return the context, or its leading portion with a note saying what was left out
     */
    private String boundProjectContext(String context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        int allowance = (int) (TokenEstimator.charsFor(tokenBudget - RESERVED_TOKENS)
                               * PROJECT_CONTEXT_BUDGET_SHARE);
        if (allowance <= 0 || context.length() <= allowance) {
            return context;
        }
        return context.substring(0, allowance)
               + "\n\n[Project context trimmed here to leave room for the code; see CADET.md for the rest.]";
    }

    private String truncateToTokenBudget(String prompt) {
        int availableTokens = tokenBudget - RESERVED_TOKENS;

        if (TokenEstimator.estimate(prompt) <= availableTokens) {
            return prompt;
        }

        // An exhausted budget (tokenBudget < RESERVED_TOKENS) yields a zero-length allowance, so
        // the result is the marker alone rather than a StringIndexOutOfBoundsException.
        int targetLength = TokenEstimator.charsFor(availableTokens);
        if (targetLength < prompt.length()) {
            return prompt.substring(0, targetLength) + "\n\n[Content truncated to fit token budget]";
        }

        return prompt;
    }

    /**
     * Builds the complete prompt for AI processing.
     *
     * @return The complete prompt
     */
    public PromptData buildCompletePromptData() { // Renamed to return PromptData
        String systemPrompt = buildSystemPrompt();
        String userPrompt   = buildUserPrompt("");

        // Manage token budget
        userPrompt = truncateToTokenBudget(userPrompt);

        // Get chat template from configuration
        Configuration config           = ConfigManager.getInstance().getConfig();
        String        chatTemplateName = config.getAi().getChatTemplate();

        return new PromptData(systemPrompt, userPrompt, this.template, chatTemplateName);
    }

    /**
     * Builds the complete prompt for AI processing.
     *
     * @return The complete prompt
     */
    public PromptData buildCompletePromptData(String request) { // Renamed to return PromptData
        String systemPrompt = buildSystemPrompt();
        String userPrompt   = buildUserPrompt(request);

        // Manage token budget
        userPrompt = truncateToTokenBudget(userPrompt);

        // Get chat template from configuration
        Configuration config           = ConfigManager.getInstance().getConfig();
        String        chatTemplateName = config.getAi().getChatTemplate();

        return new PromptData(systemPrompt, userPrompt, this.template, chatTemplateName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getContext(), userDialogues, systemDialogues, getTemplate());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PromptBuilder that = (PromptBuilder) o;
        return Objects.equals(getContext(), that.getContext()) &&
               Objects.equals(userDialogues, that.userDialogues) &&
               Objects.equals(systemDialogues, that.systemDialogues) &&
               getTemplate() == that.getTemplate();
    }

    public String getContext() {
        return context;
    }

    public Template getTemplate() {
        return template;
    }

    @Override
    public int compareTo(PromptBuilder that) {
        if (this.getContext() == null && that.getContext() == null) {
            return 0;
        } else if (this.getContext() == null) {
            return -1;
        } else if (that.getContext() == null) {
            return 1;
        } else {
            return this.getContext().compareTo(that.getContext());
        }
    }

    public PromptBuilder withContext(String context) {
        this.context = context;
        return this;
    }

    public PromptBuilder addUserDialogue(String message) {
        userDialogues.add(message);
        return this;
    }

    public PromptBuilder addSystemDialogue(String message) {
        systemDialogues.add(message);
        return this;
    }

    public PromptBuilder usingTemplate(Template template) {
        this.template = template;
        return this;
    }

    public String prepareInput(String input) {
        StringBuilder inputBuilder = new StringBuilder();
        if (template == Template.CHATML) {
            inputBuilder.append("<|im_start|>user ")
                        .append(input)
                        .append(" <|im_end|><|im_start|>assistant\n");
        } else if (template == Template.OPENCHAT) {
            inputBuilder.append(input);
            inputBuilder.append("<|end_of_turn|>\n");
        } else {
            inputBuilder.append(input);
        }
        return inputBuilder.toString();
    }

    public String build() {
        StringBuilder promptBuilder = new StringBuilder();
        String        dialogues     = interleavedDialogues();
        switch (template) {
            case LLAMA_V2:
                promptBuilder.append("<s>[INST] <<SYS>>\n")
                             .append(context)
                             .append("\n<</SYS>>\n")
                             .append(dialogues);
                break;
            case OPENCHAT:
            case ALPACA:
                promptBuilder.append(
                                     "Below is an instruction that describes a task, paired with an input that provides further context. Write a response that appropriately completes the request. \n ### Instruction:")
                             .append(context)
                             .append("\n")
                             .append(dialogues);
                break;
            case ALPACA_SYSTEM:
            case DEEPSEEK:
                promptBuilder.append(context)
                             .append("\n")
                             .append(dialogues);
                break;
            case INTEL_NEURALCHAT:
                promptBuilder.append("### System:\n")
                             .append(context)
                             .append("\n")
                             .append(dialogues);
                break;
            case CHATML:
                promptBuilder.append("<|im_start|>system\n")
                             .append(context)
                             .append("<|im_end|>\n")
                             .append(dialogues);
                break;
            case V7_TEKKEN:
                promptBuilder.append("<s>[SYSTEM_PROMPT]\n")
                             .append(context)
                             .append("[/SYSTEM_PROMPT]\n")
                             .append(dialogues);
                break;
            case LLAMA3:
                // The one enum value with no format of its own here. Llama 3's format lives in
                // ChatTemplateRegistry, which is the live implementation of every template, so this
                // renders through it rather than growing a second copy that can drift. Throwing was
                // the previous behaviour, for a value this class's OWN mapper returns for
                // ai.chatTemplate=llama3.
                return renderWithRegistry("llama3");
            default:
                throw new IllegalStateException("Unexpected value: " + template);
        }
        return promptBuilder.toString();
    }

    /**
     * Renders this builder's state with a registered {@link com.eonmux.cadetcoder.ai.templates.ChatTemplate}.
     *
     * @param templateName the registry name of the template to render with
     * @return the formatted prompt
     */
    private String renderWithRegistry(String templateName) {
        List<com.eonmux.cadetcoder.ai.templates.ChatTemplate.Message> messages = new ArrayList<>();
        for (String dialogue : userDialogues) {
            messages.add(new com.eonmux.cadetcoder.ai.templates.ChatTemplate.Message("user", dialogue));
        }
        return com.eonmux.cadetcoder.ai.templates.ChatTemplateRegistry.getInstance()
                .getTemplate(templateName)
                .formatConversation(context, messages);
    }

    private String interleavedDialogues() {
        StringBuilder dialogues = new StringBuilder();
        int           userIndex = 0, systemIndex = 0;
        while (userIndex < userDialogues.size() || systemIndex < systemDialogues.size()) {
            if (userIndex < userDialogues.size()) {
                if (template == Template.CHATML) {
                    dialogues.append("<|im_start|>user\n")
                             .append(userDialogues.get(userIndex++))
                             .append("<|im_end|>");
                } else if (template == Template.OPENCHAT) {
                    dialogues.append(userDialogues.get(userIndex++));
                    dialogues.append("<|end_of_turn|>");
                } else if (template == Template.V7_TEKKEN) {
                    dialogues.append("[INST]");
                    dialogues.append(userDialogues.get(userIndex++));
                    dialogues.append("[/INST]");
                } else if (template == Template.DEEPSEEK) {
                    dialogues.append("<|User|>");
                    dialogues.append(userDialogues.get(userIndex++));
                } else {
                    dialogues.append(userDialogues.get(userIndex++));
                }
            }
            if (systemIndex < systemDialogues.size()) {
                if (template == Template.CHATML) {
                    dialogues.append("<|im_start|>assistant\n")
                             .append(systemDialogues.get(systemIndex++))
                             .append("<|im_end|>");
                } else if (template == Template.OPENCHAT) {
                    dialogues.append(systemDialogues.get(systemIndex++));
                    dialogues.append("<|end_of_turn|>");
                } else if (template == Template.DEEPSEEK) {
                    dialogues.append("<|Assistant|>");
                    dialogues.append(systemDialogues.get(systemIndex++));
                    dialogues.append("<|end_of_sentence|>");
                } else if (template == Template.V7_TEKKEN) {
                    dialogues.append(systemDialogues.get(systemIndex++));
                    dialogues.append("</s>");
                } else {
                    dialogues.append(systemDialogues.get(systemIndex++));
                }
            }
        }
        return dialogues.toString();
    }

    public enum Template {
        ALPACA, ALPACA_SYSTEM, LLAMA_V2, INTEL_NEURALCHAT, CHATML, OPENCHAT, V7_TEKKEN, DEEPSEEK,
        // Legacy enum values - new code should use ChatTemplate names instead
        @Deprecated
        LLAMA3
    }
}
