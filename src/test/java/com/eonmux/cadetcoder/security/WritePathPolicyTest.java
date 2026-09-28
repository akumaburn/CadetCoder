package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * One rule, shared by every command that writes a file. These tests are the rule.
 */
class WritePathPolicyTest {

    @TempDir
    Path project;

    private String                      originalWorkingDir;
    private MockedStatic<ConfigManager> configs;
    private Configuration               config;

    @BeforeEach
    void setUp() {
        originalWorkingDir = System.getProperty("user.dir");
        System.setProperty("user.dir", project.toAbsolutePath().toString());

        config = new Configuration();
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(config);
        configs = mockStatic(ConfigManager.class);
        configs.when(ConfigManager::getInstance).thenReturn(manager);
    }

    @AfterEach
    void tearDown() {
        configs.close();
        System.setProperty("user.dir", originalWorkingDir);
    }

    @Test
    void aFileInsideTheProjectIsAllowed() {
        assertThat(WritePathPolicy.decide(project.resolve("src/Main.java")).isAllowed()).isTrue();
    }

    @Test
    void aRelativePathIsResolvedAgainstTheProject() {
        assertThat(WritePathPolicy.decide(Paths.get("notes.md")).isAllowed()).isTrue();
    }

    @Test
    void anAbsolutePathOutsideTheProjectIsRefusedByTheShippedPolicy() {
        assertThat(WritePathPolicy.decide(Paths.get("/var/lib/somewhere/else.txt")))
                .isEqualTo(WritePathPolicy.Decision.DENIED_BY_POLICY);
    }

    @Test
    void anAbsolutePathOutsideTheProjectIsRefusedEvenWhenReadingThereIsAllowed() {
        config.getSecurity().setAllowOutsideProject(true);

        assertThat(WritePathPolicy.decide(Paths.get("/var/lib/somewhere/else.txt")))
                .isEqualTo(WritePathPolicy.Decision.OUTSIDE_WORKING_DIRECTORY);
    }

    @Test
    void aTraversalOutOfTheProjectIsRefused() {
        assertThat(WritePathPolicy.decide(project.resolve("../escaped.txt")).isAllowed())
                .isFalse();
    }

    @Test
    void aSystemPathIsRefusedByPolicyRatherThanByContainment() {
        assertThat(WritePathPolicy.decide(Paths.get("/etc/passwd")))
                .isEqualTo(WritePathPolicy.Decision.DENIED_BY_POLICY);
    }

    @Test
    void theSystemTemporaryDirectoryIsAllowedWhenPathsOutsideTheProjectAre() {
        config.getSecurity().setAllowOutsideProject(true);
        Path temp = Paths.get(System.getProperty("java.io.tmpdir")).resolve("cadet-policy-check.txt");

        assertThat(WritePathPolicy.decide(temp).isAllowed()).isTrue();
    }

    @Test
    void theSystemTemporaryDirectoryIsOutsideTheProjectByDefault() {
        Path temp = Paths.get(System.getProperty("java.io.tmpdir")).resolve("cadet-policy-check.txt");

        assertThat(WritePathPolicy.decide(temp).isAllowed()).isFalse();
    }

    @Test
    void aDirectoryMerelyNamedLikeTheTempDirectoryIsNotTheTempDirectory() {
        config.getSecurity().setAllowOutsideProject(true);
        Path lookalike = Paths.get(System.getProperty("java.io.tmpdir") + "-elsewhere/file.txt");

        assertThat(WritePathPolicy.decide(lookalike).isAllowed()).isFalse();
    }

    @Test
    void noPathAtAllIsMalformedRatherThanAllowed() {
        assertThat(WritePathPolicy.decide(null)).isEqualTo(WritePathPolicy.Decision.MALFORMED);
    }

    @Test
    void everyRefusalCarriesAMessageAndAnAllowanceCarriesNone() {
        Path path = Paths.get("/etc/passwd");
        for (WritePathPolicy.Decision decision : WritePathPolicy.Decision.values()) {
            if (decision.isAllowed()) {
                assertThat(WritePathPolicy.reasonFor(decision, path)).isNull();
            } else {
                assertThat(WritePathPolicy.reasonFor(decision, path)).isNotBlank();
            }
        }
    }
}
