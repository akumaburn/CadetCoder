package com.eonmux.cadetcoder.commands;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the project holds is re-read after it changes, rather than remembered from before.
 *
 * <p><b>The defect</b>: the walk's answer was cached for the life of the process, misses included
 * and deliberately -- and this tool writes files. An agent that looked for {@code Parser.java}
 * at step two, created it at step five and named it again at step nine was told, from the cache,
 * that the project has no such file: the miss was recorded before the file existed and nothing
 * ever removed it. The mirror image is a file that was moved or deleted, whose old path the cache
 * went on handing out. Both are silent, both last for the rest of the session, and both are worst
 * in exactly the long agentic runs the cache was added to speed up.</p>
 */
public class AfileMadeDuringTheRunIsFoundAfterwardsTest {

    @Rule
    public TemporaryFolder project = new TemporaryFolder();

    private ProjectFileSearch search;
    private Path              root;

    @Before
    public void setUp() {
        ProjectFileSearch.forgetWhatWasFound();
        search = new ProjectFileSearch(new LoggingCommandSupport() { });
        root   = project.getRoot().toPath();
    }

    @Test
    public void afileThatDidNotExistWhenFirstAskedAboutIsFoundOnceItDoes() throws IOException {
        long now = 1_000_000L;

        assertThat(search.locate("Parser.java", root, now)).isNull();

        Files.createDirectories(root.resolve("src/main/java"));
        Path made = Files.writeString(root.resolve("src/main/java/Parser.java"), "class Parser {}");

        assertThat(search.locate("Parser.java", root, now + ProjectFileSearch.MISS_TTL_MILLIS))
                .as("the file is there now; the walk has to be repeated")
                .isEqualTo(made.toString());
    }

    @Test
    public void amissIsStillRememberedForTheBurstOfQuestionsThatFollowsIt() throws IOException {
        long now = 1_000_000L;

        assertThat(search.locate("Parser.java", root, now)).isNull();

        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Parser.java"), "class Parser {}");

        assertThat(search.locate("Parser.java", root, now + ProjectFileSearch.MISS_TTL_MILLIS - 1))
                .as("within the window the remembered answer still stands; that is what it is for")
                .isNull();
    }

    @Test
    public void afileThatHasSinceBeenDeletedIsNotStillOfferedAtItsOldPath() throws IOException {
        long now = 2_000_000L;
        Files.createDirectories(root.resolve("src"));
        Path made = Files.writeString(root.resolve("src/Gone.java"), "class Gone {}");

        assertThat(search.locate("Gone.java", root, now)).isEqualTo(made.toString());

        Files.delete(made);

        assertThat(search.locate("Gone.java", root, now + 1))
                .as("a path that no longer exists is not an answer, however recently it was true")
                .isNull();
    }

    @Test
    public void afileThatIsStillThereIsAnsweredWithoutWalkingAgain() throws IOException {
        long now = 3_000_000L;
        Files.createDirectories(root.resolve("src"));
        Path made = Files.writeString(root.resolve("src/Stable.java"), "class Stable {}");

        assertThat(search.locate("Stable.java", root, now)).isEqualTo(made.toString());
        assertThat(search.locate("Stable.java", root, now + 60 * ProjectFileSearch.MISS_TTL_MILLIS))
                .isEqualTo(made.toString());
    }

    /** Two projects are two answers; one must not be handed the other's. */
    @Test
    public void whatOneDirectoryHoldsIsNotAnAnswerAboutAnother() throws IOException {
        long now = 4_000_000L;
        Path other = project.newFolder("elsewhere").toPath();
        Files.writeString(root.resolve("Only.java"), "class Only {}");

        assertThat(search.locate("Only.java", root, now)).isNotNull();
        assertThat(search.locate("Only.java", other, now)).isNull();
    }
}
