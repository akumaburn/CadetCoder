package com.eonmux.cadetcoder.ai;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link ResponseSanitizer} removes chat-template control tokens a model can echo into
 * its output while leaving ordinary content intact. The central guarantee: the parsing engine and the
 * chat/agent harness must never see (or execute) a token-wrapped blob like
 * {@code <|im_start|>assistant ... <|im_end|>}.
 */
public class ResponseSanitizerTest {

    @Test
    public void stripsChatMlStartEndTokens() {
        String raw = "<|im_start|>assistant\nHello there<|im_end|>";
        assertThat(ResponseSanitizer.sanitize(raw)).isEqualTo("Hello there");
    }

    @Test
    public void stripsTheExactLeakedActionBlob_leavingParseableJson() {
        // The real response from the failing run: a JSON action wrapped in ChatML tokens with a
        // dangling "assistant" role label. After sanitizing, only the JSON action remains.
        String raw = "<|im_start|>assistant\n"
                + "ACTION\n"
                + "{\n"
                + "  \"action\": \"read\",\n"
                + "  \"files\": [\"README.md\", \"COMMAND_REFERENCE.md\"]\n"
                + "}\n"
                + "<|im_end|>";

        String cleaned = ResponseSanitizer.sanitize(raw);

        assertThat(cleaned).doesNotContain("<|im_start|>", "<|im_end|>");
        // The leading "assistant" role label is removed, the JSON payload is preserved verbatim.
        assertThat(cleaned).startsWith("ACTION");
        assertThat(cleaned).contains("\"action\": \"read\"");
        assertThat(cleaned).contains("\"files\": [\"README.md\", \"COMMAND_REFERENCE.md\"]");
    }

    @Test
    public void stripsGenericPipeTokens() {
        assertThat(ResponseSanitizer.sanitize("done<|eot_id|>")).isEqualTo("done");
        assertThat(ResponseSanitizer.sanitize("<|start_header_id|>assistant<|end_header_id|>\nhi"))
                .isEqualTo("hi");
        assertThat(ResponseSanitizer.sanitize("text<|endoftext|>")).isEqualTo("text");
    }

    @Test
    public void stripsLlamaAndMistralMarkers() {
        assertThat(ResponseSanitizer.sanitize("[INST] hello [/INST] world")).isEqualTo("hello  world");
        assertThat(ResponseSanitizer.sanitize("<<SYS>>be nice<</SYS>>")).isEqualTo("be nice");
        assertThat(ResponseSanitizer.sanitize("<s>opening")).isEqualTo("opening");
        assertThat(ResponseSanitizer.sanitize("closing</s>")).isEqualTo("closing");
    }

    @Test
    public void removesOnlyLeadingRoleLabel_notInteriorWords() {
        assertThat(ResponseSanitizer.sanitize("assistant: the answer is 42")).isEqualTo("the answer is 42");
        assertThat(ResponseSanitizer.sanitize("assistant\nthe answer is 42")).isEqualTo("the answer is 42");
        // "user" appearing mid-sentence must be preserved.
        assertThat(ResponseSanitizer.sanitize("The user asked a question")).isEqualTo("The user asked a question");
    }

    @Test
    public void doesNotStripLeadingRoleWordWhenItIsRealProse() {
        // A genuine answer that simply begins with one of the role words (followed by more words on the
        // same line, no colon) must be left fully intact — the role-label strip only fires on a delimiter.
        assertThat(ResponseSanitizer.sanitize("User authentication is handled by the AuthService."))
                .isEqualTo("User authentication is handled by the AuthService.");
        assertThat(ResponseSanitizer.sanitize("System startup completed in 2s."))
                .isEqualTo("System startup completed in 2s.");
    }

    @Test
    public void preservesOrdinaryContentIncludingCodeAndJson() {
        String json = "{\"action\": \"read\", \"parameters\": {\"file_path\": \"a.txt\"}}";
        assertThat(ResponseSanitizer.sanitize(json)).isEqualTo(json);

        String prose = "Here is the explanation of how the parser works.";
        assertThat(ResponseSanitizer.sanitize(prose)).isEqualTo(prose);

        // An interior <s>...</s> (e.g. discussing HTML) is left alone — only anchored BOS/EOS go.
        String html = "Use <s>strike</s> inside a sentence.";
        assertThat(ResponseSanitizer.sanitize(html)).isEqualTo(html);
    }

    @Test
    public void nullIsPassedThrough_tokenOnlyCollapsesToEmpty() {
        assertThat(ResponseSanitizer.sanitize(null)).isNull();
        assertThat(ResponseSanitizer.sanitize("<|im_start|><|im_end|>")).isEmpty();
        assertThat(ResponseSanitizer.sanitize("   ")).isEmpty();
    }
}
