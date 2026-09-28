package com.eonmux.cadetcoder.commands;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path repair touches the argument that is a path, and nothing else.
 *
 * <p><b>The defect</b>: {@code substituteFoundPaths} walked EVERY argument of every action and
 * replaced any that looked like a stand-in path. {@code grep}'s first argument is a search PATTERN
 * and {@code bash}'s is a whole command line, so a pattern containing the word "placeholder", or a
 * shell command mentioning {@code src/main/java/com/example/}, was silently rewritten into a file
 * path -- the model was told "Substituting ... with ..." and a different command ran. For
 * {@code bash} the rewrite happened BEFORE the shell policy screened it, so what was screened and
 * executed was not what the model asked for; the screen's own comment says a command must not be
 * rewritten for exactly that reason.</p>
 *
 * <p>{@link PathArgument} already answers "which argument of this command is a path", and is the
 * table the path checks in {@code ActionRun} consult. This is the same question, so it is the same
 * answer.</p>
 */
public class OnlyApathArgumentIsRepairedAsApathTest {

    private final ChatCommand chat  = new ChatCommand();
    private final ActionPaths paths = new ActionPaths(chat);

    private ChatContext conversationThatFound(String name, String where) {
        ChatContext context = new ChatContext();
        context.getFoundFiles().put(name, where);
        return context;
    }

    private static ChatCommand.AIAction action(String command, String... arguments) {
        return new ChatCommand.AIAction(command, arguments, "because the task needs it");
    }

    @Test
    public void ashellCommandLineIsNotTreatedAsApath() {
        ChatCommand.AIAction shell =
                action("bash", "ls src/main/java/com/example/");

        paths.substituteFoundPaths(shell, conversationThatFound("Example.java",
                                                                "src/main/java/app/Example.java"));

        assertThat(shell.arguments).containsExactly("ls src/main/java/com/example/");
    }

    @Test
    public void asearchPatternIsNotTreatedAsApath() {
        ChatCommand.AIAction search = action("grep", "placeholder", "--path=src");

        paths.substituteFoundPaths(search, conversationThatFound("Example.java",
                                                                "src/main/java/app/Example.java"));

        assertThat(search.arguments).containsExactly("placeholder", "--path=src");
    }

    /** {@code glob}'s argument is a pattern too, and the path it searches is an option. */
    @Test
    public void aglobPatternIsNotTreatedAsApath() {
        ChatCommand.AIAction glob = action("glob", "**/Example.java");

        paths.substituteFoundPaths(glob, conversationThatFound("Example.java",
                                                               "src/main/java/app/Example.java"));

        assertThat(glob.arguments).containsExactly("**/Example.java");
    }

    /** The repair the feature exists for still happens. */
    @Test
    public void abareFilenameInApathArgumentIsStillRepaired() {
        ChatCommand.AIAction read = action("read", "Example.java");

        paths.substituteFoundPaths(read, conversationThatFound("Example.java",
                                                               "src/main/java/app/Example.java"));

        assertThat(read.arguments).containsExactly("src/main/java/app/Example.java");
    }

    /** {@code suggest} takes its path second, so the first argument is left alone. */
    @Test
    public void thesuggestionTypeIsNotTreatedAsApath() {
        ChatCommand.AIAction suggest = action("suggest", "tests", "Example.java");

        paths.substituteFoundPaths(suggest, conversationThatFound("Example.java",
                                                                  "src/main/java/app/Example.java"));

        assertThat(suggest.arguments)
                .containsExactly("tests", "src/main/java/app/Example.java");
    }

    /** {@code multiread} takes a list of paths, and every one of them is repairable. */
    @Test
    public void everyPathOfAmultireadIsRepaired() {
        ChatCommand.AIAction multiread =
                action("multiread", "Example.java", "Other.java", "--limit=40");

        ChatContext context = conversationThatFound("Example.java", "src/main/java/app/Example.java");
        context.getFoundFiles().put("Other.java", "src/main/java/app/Other.java");
        paths.substituteFoundPaths(multiread, context);

        assertThat(multiread.arguments)
                .containsExactly("src/main/java/app/Example.java",
                                 "src/main/java/app/Other.java",
                                 "--limit=40");
    }
}
