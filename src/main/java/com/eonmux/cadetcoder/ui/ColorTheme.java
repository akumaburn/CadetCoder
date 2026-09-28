package com.eonmux.cadetcoder.ui;

/**
 * Color theme definition for CLI output.
 * Each theme defines colors for different output types.
 */
public class ColorTheme {
    private final String name;
    private final String description;

    // ANSI color codes for different output types
    private final String header;
    private final String subheader;
    private final String success;
    private final String warning;
    private final String error;
    private final String info;
    private final String command;
    private final String string;
    private final String path;
    private final String reset;
    private final String bold;

    // Additional color codes for enhanced output
    private final String accent1;  // For special highlights
    private final String accent2;  // For secondary highlights
    private final String dim;      // For less important text

    public ColorTheme(String name, String description,
                      String header, String subheader, String success,
                      String warning, String error, String info,
                      String command, String string, String path,
                      String accent1, String accent2, String dim) {
        this.name        = name;
        this.description = description;
        this.header      = header;
        this.subheader   = subheader;
        this.success     = success;
        this.warning     = warning;
        this.error       = error;
        this.info        = info;
        this.command     = command;
        this.string      = string;
        this.path        = path;
        this.accent1     = accent1;
        this.accent2     = accent2;
        this.dim         = dim;
        this.reset       = "\u001B[0m";
        this.bold        = "\u001B[1m";
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getHeader() {
        return header;
    }

    public String getSubheader() {
        return subheader;
    }

    public String getSuccess() {
        return success;
    }

    public String getWarning() {
        return warning;
    }

    public String getError() {
        return error;
    }

    public String getInfo() {
        return info;
    }

    public String getCommand() {
        return command;
    }

    public String getString() {
        return string;
    }

    public String getPath() {
        return path;
    }

    public String getAccent1() {
        return accent1;
    }

    public String getAccent2() {
        return accent2;
    }

    public String getDim() {
        return dim;
    }

    public String getReset() {
        return reset;
    }

    public String getBold() {
        return bold;
    }

    /**
     * Apply color to text based on type
     * In TUI mode, ANSI codes are disabled
     */
    public String colorize(String text, ColorType type) {
        if (text == null || text.isEmpty() || TuiMode.isActive()) {
            return text;
        }

        String color = switch (type) {
            case HEADER -> bold + header;
            case SUBHEADER -> bold + subheader;
            case SUCCESS -> success;
            case WARNING -> warning;
            case ERROR -> error;
            case INFO -> info;
            case COMMAND -> command;
            case STRING -> string;
            case PATH -> path;
            case ACCENT1 -> accent1;
            case ACCENT2 -> accent2;
            case DIM -> dim;
            case BOLD -> bold;
            default -> "";
        };

        return color + text + reset;
    }
    

    /**
     * Color types for different output elements
     */
    public enum ColorType {
        HEADER,
        SUBHEADER,
        SUCCESS,
        WARNING,
        ERROR,
        INFO,
        COMMAND,
        STRING,
        PATH,
        ACCENT1,
        ACCENT2,
        DIM,
        BOLD,
        NORMAL
    }
}