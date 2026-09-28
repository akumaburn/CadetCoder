package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code --flag=value} expansion that makes the command catalog's promise true for the
 * hand-rolled argument parsers, without corrupting arguments that merely contain {@code =}.
 */
public class CommandOptionsTest {

    private static final Set<String> READ_FLAGS = Set.of("-l", "--limit", "-o", "--offset");

    @Test
    public void expandsTheDocumentedInlineForm() {
        assertThat(CommandOptions.expandInlineValues(
                new String[] {"Foo.java", "--limit=50"}, READ_FLAGS))
                .containsExactly("Foo.java", "--limit", "50");
    }

    @Test
    public void expandsShortFlagsToo() {
        assertThat(CommandOptions.expandInlineValues(new String[] {"Foo.java", "-l=50"}, READ_FLAGS))
                .containsExactly("Foo.java", "-l", "50");
    }

    @Test
    public void leavesTheSpaceSeparatedFormAlone() {
        assertThat(CommandOptions.expandInlineValues(
                new String[] {"Foo.java", "--limit", "50"}, READ_FLAGS))
                .containsExactly("Foo.java", "--limit", "50");
    }

    @Test
    public void doesNotTouchFlagsItWasNotToldAbout() {
        assertThat(CommandOptions.expandInlineValues(
                new String[] {"Foo.java", "--other=keepme"}, READ_FLAGS))
                .containsExactly("Foo.java", "--other=keepme");
    }

    @Test
    public void doesNotTouchPositionalArgumentsContainingEquals() {
        // This is why expansion is opt-in per command: a shell command, a grep pattern or file
        // content routinely contains '='.
        assertThat(CommandOptions.expandInlineValues(
                new String[] {"FOO=bar make", "--limit=5"}, READ_FLAGS))
                .containsExactly("FOO=bar make", "--limit", "5");
    }

    @Test
    public void stopsExpandingAfterATerminator() {
        assertThat(CommandOptions.expandInlineValues(
                new String[] {"--limit=5", "--", "--limit=7"}, READ_FLAGS))
                .containsExactly("--limit", "5", "--", "--limit=7");
    }

    @Test
    public void handlesAnEmptyValue() {
        assertThat(CommandOptions.expandInlineValues(new String[] {"--limit="}, READ_FLAGS))
                .containsExactly("--limit", "");
    }

    @Test
    public void handlesNullAndEmptyInput() {
        assertThat(CommandOptions.expandInlineValues(null, READ_FLAGS)).isEmpty();
        assertThat(CommandOptions.expandInlineValues(new String[0], READ_FLAGS)).isEmpty();
        assertThat(CommandOptions.expandInlineValues(new String[] {"a"}, null)).containsExactly("a");
        assertThat(CommandOptions.expandInlineValues(new String[] {"a"}, Set.of())).containsExactly("a");
    }

    @Test
    public void keepsTheOriginalArrayUnmodified() {
        String[] original = {"Foo.java", "--limit=50"};

        CommandOptions.expandInlineValues(original, READ_FLAGS);

        assertThat(original).containsExactly("Foo.java", "--limit=50");
    }
}
