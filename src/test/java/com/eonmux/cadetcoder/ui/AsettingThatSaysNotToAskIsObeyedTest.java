package com.eonmux.cadetcoder.ui;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ui.interactivePrompts} decides whether a run stops to ask.
 *
 * <p><b>The defect</b>: the setting existed, {@code /config} offered it, listed it and saved it, and
 * twenty-two commands each decided the same question by reading a system property directly. Turning
 * it off reported success and changed nothing.</p>
 */
class AsettingThatSaysNotToAskIsObeyedTest {

    private String saved;

    @BeforeEach
    void setUp() {
        saved = System.getProperty(InteractivePrompts.PROPERTY);
        System.clearProperty(InteractivePrompts.PROPERTY);
    }

    @AfterEach
    void tearDown() {
        if (saved == null) {
            System.clearProperty(InteractivePrompts.PROPERTY);
        } else {
            System.setProperty(InteractivePrompts.PROPERTY, saved);
        }
    }

    private static Configuration configuredToAsk(boolean asking) {
        Configuration configuration = new Configuration();
        configuration.getUi().setInteractivePrompts(asking);
        return configuration;
    }

    private static MockedStatic<ConfigManager> managerReading(Configuration configuration) {
        ConfigManager manager = Mockito.mock(ConfigManager.class);
        Mockito.when(manager.getConfig()).thenReturn(configuration);
        MockedStatic<ConfigManager> statics = Mockito.mockStatic(ConfigManager.class);
        statics.when(ConfigManager::getInstance).thenReturn(manager);
        return statics;
    }

    @Test
    void whatTheSettingSaysIsWhatHappens() {
        try (MockedStatic<ConfigManager> ignored = managerReading(configuredToAsk(false))) {
            assertThat(InteractivePrompts.isOn())
                    .as("the setting said not to ask")
                    .isFalse();
        }
        try (MockedStatic<ConfigManager> ignored = managerReading(configuredToAsk(true))) {
            assertThat(InteractivePrompts.isOn()).isTrue();
        }
    }

    @Test
    void oneRunCanSayOtherwiseOnTheCommandLine() {
        try (MockedStatic<ConfigManager> ignored = managerReading(configuredToAsk(false))) {
            System.setProperty(InteractivePrompts.PROPERTY, "true");
            assertThat(InteractivePrompts.isOn())
                    .as("what is asked for on this run beats what was saved")
                    .isTrue();
        }
    }

    @Test
    void aconfigurationThatCannotBeReadKeepsAsking() {
        try (MockedStatic<ConfigManager> statics = Mockito.mockStatic(ConfigManager.class)) {
            statics.when(ConfigManager::getInstance).thenThrow(new IllegalStateException("no config"));
            assertThat(InteractivePrompts.isOn())
                    .as("acting unattended is the answer that cannot be taken back")
                    .isTrue();
        }
    }
}
