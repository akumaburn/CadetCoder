package com.eonmux.cadetcoder.security;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@code --read-only} is a promise made once on the command line and kept by every command that
 * could write. These tests hold the single place that promise is now expressed.
 */
class ReadOnlyGuardTest {

    private TestOutputCapture output;

    @BeforeEach
    void setUp() {
        output = new TestOutputCapture();
    }

    @AfterEach
    void tearDown() {
        output.restore();
    }

    private static Configuration configWithReadOnly(boolean readOnly) {
        Configuration config = new Configuration();
        config.getSecurity().setReadOnlyMode(readOnly);
        return config;
    }

    private static MockedStatic<ConfigManager> stubConfig(MockedStatic<ConfigManager> statics,
                                                          boolean readOnly) {
        ConfigManager manager = mock(ConfigManager.class);
        when(manager.getConfig()).thenReturn(configWithReadOnly(readOnly));
        statics.when(ConfigManager::getInstance).thenReturn(manager);
        return statics;
    }

    @Test
    void aWriteIsRefusedAndSaysWhy() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            stubConfig(configs, true);

            assertThat(ReadOnlyGuard.blocks("write file")).isTrue();
            assertThat(output.getAllOutput())
                    .contains("Cannot write file: read-only mode is enabled");
        }
    }

    @Test
    void aWriteIsAllowedSilentlyWhenReadOnlyIsOff() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            stubConfig(configs, false);

            assertThat(ReadOnlyGuard.blocks("write file")).isFalse();
            assertThat(output.getAllOutput()).doesNotContain("read-only");
        }
    }

    @Test
    void everyRefusalIsWordedTheSameWay() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            stubConfig(configs, true);

            ReadOnlyGuard.blocks("push");
            ReadOnlyGuard.blocks("commit");

            assertThat(output.getAllOutput())
                    .contains("Cannot push: read-only mode is enabled")
                    .contains("Cannot commit: read-only mode is enabled");
        }
    }

    @Test
    void theModeCanBeReadWithoutReportingAnything() {
        try (MockedStatic<ConfigManager> configs = mockStatic(ConfigManager.class)) {
            stubConfig(configs, true);

            assertThat(ReadOnlyGuard.isEnabled()).isTrue();
            assertThat(output.getAllOutput()).isEmpty();
        }
    }
}
