package com.eonmux.cadetcoder.util;

import com.eonmux.cadetcoder.test.TestOutputCapture;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far into a project a search for a misspelt filename reaches.
 *
 * <p><b>The defect</b>: the walk kept its own depth counter, incremented in
 * {@code preVisitDirectory} and decremented in {@code postVisitDirectory}. A subtree that is skipped
 * is never handed to {@code postVisitDirectory} -- that is what skipping it means -- so both of the
 * reasons the visitor had for skipping one, being too deep and being a build or hidden directory,
 * left the count one higher than it should be, for good. The count only ever climbed, and once it
 * had climbed past the limit every directory after it was skipped. A project with a handful of deep
 * branches in it would answer "File not found" about files sitting in plain sight, and which files
 * those were depended on the order the filesystem happened to hand the directories back in.</p>
 *
 * <p><b>What is locked here</b>: that how deep a search goes is the same for the last branch it
 * looks at as for the first, and what that depth is.</p>
 */
public class AsearchForAfileGoesAsDeepAsItSaysItDoesTest {

    /** How many branches are deep enough to be cut off, so the count has somewhere to leak from. */
    private static final int BRANCHES = 3;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path              project;
    private TestOutputCapture output;

    @Before
    public void setUp() {
        project = folder.getRoot().toPath();
        output  = new TestOutputCapture();
    }

    @After
    public void tearDown() {
        output.restore();
    }

    /**
     * A chain of {@code levels} directories under {@code root}.
     *
     * @return the deepest directory made
     */
    private static Path chain(Path root, int levels) throws IOException {
        Path at = root;
        for (int level = 1; level <= levels; level++) {
            at = at.resolve("d" + level);
        }
        Files.createDirectories(at);
        return at;
    }

    /**
     * Every branch is searched as deeply as the first one was.
     *
     * <p>Each branch here is deeper than the search is allowed to go, so each one gives the walk a
     * reason to stop part way down it. With the count leaking, the first branch the filesystem
     * offers is searched properly and every branch after it is searched one level less deeply than
     * the one before, until nothing is searched at all.</p>
     */
    @Test
    public void afileAtTheSameDepthIsFoundInTheLastBranchAsInTheFirst() throws Exception {
        for (int branch = 0; branch < BRANCHES; branch++) {
            Path deep = chain(project.resolve("branch" + branch), 12);
            Files.createFile(deep.getParent().getParent().getParent().getParent()
                                 .resolve("wanted" + branch + ".txt"));
        }

        for (int branch = 0; branch < BRANCHES; branch++) {
            FilePathResolver.ResolvedPath found =
                    FilePathResolver.resolve("wanted" + branch + ".txt", project, false);

            assertThat(found.exists())
                    .as("wanted" + branch + ".txt is as deep as every other one")
                    .isTrue();
            assertThat(found.getPath()).endsWith(Path.of("wanted" + branch + ".txt"));
        }
    }

    /** The depth a search goes to, stated once so that changing it is a deliberate act. */
    @Test
    public void asearchGoesTenDirectoriesDownAndNoFurther() throws Exception {
        Files.createFile(chain(project, 9).resolve("just-deep-enough.txt"));
        Files.createFile(chain(project.resolve("other"), 10).resolve("one-too-far.txt"));

        assertThat(FilePathResolver.resolve("just-deep-enough.txt", project, false).exists())
                .isTrue();
        assertThat(FilePathResolver.resolve("one-too-far.txt", project, false).exists())
                .isFalse();
    }
}
