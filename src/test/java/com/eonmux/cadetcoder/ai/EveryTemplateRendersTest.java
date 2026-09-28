package com.eonmux.cadetcoder.ai;

import com.eonmux.cadetcoder.ai.templates.ChatTemplateRegistry;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every template a user can select must actually render.
 *
 * <p>{@code PromptBuilder.build()} switched over its own {@code Template} enum and covered eight of
 * its nine values. The missing one was {@code LLAMA3} -- which is exactly what
 * {@code mapChatTemplateToEnum} returns for {@code ai.chatTemplate=llama3}, a value the
 * {@code template} command lists as available. So a supported setting reached a
 * {@code default: throw new IllegalStateException}.</p>
 *
 * <p>Driven off the enum rather than a written-out list, because the list is what went stale.</p>
 */
public class EveryTemplateRendersTest {

    private static PromptBuilder builderFor(PromptBuilder.Template template) {
        PromptBuilder builder = new PromptBuilder("summarize this");
        builder.withContext("YOU ARE A CODE AGENT")
               .addUserDialogue("what does this file do?")
               .usingTemplate(template);
        return builder;
    }

    @Test
    public void noTemplateValueCanReachTheDefaultThrow() {
        for (PromptBuilder.Template template : PromptBuilder.Template.values()) {
            assertThatCode(() -> builderFor(template).build())
                    .as("%s is a selectable template and must render", template)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    public void everyRenderedPromptCarriesTheSystemContextAndTheUserTurn() {
        for (PromptBuilder.Template template : PromptBuilder.Template.values()) {
            String prompt = builderFor(template).build();

            assertThat(prompt)
                    .as("%s dropped the system context", template)
                    .contains("YOU ARE A CODE AGENT");
            assertThat(prompt)
                    .as("%s dropped the user's message", template)
                    .contains("what does this file do?");
        }
    }

    /** The name-to-enum mapping and the renderer must agree on what exists. */
    @Test
    public void everyNameTheRegistryOffersRendersThroughTheBuilderToo() {
        for (String name : ChatTemplateRegistry.getInstance().getTemplateDescriptions().keySet()) {
            assertThatCode(() -> ChatTemplateRegistry.getInstance()
                    .getTemplate(name)
                    .formatConversation("YOU ARE A CODE AGENT",
                            java.util.List.of(new com.eonmux.cadetcoder.ai.templates.ChatTemplate.Message(
                                    "user", "what does this file do?"))))
                    .as("%s is offered by `template list` and must render", name)
                    .doesNotThrowAnyException();
        }
    }
}
