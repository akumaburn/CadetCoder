package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Configuration updated: {@code <name> = <value>}" has to name the value that was stored.
 *
 * <h2>The defect</h2>
 *
 * <p>The line echoed the argument as typed. A setting that normalises what it is given -- a path
 * beginning with {@code ~}, or a blank that means "back to the default" -- was therefore confirmed
 * with one value and stored as another, and the difference only showed up the next time somebody
 * read the setting back. Reporting the request rather than the outcome is the one thing a
 * confirmation must not do.</p>
 */
public class WhatConfigSaysItSavedIsWhatItSavedTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private ConfigCommand     config;
    private TestOutputCapture output;
    private String            originalUserHome;
    private String            originalBaseDir;

    @Before
    public void setUp() throws Exception {
        config = new ConfigCommand();
        output = new TestOutputCapture();
        output.startCapture();

        // Both are process-wide and outlive this class unless they are put back.
        originalUserHome = System.getProperty("user.home");
        originalBaseDir  = Configuration.defaultBaseDir;
        System.setProperty("user.home", tempFolder.getRoot().getAbsolutePath());
        Configuration.defaultBaseDir = tempFolder.getRoot().getAbsolutePath() + "/.cadet";

        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @After
    public void tearDown() throws Exception {
        output.stopCapture();
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        Configuration.defaultBaseDir = originalBaseDir;
        java.lang.reflect.Field instance = ConfigManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test
    public void aPathThatWasExpandedIsConfirmedAsThePathThatWasStored() {
        String home = tempFolder.getRoot().getAbsolutePath();

        assertThat(config.execute(new String[] {"baseDir", "~/work/cadet"})).isZero();

        assertThat(output.getStdout())
                .as("a tilde is expanded on the way in, so echoing the argument confirms a value "
                    + "that is not the one in the file")
                .contains("Configuration updated: baseDir = " + home + "/work/cadet");
    }

    @Test
    public void aValueThatNeededNoChangingIsConfirmedUnchanged() {
        assertThat(config.execute(new String[] {"ai.model", "gpt-4o"})).isZero();

        assertThat(output.getStdout()).contains("Configuration updated: ai.model = gpt-4o");
    }

    @Test
    public void aSecretIsStillNeverPrintedBackInFull() {
        assertThat(config.execute(new String[] {"ai.apiKey", "sk-01234567890123456789"})).isZero();

        assertThat(output.getStdout())
                .doesNotContain("sk-01234567890123456789")
                .contains("Configuration updated: ai.apiKey =");
    }
}
