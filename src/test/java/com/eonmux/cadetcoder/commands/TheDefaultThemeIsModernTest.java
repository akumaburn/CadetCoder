package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.test.TestOutputCapture;
import com.eonmux.cadetcoder.ui.ColorTheme;
import com.eonmux.cadetcoder.ui.ColorThemeManager;
import com.eonmux.cadetcoder.ui.TuiThemeManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A new configuration and {@code theme reset} both give the {@code modern} theme.
 *
 * <p>The default is one constant, {@link Configuration.UiConfig#DEFAULT_COLOR_THEME}. A second
 * copy of it in the configuration or in {@code theme reset} can name another theme than the one
 * the shell falls back to.</p>
 */
public class TheDefaultThemeIsModernTest {

    private TestOutputCapture output;
    private String            tuiTheme;
    private String            colorTheme;
    private String            configured;

    @Before
    public void setUp() {
        output = new TestOutputCapture();
        output.startCapture();
        tuiTheme   = TuiThemeManager.getCurrentTheme().getName();
        ColorTheme color = ColorThemeManager.getCurrentTheme();
        colorTheme = color == null ? null : color.getName();
        configured = ConfigManager.getInstance().getConfig().getUi().getColorTheme();
    }

    @After
    public void tearDown() {
        output.stopCapture();
        TuiThemeManager.setTheme(tuiTheme);
        if (colorTheme != null) {
            ColorThemeManager.setTheme(colorTheme);
        }
        ConfigManager.getInstance().getConfig().getUi().setColorTheme(configured);
    }

    @Test
    public void aNewConfigurationUsesTheModernTheme() {
        assertThat(new Configuration().getUi().getColorTheme()).isEqualTo("modern");
        assertThat(TuiThemeManager.DEFAULT_THEME).isEqualTo("modern");
    }

    @Test
    public void resetGoesBackToTheModernTheme() {
        new ThemeCommand().execute(new String[] {"set", "dracula"});

        assertThat(new ThemeCommand().execute(new String[] {"reset"})).isZero();

        assertThat(TuiThemeManager.getCurrentTheme().getName()).isEqualTo("modern");
        assertThat(ConfigManager.getInstance().getConfig().getUi().getColorTheme())
                .isEqualTo("modern");
    }
}
