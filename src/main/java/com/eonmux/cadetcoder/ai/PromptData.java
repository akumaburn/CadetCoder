package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.templates.ChatTemplate;
import com.eonmux.cadetcoder.ai.templates.ChatTemplateRegistry;
import com.eonmux.cadetcoder.config.ConfigManager;

import java.util.*;

public class PromptData {
    private final String                 systemPrompt;
    private final String                 userPrompt;
    private final PromptBuilder.Template template; // Legacy template
    private final String                 chatTemplateName; // New chat template name

    /**
     * The images this request carries, in the order they were attached.
     *
     * <p>Kept beside the prompt rather than in it, because an image is not text on any wire that
     * takes one: each provider has its own place to put it, and only the backend building the body
     * knows which. What the text says about the image -- the file it came from, and what to do with
     * it -- is already in {@link #userPrompt}.</p>
     */
    private final List<PromptImage> images;

    /**
     * Legacy constructor for backward compatibility
     */
    public PromptData(String systemPrompt, String userPrompt, PromptBuilder.Template template) {
        this(systemPrompt, userPrompt, template, null);
    }

    /**
     * Full constructor
     */
    public PromptData(String systemPrompt,
                      String userPrompt,
                      PromptBuilder.Template template,
                      String chatTemplateName) {
        this(systemPrompt, userPrompt, template, chatTemplateName, List.of());
    }

    /**
     * Full constructor, images included.
     *
     * @param images what this request carries besides its text; copied, and never null in the field
     */
    public PromptData(String systemPrompt,
                      String userPrompt,
                      PromptBuilder.Template template,
                      String chatTemplateName,
                      List<PromptImage> images) {
        this.systemPrompt     = systemPrompt;
        this.userPrompt       = userPrompt;
        this.template         = template;
        this.chatTemplateName = chatTemplateName;
        this.images           = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * New constructor using chat template name
     */
    public PromptData(String systemPrompt, String userPrompt, String chatTemplateName) {
        this(systemPrompt, userPrompt, null, chatTemplateName);
    }

    /**
     * Constructor that uses the configured default chat template
     */
    public PromptData(String systemPrompt, String userPrompt) {
        this(systemPrompt, userPrompt, null, null);
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Returns a copy carrying a different system prompt, leaving this instance untouched.
     *
     * @param replacement the system prompt for the copy
     * @return a new {@code PromptData} with the same user prompt and template
     */
    public PromptData withSystemPrompt(String replacement) {
        return new PromptData(replacement, userPrompt, template, chatTemplateName, images);
    }

    /**
     * The images this request carries.
     *
     * @return the images, in the order they were attached; empty when it is text alone
     */
    public List<PromptImage> getImages() {
        return images;
    }

    /** @return whether this request carries anything a text-only backend cannot send */
    public boolean hasImages() {
        return !images.isEmpty();
    }

    /**
     * Returns a copy carrying these images, leaving this instance untouched.
     *
     * @param replacement the images for the copy; {@code null} or empty makes it text alone
     * @return a new {@code PromptData} with the same prompts and template
     */
    public PromptData withImages(List<PromptImage> replacement) {
        return new PromptData(systemPrompt, userPrompt, template, chatTemplateName, replacement);
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public PromptBuilder.Template getTemplate() { // Legacy getter
        return template;
    }

    public String getChatTemplateName() {
        return chatTemplateName;
    }

    /**
     * Get the formatted prompt using the configured chat template.
     * This method is specifically for backends that need a single formatted string.
     *
     * @return The fully formatted prompt ready for the model
     */
    public String getFormattedPrompt() {
        return getPrompt();
    }

    /**
     * Check if the prompt should use formatted template or structured messages.
     *
     * @return true if a chat template is configured and should be used
     */
    public boolean useFormattedTemplate() {
        String templateName = getEffectiveChatTemplateName();
        if (templateName == null) {
            return false;
        }

        // Some templates work better with formatted output
        // rather than structured messages
        return !"plain".equals(templateName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(systemPrompt, userPrompt, template, chatTemplateName, images);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PromptData that)) {
            return false;
        }
        return systemPrompt.equals(that.systemPrompt) &&
               userPrompt.equals(that.userPrompt) &&
               template == that.template &&
               Objects.equals(chatTemplateName, that.chatTemplateName) &&
               images.equals(that.images);
    }

    @Override
    public String toString() {
        return getPrompt();
    }

    public String getPrompt() {
        // Use new chat template system if available
        String templateName = getEffectiveChatTemplateName();
        if (templateName != null) {
            ChatTemplate               chatTemplate = ChatTemplateRegistry.getInstance().getTemplate(templateName);
            List<ChatTemplate.Message> messages     = new ArrayList<>();
            messages.add(new ChatTemplate.Message("user", userPrompt));
            return chatTemplate.formatConversation(systemPrompt, messages);
        }

        // Fall back to legacy PromptBuilder if no chat template specified
        if (template != null) {
            PromptBuilder tempBuilder = new PromptBuilder("temp");
            tempBuilder.withContext(systemPrompt)
                       .addUserDialogue(userPrompt)
                       .usingTemplate(template);
            return tempBuilder.build();
        }

        // Default: just concatenate prompts
        return systemPrompt + "\n\n" + userPrompt;
    }

    /**
     * Get the effective chat template name, considering configuration.
     */
    private String getEffectiveChatTemplateName() {
        if (chatTemplateName != null) {
            return chatTemplateName;
        }

        // Check configuration for default template
        try {
            return ConfigManager.getInstance().getConfig().getAi().getChatTemplate();
        } catch (Exception e) {
            return null;
        }
    }
}
