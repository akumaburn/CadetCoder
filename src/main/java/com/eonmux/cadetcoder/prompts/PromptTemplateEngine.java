package com.eonmux.cadetcoder.prompts;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.logging.CadetLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A template engine for managing and customizing AI prompts.
 * Allows users to override default prompts with custom templates.
 */
public class PromptTemplateEngine {
    private static final CadetLogger logger               = CadetLogger.getLogger(PromptTemplateEngine.class);
    private static final Pattern     VARIABLE_PATTERN     = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");
    private static final String      DEFAULT_PROMPT_DIR   = "/prompts/default/";
    private static final String      USER_PROMPT_DIR_NAME = "prompts";

    /**
     * The prompt templates that ship with the tool.
     *
     * <p>One list, used both to seed the {@code .example} files and to answer {@code prompt list}.
     * They were two separate hardcoded arrays, so a template added to one was invisible in the
     * other -- discoverable on disk but absent from the listing, or the reverse.</p>
     */
    private static final String[] DEFAULT_PROMPT_NAMES = {
            "system", "chat", "edit", "analyze", "explain",
            "suggest", "refactor", "agent", "webfetch", "websearch", "ubermode"
    };

    private static PromptTemplateEngine instance;
    /**
     * Loaded templates, keyed by prompt name.
     *
     * <p>Concurrent because this is a process-wide singleton read on every LLM completion, from
     * every worker thread, and cleared from the shell thread by {@code prompt reload}. As a plain
     * {@code HashMap} read with {@code containsKey} followed by {@code get}, a racing write could
     * make the pair disagree: the key was present and the value came back null, which
     * {@code substituteVariables} passes straight through, so that request went to the provider
     * with no system prompt at all -- no operating principles, and a broken cacheable prefix.</p>
     */
    private final  Map<String, String>  promptCache  = new java.util.concurrent.ConcurrentHashMap<>();
    private final  Path                 userPromptDir;
    private final  ObjectMapper         objectMapper = new ObjectMapper();

    private PromptTemplateEngine() {
        // Initialize user prompt directory
        Path promptDir;
        try {
            String baseDir = ConfigManager.getInstance().getConfig().getBaseDir();
            promptDir = Paths.get(baseDir).resolve(USER_PROMPT_DIR_NAME);
        } catch (Exception e) {
            // If config is not available (e.g., during testing), use temp directory
            promptDir = Paths.get(System.getProperty("java.io.tmpdir")).resolve("cadet-prompts");
        }
        this.userPromptDir = promptDir;

        try {
            if (!Files.exists(userPromptDir)) {
                Files.createDirectories(userPromptDir);
            }
            initializeUserPromptDirectory();
        } catch (IOException e) {
            logger.error("Failed to create prompt directory", e);
        }
    }

    private void initializeUserPromptDirectory() {
        try {
            // Copy default prompts to user directory as examples
            copyDefaultPromptsToUserDir();
        } catch (Exception e) {
            logger.warn("Failed to copy default prompts: " + e.getMessage());
        }
    }

