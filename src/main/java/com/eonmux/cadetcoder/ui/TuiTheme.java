package com.eonmux.cadetcoder.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.style.Style;

/**
 * TamboUI-native color theme for the interactive TUI.
 *
 * <p>This is the immediate-mode successor to the old Jexer theme object: every UI
 * element is described by an immutable {@link Style} (foreground/background colour
 * plus modifiers) instead of a Jexer {@code CellAttributes}. The slot names and
 * getter surface are kept identical to the previous theme object so the rest of the
 * application (theme command, validation, manager) is unaffected by the backend
 * change.</p>
 */
public class TuiTheme {
    private final String name;
    private final String description;

    // Core UI element colors
    private final Style windowBackground;
    private final Style windowBorder;
    private final Style windowTitle;

    // Text area colors
    private final Style textNormal;
    private final Style textActive;
    private final Style textSelected;
    private final Style textHighlight;

    // Input field colors
    private final Style fieldActive;
    private final Style fieldInactive;
    private final Style fieldSelected;

    // Semantic colors for different message types
    private final Style success;
    private final Style warning;
    private final Style error;
    private final Style info;
    private final Style command;
    private final Style path;
    private final Style string;

    // Accent colors for highlights and emphasis
    private final Style accent1;
    private final Style accent2;
    private final Style dim;
    private final Style bold;

    // Menu colors (retained for parity even though the immediate-mode shell has no menu bar)
    private final Style menuText;
    private final Style menuHighlighted;
    private final Style menuMnemonic;
    private final Style menuDisabled;

    // Status and labels
    private final Style label;
    private final Style status;

    // Raised surfaces and semantic tints -- BACKGROUND colours, not text styles.
    //
    // The model is the opencode theme's: areas of the console are separated by painting their
    // background one small step away from the base (a "raised" surface), and a notice is painted
    // with a dark, desaturated wash of its own semantic hue, the accent staying in the text on
    // top. Surfaces hold banners, step strips and the user's own lines; tints hold success,
    // warning and error notices. They are composed onto an element's style where the element is
    // rendered, which is why they are colours rather than styles.
    private final Color surface;
    private final Color surfaceStrong;
    private final Color successBg;
    private final Color warningBg;
    private final Color errorBg;

    public TuiTheme(String name, String description,
                    Style windowBackground, Style windowBorder, Style windowTitle,
                    Style textNormal, Style textActive, Style textSelected, Style textHighlight,
                    Style fieldActive, Style fieldInactive, Style fieldSelected,
                    Style success, Style warning, Style error, Style info,
                    Style command, Style path, Style string,
                    Style accent1, Style accent2, Style dim, Style bold,
                    Style menuText, Style menuHighlighted, Style menuMnemonic, Style menuDisabled,
                    Style label, Style status,
                    Color surface, Color surfaceStrong,
                    Color successBg, Color warningBg, Color errorBg) {

        this.name = name;
        this.description = description;
        this.windowBackground = windowBackground;
        this.windowBorder = windowBorder;
        this.windowTitle = windowTitle;
        this.textNormal = textNormal;
        this.textActive = textActive;
        this.textSelected = textSelected;
        this.textHighlight = textHighlight;
        this.fieldActive = fieldActive;
        this.fieldInactive = fieldInactive;
        this.fieldSelected = fieldSelected;
        this.success = success;
        this.warning = warning;
        this.error = error;
        this.info = info;
        this.command = command;
        this.path = path;
        this.string = string;
        this.accent1 = accent1;
        this.accent2 = accent2;
        this.dim = dim;
        this.bold = bold;
        this.menuText = menuText;
        this.menuHighlighted = menuHighlighted;
        this.menuMnemonic = menuMnemonic;
        this.menuDisabled = menuDisabled;
        this.label = label;
        this.status = status;
        this.surface = surface;
        this.surfaceStrong = surfaceStrong;
        this.successBg = successBg;
        this.warningBg = warningBg;
        this.errorBg = errorBg;
    }

    // Getters
    public String getName() { return name; }
    public String getDescription() { return description; }

    // Window elements
    public Style getWindowBackground() { return windowBackground; }
    public Style getWindowBorder() { return windowBorder; }
    public Style getWindowTitle() { return windowTitle; }

    // Text elements
    public Style getTextNormal() { return textNormal; }
    public Style getTextActive() { return textActive; }
    public Style getTextSelected() { return textSelected; }
    public Style getTextHighlight() { return textHighlight; }

    // Field elements
    public Style getFieldActive() { return fieldActive; }
    public Style getFieldInactive() { return fieldInactive; }
    public Style getFieldSelected() { return fieldSelected; }

    // Semantic colors
    public Style getSuccess() { return success; }
    public Style getWarning() { return warning; }
    public Style getError() { return error; }
    public Style getInfo() { return info; }
    public Style getCommand() { return command; }
    public Style getPath() { return path; }
    public Style getString() { return string; }

    // Accent colors
    public Style getAccent1() { return accent1; }
    public Style getAccent2() { return accent2; }
    public Style getDim() { return dim; }
    public Style getBold() { return bold; }

    // Menu colors
    public Style getMenuText() { return menuText; }
    public Style getMenuHighlighted() { return menuHighlighted; }
    public Style getMenuMnemonic() { return menuMnemonic; }
    public Style getMenuDisabled() { return menuDisabled; }

    // Status and labels
    public Style getLabel() { return label; }
    public Style getStatus() { return status; }

    // Raised surfaces and semantic tints (background colours; may be null for a theme that
    // declares none, in which case nothing is painted behind the element)
    public Color getSurface()       { return surface; }
    public Color getSurfaceStrong() { return surfaceStrong; }
    public Color getSuccessBg()     { return successBg; }
    public Color getWarningBg()     { return warningBg; }
    public Color getErrorBg()       { return errorBg; }

    /**
     * Build a TamboUI {@link Style} from a foreground/background colour pair and the
     * classic terminal attribute flags. Mirrors the old {@code createCellAttributes}
     * helper so theme definitions read identically.
     *
     * @param foreground foreground colour, or {@code null} to leave unset
     * @param background background colour, or {@code null} to leave unset
     * @param bold       apply the bold modifier
     * @param blink      apply the (slow) blink modifier
     * @param dim        apply the dim modifier
     * @param underline  apply the underline modifier
     * @param reverse    apply the reverse-video modifier
     * @return an immutable style describing the element
     */
    public static Style style(Color foreground, Color background,
                              boolean bold, boolean blink, boolean dim,
                              boolean underline, boolean reverse) {
        Style s = Style.EMPTY;
        if (foreground != null) {
            s = s.fg(foreground);
        }
        if (background != null) {
            s = s.bg(background);
        }
        if (bold) {
            s = s.addModifier(Modifier.BOLD);
        }
        if (blink) {
            s = s.addModifier(Modifier.SLOW_BLINK);
        }
        if (dim) {
            s = s.addModifier(Modifier.DIM);
        }
        if (underline) {
            s = s.addModifier(Modifier.UNDERLINED);
        }
        if (reverse) {
            s = s.addModifier(Modifier.REVERSED);
        }
        return s;
    }
}
