package com.eonmux.cadetcoder.ui;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for ColorTheme
 */
public class ColorThemeTest {

    @Test
    public void testColorTheme_Construction() {
        ColorTheme theme = new ColorTheme(
                "test-theme", "Test theme description",
                "\u001B[34m", "\u001B[37m", "\u001B[32m",     // header, subheader, success
                "\u001B[33m", "\u001B[31m", "\u001B[34m",     // warning, error, info
                "\u001B[36m", "\u001B[32m", "\u001B[33m",     // command, string, path
                "\u001B[35m", "\u001B[96m", "\u001B[90m"      // accent1, accent2, dim
        );

        assertThat(theme.getName()).isEqualTo("test-theme");
        assertThat(theme.getDescription()).isEqualTo("Test theme description");
        assertThat(theme.getHeader()).isEqualTo("\u001B[34m");
        assertThat(theme.getSubheader()).isEqualTo("\u001B[37m");
        assertThat(theme.getSuccess()).isEqualTo("\u001B[32m");
        assertThat(theme.getWarning()).isEqualTo("\u001B[33m");
        assertThat(theme.getError()).isEqualTo("\u001B[31m");
        assertThat(theme.getInfo()).isEqualTo("\u001B[34m");
        assertThat(theme.getCommand()).isEqualTo("\u001B[36m");
        assertThat(theme.getString()).isEqualTo("\u001B[32m");
        assertThat(theme.getPath()).isEqualTo("\u001B[33m");
        assertThat(theme.getAccent1()).isEqualTo("\u001B[35m");
        assertThat(theme.getAccent2()).isEqualTo("\u001B[96m");
        assertThat(theme.getDim()).isEqualTo("\u001B[90m");
        assertThat(theme.getReset()).isEqualTo("\u001B[0m");
        assertThat(theme.getBold()).isEqualTo("\u001B[1m");
    }

    @Test
    public void testColorize_HeaderType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("Test Header", ColorTheme.ColorType.HEADER);

        assertThat(result).isEqualTo("\u001B[1m\u001B[34mTest Header\u001B[0m");
    }

    private ColorTheme createTestTheme() {
        return new ColorTheme(
                "test", "Test theme",
                "\u001B[34m", "\u001B[37m", "\u001B[32m",     // header, subheader, success
                "\u001B[33m", "\u001B[31m", "\u001B[34m",     // warning, error, info
                "\u001B[36m", "\u001B[32m", "\u001B[33m",     // command, string, path
                "\u001B[35m", "\u001B[96m", "\u001B[90m"      // accent1, accent2, dim
        );
    }

    @Test
    public void testColorize_SubheaderType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("Test Subheader", ColorTheme.ColorType.SUBHEADER);

        assertThat(result).isEqualTo("\u001B[1m\u001B[37mTest Subheader\u001B[0m");
    }

    @Test
    public void testColorize_SuccessType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("Success message", ColorTheme.ColorType.SUCCESS);

        assertThat(result).isEqualTo("\u001B[32mSuccess message\u001B[0m");
    }

    @Test
    public void testColorize_WarningType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("Warning message", ColorTheme.ColorType.WARNING);

        assertThat(result).isEqualTo("\u001B[33mWarning message\u001B[0m");
    }

    @Test
    public void testColorize_ErrorType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("Error message", ColorTheme.ColorType.ERROR);

        assertThat(result).isEqualTo("\u001B[31mError message\u001B[0m");
    }

    @Test
    public void testColorize_InfoType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("Info message", ColorTheme.ColorType.INFO);

        assertThat(result).isEqualTo("\u001B[34mInfo message\u001B[0m");
    }

    @Test
    public void testColorize_CommandType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("command --flag", ColorTheme.ColorType.COMMAND);

        assertThat(result).isEqualTo("\u001B[36mcommand --flag\u001B[0m");
    }

    @Test
    public void testColorize_StringType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("string value", ColorTheme.ColorType.STRING);

        assertThat(result).isEqualTo("\u001B[32mstring value\u001B[0m");
    }

    @Test
    public void testColorize_PathType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("/path/to/file", ColorTheme.ColorType.PATH);

        assertThat(result).isEqualTo("\u001B[33m/path/to/file\u001B[0m");
    }

    @Test
    public void testColorize_Accent1Type() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("accent text", ColorTheme.ColorType.ACCENT1);

        assertThat(result).isEqualTo("\u001B[35maccent text\u001B[0m");
    }

    @Test
    public void testColorize_Accent2Type() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("accent2 text", ColorTheme.ColorType.ACCENT2);

        assertThat(result).isEqualTo("\u001B[96maccent2 text\u001B[0m");
    }

    @Test
    public void testColorize_DimType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("dim text", ColorTheme.ColorType.DIM);

        assertThat(result).isEqualTo("\u001B[90mdim text\u001B[0m");
    }

    @Test
    public void testColorize_BoldType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("bold text", ColorTheme.ColorType.BOLD);

        assertThat(result).isEqualTo("\u001B[1mbold text\u001B[0m");
    }

    @Test
    public void testColorize_NormalType() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("normal text", ColorTheme.ColorType.NORMAL);

        assertThat(result).isEqualTo("normal text\u001B[0m");
    }

    @Test
    public void testColorize_NullText() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize(null, ColorTheme.ColorType.SUCCESS);

        assertThat(result).isNull();
    }

    @Test
    public void testColorize_EmptyText() {
        ColorTheme theme = createTestTheme();

        String result = theme.colorize("", ColorTheme.ColorType.SUCCESS);

        assertThat(result).isEmpty();
    }

    @Test
    public void testColorType_EnumValues() {
        ColorTheme.ColorType[] types = ColorTheme.ColorType.values();

        assertThat(types).contains(
                ColorTheme.ColorType.HEADER,
                ColorTheme.ColorType.SUBHEADER,
                ColorTheme.ColorType.SUCCESS,
                ColorTheme.ColorType.WARNING,
                ColorTheme.ColorType.ERROR,
                ColorTheme.ColorType.INFO,
                ColorTheme.ColorType.COMMAND,
                ColorTheme.ColorType.STRING,
                ColorTheme.ColorType.PATH,
                ColorTheme.ColorType.ACCENT1,
                ColorTheme.ColorType.ACCENT2,
                ColorTheme.ColorType.DIM,
                ColorTheme.ColorType.BOLD,
                ColorTheme.ColorType.NORMAL
                                  );
    }

    @Test
    public void testColorType_ValueOf() {
        ColorTheme.ColorType type = ColorTheme.ColorType.valueOf("SUCCESS");
        assertThat(type).isEqualTo(ColorTheme.ColorType.SUCCESS);

        type = ColorTheme.ColorType.valueOf("ERROR");
        assertThat(type).isEqualTo(ColorTheme.ColorType.ERROR);
    }
}