    private void copyDefaultPromptsToUserDir() {
        for (String promptName : DEFAULT_PROMPT_NAMES) {
            String promptFile = promptName + ".json";
            try {
                Path userFile = userPromptDir.resolve(promptFile + ".example");
                if (!Files.exists(userFile)) {
                    String content = loadDefaultPrompt(promptFile);
                    if (content != null) {
                        Files.writeString(userFile, content, StandardCharsets.UTF_8);
                        logger.info("Created example prompt file: " + userFile);
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to copy default prompt: " + promptFile + " - " + e.getMessage());
            }
        }
    }

    private String loadDefaultPrompt(String filename) {
        try (InputStream is = getClass().getResourceAsStream(DEFAULT_PROMPT_DIR + filename)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            logger.error("Failed to load default prompt: " + filename, e);
        }
        return null;
    }

    public static synchronized PromptTemplateEngine getInstance() {
        if (instance == null) {
            instance = new PromptTemplateEngine();
        }
        return instance;
    }

    /**
     * Get a prompt template by name, with variable substitution.
     * First checks user overrides, then falls back to defaults.
     *
     * @param promptName The name of the prompt (e.g., "chat", "edit")
     * @param variables  Variables to substitute in the template
     * @return The processed prompt text
     */
    public String getPrompt(String promptName, Map<String, String> variables) {
        String template = loadPromptTemplate(promptName);
        return substituteVariables(template, variables);
    }

    private String loadPromptTemplate(String promptName) {
        // One read, so there is no window between "is it there" and "what is it".
        String cacheKey = "prompt:" + promptName;
        String cached   = promptCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String  template     = null;
        boolean isUserPrompt = false;

        // Try to load user override first
        Path userPromptFile = userPromptDir.resolve(promptName + ".json");
        if (Files.exists(userPromptFile)) {
            try {
                String jsonContent = Files.readString(userPromptFile, StandardCharsets.UTF_8);
                template     = parsePromptJson(jsonContent, true);
                isUserPrompt = true;
                logger.info("Loaded user prompt override: " + promptName);
            } catch (IOException e) {
                logger.error("Failed to load user prompt: " + promptName, e);
            }
        }

        // Fall back to default if no user override
        if (template == null) {
            String jsonContent = loadDefaultPrompt(promptName + ".json");
            if (jsonContent != null) {
                template = parsePromptJson(jsonContent, false);
            }
        }

        // putIfAbsent rather than computeIfAbsent: loading reads files, and doing that inside the
        // map's mapping function holds a bin lock across I/O. Two threads racing here both load the
        // same immutable template, which costs one redundant read and nothing else.
        if (template != null) {
            promptCache.putIfAbsent(cacheKey, template);
        } else {
            logger.warn("No prompt template found for: " + promptName);
            template = "No template found for prompt: " + promptName;
        }

        return template;
    }

    private String substituteVariables(String template, Map<String, String> variables) {
        if (template == null || variables == null || variables.isEmpty()) {
            return template;
        }

        StringBuffer result  = new StringBuffer();
        Matcher      matcher = VARIABLE_PATTERN.matcher(template);

        while (matcher.find()) {
            String varName     = matcher.group(1);
            String replacement = variables.getOrDefault(varName, "{{" + varName + "}}");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String parsePromptJson(String jsonContent, boolean isUserPrompt) {
        try {
            ObjectNode promptNode = objectMapper.readValue(jsonContent, ObjectNode.class);

            // Check for content_path first (new format)
            if (promptNode.has("content_path")) {
                String contentPath = promptNode.get("content_path").asText();
                return loadPromptContentFromPath(contentPath, isUserPrompt);
            }
            // Extract the prompt content (legacy format)
            else if (promptNode.has("content")) {
                return promptNode.get("content").asText();
            } else if (promptNode.has("template")) {
                return promptNode.get("template").asText();
            } else {
                logger.warn("Prompt JSON missing 'content', 'content_path', or 'template' field");
                return jsonContent; // Fallback to raw content
            }
        } catch (Exception e) {
            logger.error("Failed to parse prompt JSON", e);
            return jsonContent; // Fallback to raw content
        }
    }

    private String loadPromptContentFromPath(String contentPath, boolean isUserPrompt) {
        if (isUserPrompt) {
            // For user prompts, try to load from user directory first
            Path userContentFile = userPromptDir.resolve(contentPath);
            if (Files.exists(userContentFile)) {
                try {
                    return Files.readString(userContentFile, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    logger.error("Failed to load user prompt content from: " + userContentFile, e);
                }
            }
        }

        // Try to load from resources (default location)
        String resourcePath = DEFAULT_PROMPT_DIR + contentPath;
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                logger.error("Failed to find prompt content file at: " + resourcePath);
                return "Failed to load prompt content from: " + contentPath;
            }
        } catch (IOException e) {
            logger.error("Failed to load prompt content from path: " + contentPath, e);
            return "Error loading prompt content: " + e.getMessage();
        }
    }

    /**
     * Get a prompt template without variable substitution.
     *
     * @param promptName The name of the prompt
     * @return The raw template text
     */
    public String getRawPrompt(String promptName) {
        return loadPromptTemplate(promptName);
    }

    /**
     * Clear the prompt cache to force reloading of templates.
     */
    public void clearCache() {
        promptCache.clear();
        logger.info("Prompt template cache cleared");
    }

    /**
     * List all available prompt templates (both default and user overrides).
     *
     * @return Map of prompt names to their sources ("default" or "user")
     */
    public Map<String, String> listAvailablePrompts() {
        Map<String, String> prompts = new HashMap<>();

        // List default prompts
        for (String prompt : DEFAULT_PROMPT_NAMES) {
            prompts.put(prompt, "default");
        }

        // Check for user overrides
        // Closed: Files.list keeps the directory handle open until the stream is, and this is
        // called whenever prompts are listed.
        try (java.util.stream.Stream<java.nio.file.Path> entries = Files.list(userPromptDir)) {
            entries.filter(path -> path.toString().endsWith(".json"))
                   .forEach(path -> {
                       String filename   = path.getFileName().toString();
                       String promptName = filename.substring(0, filename.length() - 5);
                       prompts.put(promptName, "user");
                   });
        } catch (IOException e) {
            logger.error("Failed to list user prompts", e);
        }

        return prompts;
    }

    /**
     * Save a custom prompt template.
     *
     * @param promptName The name of the prompt
     * @param content    The prompt content
     * @param metadata   Optional metadata about the prompt
     */
    public void saveCustomPrompt(String promptName, String content, Map<String, String> metadata) throws IOException {
        ObjectNode promptNode = objectMapper.createObjectNode();
        promptNode.put("name", promptName);
        promptNode.put("content", content);
        promptNode.put("version", "1.0");

        if (metadata != null) {
            ObjectNode metaNode = objectMapper.createObjectNode();
            metadata.forEach(metaNode::put);
            promptNode.set("metadata", metaNode);
        }

        String json       = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptNode);
        Path   promptFile = userPromptDir.resolve(promptName + ".json");
        Files.writeString(promptFile, json, StandardCharsets.UTF_8);

        // Clear cache for this prompt
        promptCache.remove("prompt:" + promptName);

        logger.info("Saved custom prompt: " + promptName);
    }

    /**
     * Delete a user override for a prompt, restoring the default. Resolves the
     * file from the SAME user prompt directory used by {@link #saveCustomPrompt}
     * and {@link #listAvailablePrompts}, so callers do not have to re-derive the
     * path (which risks diverging from the engine's actual location).
     *
     * @param promptName The name of the prompt whose override should be removed
     * @return true if an override existed and was deleted, false if none existed
     * @throws IOException if the override file exists but cannot be deleted
     */
    public boolean deleteCustomPrompt(String promptName) throws IOException {
        Path promptFile = userPromptDir.resolve(promptName + ".json");
        if (!Files.exists(promptFile)) {
            return false;
        }
        Files.delete(promptFile);
        promptCache.remove("prompt:" + promptName);
        logger.info("Deleted custom prompt: " + promptName);
        return true;
    }

    /**
     * Whether a user override file exists for the given prompt name. Resolved
     * against the engine's own user prompt directory so callers stay consistent
     * with save/list/delete.
     *
     * @param promptName The name of the prompt to check
     * @return true if a user override exists for the prompt
     */
    public boolean hasUserOverride(String promptName) {
        return Files.exists(userPromptDir.resolve(promptName + ".json"));
    }
}