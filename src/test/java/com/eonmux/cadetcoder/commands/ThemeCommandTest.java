package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.ColorTheme;
import com.eonmux.cadetcoder.ui.ColorThemeManager;
import org.junit.*;

import static org.assertj.core.api.Assertions.assertThat;

public class ThemeCommandTest {

    private ThemeCommand      themeCommand;
    private TestOutputCapture outputCapture;
    private String            originalTheme;
    private String            originalColorTheme;

    @Before
    public void setUp() {
        themeCommand  = new ThemeCommand();
        outputCapture = new TestOutputCapture();
        outputCapture.startCapture();

        // Save original theme
        originalTheme = com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme().getName();
        ColorTheme currentColor = ColorThemeManager.getCurrentTheme();
        originalColorTheme = currentColor != null ? currentColor.getName() : null;
    }

    @After
    public void tearDown() {
        outputCapture.stopCapture();

        // Restore original theme
        com.eonmux.cadetcoder.ui.TuiThemeManager.setTheme(originalTheme);
        if (originalColorTheme != null) {
            ColorThemeManager.setTheme(originalColorTheme);
        }
    }

    @Test
    public void testThemeList() {
        // Execute list command
        int exitCode = themeCommand.execute(new String[] {"list"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();

        // Should list all themes
        assertThat(output).contains("Available TUI Themes");
        assertThat(output).contains("default");
        assertThat(output).contains("solarized-dark");
        assertThat(output).contains("solarized-light");
        assertThat(output).contains("monokai");
        assertThat(output).contains("dracula");
        assertThat(output).contains("nord");
        assertThat(output).contains("gruvbox-dark");
        assertThat(output).contains("one-dark");
        assertThat(output).contains("matrix");

        // Should mark the active theme
        assertThat(output).contains("★ active");
        // ... and no row should carry padding past its last visible character.
        for (String line : output.split("\\R")) {
            assertThat(line).isEqualTo(line.stripTrailing());
        }
    }

    @Test
    public void testThemeSet() {
        // Execute set command
        int exitCode = themeCommand.execute(new String[] {"set", "nord"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Applied theme: nord");
        // Use new Jexer theme manager for verification
        assertThat(com.eonmux.cadetcoder.ui.TuiThemeManager.getCurrentTheme().getName()).isEqualTo("nord");
    }

    @Test
    public void testThemeSetInvalid() {
        // Execute set with invalid theme
        int exitCode = themeCommand.execute(new String[] {"set", "nonexistent"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Theme 'nonexistent' not found");
    }

    @Test
    public void testThemeCurrent() {
        // Set a known theme first using new Jexer theme manager
        com.eonmux.cadetcoder.ui.TuiThemeManager.setTheme("monokai");

        // Execute current command
        int exitCode = themeCommand.execute(new String[] {"current"});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Current TUI Theme");
        assertThat(output).contains("Name: monokai");
        assertThat(output).contains("Monokai theme with vibrant colors");
    }

    @Test
    public void testPreviewNamedThemeIsReadOnly() {
        // Establish a known active console theme before previewing a different one.
        ColorThemeManager.setTheme("monokai");

        int exitCode = themeCommand.execute(new String[] {"preview", "nord"});

        assertThat(exitCode).isEqualTo(0);
        // 'preview' must not permanently change the active console theme.
        assertThat(ColorThemeManager.getCurrentTheme().getName()).isEqualTo("monokai");
    }

    @Test
    public void testPreviewAllThemesIsReadOnly() {
        // Establish a known active console theme before previewing every theme.
        ColorThemeManager.setTheme("dracula");

        int exitCode = themeCommand.execute(new String[] {"preview"});

        assertThat(exitCode).isEqualTo(0);
        // After previewing all themes, the original active theme must be restored.
        assertThat(ColorThemeManager.getCurrentTheme().getName()).isEqualTo("dracula");
    }

    @Test
    public void testInvalidAction() {
        // Execute invalid action
        int exitCode = themeCommand.execute(new String[] {"invalid"});

        // Verify
        assertThat(exitCode).isEqualTo(1);
        String output = outputCapture.getAllOutput();
        assertThat(output).contains("Unknown action: invalid");
        assertThat(output).contains("Actions:");
    }

    @Test
    public void testDefaultAction() {
        // Execute without action (should default to list)
        int exitCode = themeCommand.execute(new String[] {});

        // Verify
        assertThat(exitCode).isEqualTo(0);
        String output = outputCapture.getStdout();
        assertThat(output).contains("Available TUI Themes");
    }
}