package com.eonmux.cadetcoder.util;

import com.eonmux.cadetcoder.config.Configuration;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which directories a project walk skips is one rule.
 *
 * <p>{@link ProjectTreeWalk} was extracted for the walks that had all pruned their own start
 * directory, and four more copies were left behind because they were spelled differently.
 * {@code edit} built the source tree it sends to the model from a list of five names,
 * {@code write} from four, {@code suggest} and {@code refactor} from one ({@code target}) --
 * and the index from another four. So {@code dist/bundle.js}, {@code out/} and {@code bin/}
 * were indexed and offered to the provider as the user's own code, while {@code grep},
 * {@code ls} and {@code read} skipped the very same directories.</p>
 *
 * <p>{@code edit}'s copy carried the original defect as well: it asked {@code Files.isHidden}
 * before exempting the start directory, so a project in a directory whose name begins with a dot
 * produced an empty source tree.</p>
 */
public class OneProjectPruneRuleTest {

    /** Every name the four scattered copies knew between them. */
    private static final Set<String> NAMES_THE_COPIES_KNEW =
            Set.of("target", "build", "node_modules", "dist", ".git");

    @Test
    public void theSharedRuleCoversEveryNameTheCopiesKnew() {
        for (String name : NAMES_THE_COPIES_KNEW) {
            assertThat(ProjectTreeWalk.isPrunedName(name))
                    .as("%s was skipped before the copies were merged and must still be", name)
                    .isTrue();
        }
    }

    @Test
    public void theIndexSkipsWhatTheWalkSkips() {
        List<String> configured =
                List.of(new Configuration().getIndexing().getExcludePatterns());

        assertThat(configured)
                .as("a directory grep refuses to search must not be indexed and fed to the model")
                .containsAll(ProjectTreeWalk.buildOutputDirectories());
    }

    /** A source tree whose root is hidden is still a source tree. */
    @Test
    public void aProjectInsideAHiddenDirectoryIsStillWalked() {
        Path hiddenRoot = Paths.get("/home/someone/.workspaces/project");

        assertThat(ProjectTreeWalk.isPruned(hiddenRoot, hiddenRoot))
                .as("pruning the start directory visits nothing and reports it as an empty project")
                .isFalse();
        assertThat(ProjectTreeWalk.isPruned(hiddenRoot, hiddenRoot.resolve("src"))).isFalse();
        assertThat(ProjectTreeWalk.isPruned(hiddenRoot, hiddenRoot.resolve("dist"))).isTrue();
    }

    /**
     * No source outside {@link ProjectTreeWalk} may decide a prune from a written-out name.
     *
     * <p>The four copies were not found by reading {@code ProjectTreeWalk}; they were found by
     * looking for the names. This looks for them so the next copy is caught when it is written
     * rather than after it has quietly disagreed for a release.</p>
     */
    @Test
    public void noOtherSourceDecidesAPruneFromAWrittenOutName() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path sources = Paths.get("src", "main", "java");

        try (Stream<Path> walk = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("ProjectTreeWalk.java")) {
                    continue;
                }
                String source = Files.readString(file);
                for (String name : ProjectTreeWalk.buildOutputDirectories()) {
                    if (source.contains("equals(\"" + name + "\")")) {
                        offenders.add(file + " compares a directory name against \"" + name + "\"");
                    }
                }
            }
        }

        assertThat(offenders)
                .as("a prune decided here cannot help but drift from the shared one")
                .isEmpty();
    }
}
