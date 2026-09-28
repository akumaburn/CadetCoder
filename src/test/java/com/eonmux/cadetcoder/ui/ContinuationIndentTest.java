package com.eonmux.cadetcoder.ui;

import org.junit.Test;
import dev.tamboui.text.Line;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Multi-line blocks have to keep the indent that aligns their continuation lines. */
public class ContinuationIndentTest {

    private List<String> render(int width, String... lines) {
        return new MarkdownRenderer(null, Glyphs.UNICODE).render(List.of(lines), width)
                .stream().map(Line::rawContent).collect(Collectors.toList());
    }

    @Test
    public void aMarkedBlocksContinuationStaysAlignedUnderItsFirstLine() {
        // printInfo indents continuation lines to sit under the text after the marker. Losing that
        // turns an aligned key/value block into ragged prose the moment it reaches the shell.
        List<String> out = render(90,
                "ℹ State:           on",
                "  Input window:    8,192 tokens");

        assertThat(out.get(0)).startsWith("ℹ State:");
        assertThat(out.get(1))
                .as("the continuation line must keep its leading indent")
                .startsWith("  Input window:");
    }

    @Test
    public void aDeeperIndentIsAlsoKept() {
        List<String> out = render(90, "ℹ Workers", "    Worker 1  review retries");

        assertThat(out.get(1)).startsWith("    Worker 1");
    }
}
