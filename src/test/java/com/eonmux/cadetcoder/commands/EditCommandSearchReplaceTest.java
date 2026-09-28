package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the *SEARCH/REPLACE* parsing/apply logic that backs {@link EditCommand}.
 *
 * <p>These pin the fix for the critical corruption bug: the edit system prompt instructs the
 * model to return SEARCH/REPLACE blocks, but the apply logic used to write the whole fenced
 * block (markers and all) over the file, discarding the original content. The logic below
 * applies the blocks against the existing file so only the targeted lines change, and refuses
 * to write (returns null) when a SEARCH cannot be located.</p>
 */
public class EditCommandSearchReplaceTest {

    @Test
    public void detectsMarkers() {
        assertThat(SearchReplaceBlocks.present(
                "<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE")).isTrue();
        assertThat(SearchReplaceBlocks.present("public class Foo {}")).isFalse();
        assertThat(SearchReplaceBlocks.present(null)).isFalse();
    }

    @Test
    public void parsesSingleBlock() {
        List<String[]> blocks = SearchReplaceBlocks.parse(
                "<<<<<<< SEARCH\n    int x = 1;\n=======\n    int x = 2;\n>>>>>>> REPLACE");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)[0]).isEqualTo("    int x = 1;");
        assertThat(blocks.get(0)[1]).isEqualTo("    int x = 2;");
    }

    @Test
    public void parsesMultipleBlocks() {
        String body = "<<<<<<< SEARCH\na\n=======\nA\n>>>>>>> REPLACE\n"
                + "<<<<<<< SEARCH\nb\n=======\nB\n>>>>>>> REPLACE";
        List<String[]> blocks = SearchReplaceBlocks.parse(body);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)[0]).isEqualTo("a");
        assertThat(blocks.get(0)[1]).isEqualTo("A");
        assertThat(blocks.get(1)[0]).isEqualTo("b");
        assertThat(blocks.get(1)[1]).isEqualTo("B");
    }

    @Test
    public void malformedOrMarkerlessBodyReturnsNull() {
        // SEARCH started but never terminated.
        assertThat(SearchReplaceBlocks.parse("<<<<<<< SEARCH\norphan line")).isNull();
        // Divider present but no REPLACE terminator.
        assertThat(SearchReplaceBlocks.parse("<<<<<<< SEARCH\na\n=======\nb")).isNull();
        // No markers at all.
        assertThat(SearchReplaceBlocks.parse("just some code")).isNull();
    }

    @Test
    public void appliesTargetedReplacementPreservingSurroundingContent() {
        String         existing = "public class Foo {\n    int x = 1;\n}\n";
        List<String[]> blocks   = SearchReplaceBlocks.parse(
                "<<<<<<< SEARCH\n    int x = 1;\n=======\n    int x = 2;\n>>>>>>> REPLACE");
        StringBuilder  err      = new StringBuilder();

        String result = SearchReplaceBlocks.applyTo(existing, blocks, err);

        assertThat(result).isEqualTo("public class Foo {\n    int x = 2;\n}\n");
        assertThat(err.length()).isZero();
    }

    @Test
    public void appliesMultipleBlocksSequentially() {
        String         existing = "a\nb\nc\n";
        List<String[]> blocks   = SearchReplaceBlocks.parse(
                "<<<<<<< SEARCH\na\n=======\nX\n>>>>>>> REPLACE\n"
                        + "<<<<<<< SEARCH\nc\n=======\nZ\n>>>>>>> REPLACE");
        StringBuilder  err      = new StringBuilder();

        String result = SearchReplaceBlocks.applyTo(existing, blocks, err);

        assertThat(result).isEqualTo("X\nb\nZ\n");
    }

    @Test
    public void emptySearchReplacesWholeFile() {
        List<String[]> blocks = SearchReplaceBlocks.parse(
                "<<<<<<< SEARCH\n=======\nbrand new content\n>>>>>>> REPLACE");
        StringBuilder  err    = new StringBuilder();

        String result = SearchReplaceBlocks.applyTo("old stuff", blocks, err);

        assertThat(result).isEqualTo("brand new content");
    }

    @Test
    public void shortEqualsUnderlineInSearchIsNotMistakenForDivider() {
        // The SEARCH content legitimately contains a Markdown setext underline (=====, 5 chars);
        // only the canonical 7-equals line is the divider, so the block must parse correctly.
        String body = "<<<<<<< SEARCH\n"
                + "Title\n"
                + "=====\n"
                + "old body\n"
                + "=======\n"
                + "Title\n"
                + "=====\n"
                + "new body\n"
                + ">>>>>>> REPLACE";

        List<String[]> blocks = SearchReplaceBlocks.parse(body);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)[0]).isEqualTo("Title\n=====\nold body");
        assertThat(blocks.get(0)[1]).isEqualTo("Title\n=====\nnew body");
    }

    @Test
    public void crlfBodyLinesAreNormalisedSoTheyMatchAnLfFile() {
        // A CRLF response body must still match LF content on disk.
        String body = "<<<<<<< SEARCH\r\n    int x = 1;\r\n=======\r\n    int x = 2;\r\n>>>>>>> REPLACE";

        List<String[]> blocks = SearchReplaceBlocks.parse(body);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)[0]).isEqualTo("    int x = 1;");
        assertThat(blocks.get(0)[1]).isEqualTo("    int x = 2;");
    }

    @Test
    public void searchNotFoundReturnsNullAndLeavesContentUnwritten() {
        List<String[]> blocks = SearchReplaceBlocks.parse(
                "<<<<<<< SEARCH\nnonexistent\n=======\nx\n>>>>>>> REPLACE");
        StringBuilder  err    = new StringBuilder();

        String result = SearchReplaceBlocks.applyTo("totally different", blocks, err);

        assertThat(result).isNull();
        assertThat(err.toString()).contains("did not match");
    }
}
