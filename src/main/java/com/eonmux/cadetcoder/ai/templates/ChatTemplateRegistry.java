package com.eonmux.cadetcoder.ai.templates;

import com.eonmux.cadetcoder.logging.CadetLogger;

import java.util.*;

/**
 * Registry for chat templates.
 * Manages available templates and provides access by name.
 */
public class ChatTemplateRegistry {
    private static final CadetLogger          logger = CadetLogger.getLogger(ChatTemplateRegistry.class);
    private static       ChatTemplateRegistry instance;

    private final Map<String, ChatTemplate> templates = new HashMap<>();
    private       ChatTemplate              defaultTemplate;

    private ChatTemplateRegistry() {
        initializeTemplates();
    }

    private void initializeTemplates() {
        // Register all built-in templates
        registerTemplate(new PlainTemplate());
        registerTemplate(new ChatMLTemplate());
        registerTemplate(new AlpacaTemplate());
        registerTemplate(new LlamaTemplate());
        registerTemplate(new Llama3Template());
        registerTemplate(new OpenChatTemplate());
        registerTemplate(new DeepseekTemplate());
        registerTemplate(new TekkenTemplate());
        registerTemplate(new IntelNeuralChatTemplate());

        // Register additional variant templates
        registerTemplate(new AlpacaSystemTemplate());
        registerTemplate(new AdvancedChatMLTemplate());

        // Set default template
        defaultTemplate = templates.get("chatml");

        logger.info("Initialized chat template registry with " + templates.size() + " templates");
    }

    /**
     * Register a new template.
     *
     * @param template The template to register
     */
    public void registerTemplate(ChatTemplate template) {
        templates.put(template.getName().toLowerCase(), template);
        logger.debug("Registered chat template: " + template.getName());
    }

    public static synchronized ChatTemplateRegistry getInstance() {
        if (instance == null) {
            instance = new ChatTemplateRegistry();
        }
        return instance;
    }

    /**
     * Get the default template.
     *
     * @return The default template
     */
    public ChatTemplate getDefaultTemplate() {
        return defaultTemplate;
    }

    /**
     * Set the default template.
     *
     * @param name The name of the template to use as default
     */
    public void setDefaultTemplate(String name) {
        ChatTemplate template = getTemplate(name);
        if (template != null) {
            defaultTemplate = template;
            logger.info("Set default template to: " + name);
        }
    }

    /**
     * Get a template by name.
     *
     * @param name The template name (case-insensitive)
     * @return The template, or the default if not found
     */
    public ChatTemplate getTemplate(String name) {
        if (name == null || name.isEmpty()) {
            return defaultTemplate;
        }

        ChatTemplate template = templates.get(name.toLowerCase());
        if (template == null) {
            logger.warn("Template not found: " + name + ", using default");
            return defaultTemplate;
        }
        return template;
    }

    /**
     * Get all templates with their descriptions.
     *
     * @return Map of template names to descriptions
     */
    public Map<String, String> getTemplateDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        List<String>        names        = listTemplateNames();

        for (String name : names) {
            ChatTemplate template = templates.get(name);
            descriptions.put(name, template.getDescription());
        }

        return descriptions;
    }

    /**
     * List all available template names.
     *
     * @return Sorted list of template names
     */
    public List<String> listTemplateNames() {
        List<String> names = new ArrayList<>(templates.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * Check if a template exists.
     *
     * @param name The template name
     * @return true if the template exists
     */
    public boolean hasTemplate(String name) {
        return name != null && templates.containsKey(name.toLowerCase());
    }

    /**
     * Alpaca System variant that places the system prompt differently.
     */
    private static class AlpacaSystemTemplate extends AbstractChatTemplate {
        public AlpacaSystemTemplate() {
            super("alpaca-system", "Alpaca format with system prompt in content");
        }

        @Override
        public String formatConversation(String systemPrompt, List<Message> messages) {
            StringBuilder prompt = buildConversation();

            // Just prepend system prompt
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                appendLine(prompt, systemPrompt);
                appendLine(prompt, "");
            }

            // Then format as regular conversation
            boolean first = true;
            for (Message message : messages) {
                if ("user".equals(message.getRole())) {
                    if (first) {
                        appendLine(prompt, "### Input:");
                        first = false;
                    }
                    appendLine(prompt, message.getContent());
                } else if ("assistant".equals(message.getRole())) {
                    appendLine(prompt, "");
                    appendLine(prompt, "### Response:");
                    appendLine(prompt, message.getContent());
                }
            }

            if (!hasAssistantMessage(messages)) {
                appendLine(prompt, "");
                appendLine(prompt, "### Response:");
            }

            return prompt.toString();
        }

        private boolean hasAssistantMessage(List<Message> messages) {
            return messages.stream().anyMatch(m -> "assistant".equals(m.getRole()));
        }

        @Override
        protected void formatMessage(StringBuilder dialogue, Message message) {
            // Handled in formatConversation
        }
    }
}