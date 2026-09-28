package com.eonmux.cadetcoder.util;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a path is a placeholder is one rule.
 *
 * <p>{@code grep} and {@code ls} each carried their own copy of it, and the copies disagreed about
 * the only thing that matters: {@code grep} refused to rewrite a path that exists, while {@code ls}
 * asked the shape question first. So a project with a real {@code com/example} package -- or any
 * directory whose name contains "placeholder" -- had {@code ls} silently listing somewhere else
 * while {@code grep} looked exactly where it was told, and neither said anything had happened.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>Existence wins over shape, in every case; a path that is genuinely a stand-in still resolves
 * to something real; and a path that is neither is handed back untouched so the caller can report
 * that it does not exist.</p>
 */
public class OnePlaceholderPathRuleTest {

    /** Somewhere inside the build directory, so the walk can see it and the repo never does. */
    private static final Path PROBE_ROOT = Paths.get("target", "placeholder-probe");

    private final List<String> warnings = new ArrayList<>();

    @After
    public void removeProbe() throws IOException {
        if (!Files.exists(PROBE_ROOT)) {
            return;
        }
        try (var paths = Files.walk(PROBE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    public void aDirectoryThatExistsIsUsedExactlyAsWrittenEvenWhenItLooksLikeAnExample()
            throws IOException {
        // The last segment names a directory this project really has somewhere else, so a copy
        // that asked the shape question before the existence question would answer with that one.
        Path real = PROBE_ROOT.resolve(Paths.get("com", "example", "java"));
        Files.createDirectories(real);

        assertThat(PlaceholderPath.looksLikeStandIn(real.toString()))
                .as("this path has the shape the heuristic looks for")
                .isTrue();
        assertThat(PlaceholderPath.resolved(real.toString(), warnings::add))
                .as("a directory that is really there is never redirected somewhere else")
                .isEqualTo(real.toString());
    }

    @Test
    public void aBareSourceDirectoryThatExistsIsNotTreatedAsAStandIn() {
        assertThat(Files.isDirectory(Paths.get("src")))
                .as("this project has a src directory to ask about")
                .isTrue();
        assertThat(PlaceholderPath.resolved("src", warnings::add)).isEqualTo("src");
    }

    @Test
    public void aStandInIsResolvedToADirectoryThisProjectActuallyHas() {
        String resolved = PlaceholderPath.resolved("path/to/java", warnings::add);

        assertThat(resolved).isNotEqualTo("path/to/java");
        assertThat(Files.isDirectory(Paths.get(resolved)))
                .as("a stand-in resolves to somewhere that exists, or it has helped nobody")
                .isTrue();
        assertThat(Paths.get(resolved).getFileName().toString()).isEqualTo("java");
    }

    @Test
    public void aPathThatIsNeitherThereNorAStandInIsHandedBackUntouched() {
        String asked = "definitely/not/here";

        assertThat(PlaceholderPath.looksLikeStandIn(asked)).isFalse();
        assertThat(PlaceholderPath.resolved(asked, warnings::add)).isEqualTo(asked);
    }

    @Test
    public void everyShapeTheTwoCopiesCalledAPlaceholderStillCountsAsOne() {
        for (String shape : new String[] {"path/to/src", "src/main/java/com/example/Foo.java",
                                          "found_path/thing", "some/placeholder/dir",
                                          "a/example.txt", "/path/to/thing", "src", "test"}) {
            assertThat(PlaceholderPath.looksLikeStandIn(shape))
                    .as("%s was a placeholder before the copies were merged and must still be", shape)
                    .isTrue();
        }
    }

    @Test
    public void nothingIsResolvedWithoutAPathToResolve() {
        assertThat(PlaceholderPath.resolved(null, warnings::add)).isEqualTo(".");
    }
}
