package com.eonmux.cadetcoder.util;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;

import org.junit.After;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * What {@code indexing.excludePatterns} says is skipped is skipped by every walk, not four of them.
 *
 * <p><b>The defect</b>: thirteen places in this tool walk the project tree. Four read the user's
 * {@code indexing.excludePatterns} -- the index, {@code grep}, {@code ls} and {@code glob} -- and
 * the rest used a list written into the source. The defaults are the same list, so nothing looked
 * wrong until somebody customised it: adding {@code vendor} made {@code grep} skip it while
 * {@code read}, {@code write}, {@code edit}, {@code refactor} and the agent's own file search
 * carried on walking into it, and removing {@code target} from the list did not make it searchable
 * to any of them.</p>
 *
 * <p>Of the four that did read it, three compared names for equality while the setting is
 * documented -- and implemented by the fourth -- as a regular expression. So {@code build-.*}
 * excluded a directory from the index and from nothing else.</p>
 */
public class OneExcludeListGovernsEveryProjectWalkTest {

    @After
    public void tearDown() {
        ProjectTreeWalk.forgetConfiguredExclusions();
    }

    private static ConfigManager managerExcluding(String... patterns) {
        ConfigManager manager = mock(ConfigManager.class);
        Configuration config  = new Configuration();
        Configuration.IndexingConfig indexing = new Configuration.IndexingConfig();
        indexing.setExcludePatterns(patterns);
        config.setIndexing(indexing);
        when(manager.getConfig()).thenReturn(config);
        return manager;
    }

    private static boolean prunedWith(String name, String... patterns) {
        ProjectTreeWalk.forgetConfiguredExclusions();
        ConfigManager manager = managerExcluding(patterns);
        try (MockedStatic<ConfigManager> configured = mockStatic(ConfigManager.class)) {
            configured.when(ConfigManager::getInstance).thenReturn(manager);
            return ProjectTreeWalk.isPrunedName(name);
        }
    }

    @Test
    public void adirectoryTheUserExcludedIsSkippedByTheSharedWalk() {
        assertThat(prunedWith("vendor", "vendor", "target")).isTrue();
        assertThat(prunedWith("src", "vendor", "target")).isFalse();
    }

    @Test
    public void adirectoryTheUserNoLongerExcludesIsWalkedAgain() {
        assertThat(prunedWith("target", "vendor"))
                .as("the shipped list is a default, not a floor under whatever the user chooses")
                .isFalse();
    }

    @Test
    public void apatternIsMatchedAsTheRegularExpressionItIsDocumentedToBe() {
        assertThat(prunedWith("build-linux", "build-.*")).isTrue();
        assertThat(prunedWith("buildings", "build-.*")).isFalse();
    }

    @Test
    public void apatternThatIsNotValidRegexIsComparedLiterally() {
        assertThat(prunedWith("*.tmp", "*.tmp")).isTrue();
        assertThat(prunedWith("notes.tmp", "*.tmp")).isFalse();
    }

    @Test
    public void hiddenDirectoriesAreSkippedWhateverTheListSays() {
        assertThat(prunedWith(".git")).isTrue();
        assertThat(prunedWith(".idea", "vendor")).isTrue();
        assertThat(prunedWith(".", "vendor")).isFalse();
        assertThat(prunedWith("..", "vendor")).isFalse();
    }

    @Test
    public void excludingNothingLeavesOnlyTheHiddenRule() {
        assertThat(prunedWith("target")).isFalse();
        assertThat(prunedWith("node_modules")).isFalse();
        assertThat(prunedWith(".svn")).isTrue();
    }

    @Test
    public void theShippedListIsWhatIsSkippedWhenNobodyHasChosen() {
        assertThat(ProjectTreeWalk.buildOutputDirectories())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.Set.of(new Configuration.IndexingConfig().getExcludePatterns()));
    }
}
