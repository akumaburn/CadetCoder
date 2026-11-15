package com.eonmux.cadetcoder.ui;

/**
 * Authentic, published 24-bit truecolor palettes used by {@link TuiThemeManager}.
 *
 * <p>Each nested class holds the canonical hex values for a single named theme so the
 * theme construction in {@link TuiThemeManager} reads as a clear mapping from named
 * palette colour to UI slot rather than a wall of inline hex strings. All values are
 * the real, published palettes for each scheme (Solarized, Nord, Dracula, Gruvbox,
 * Monokai, One Dark, etc.) so the themes are visually distinct from one another.</p>
 *
 * <p>This class is a pure constants holder; it is never instantiated.</p>
 */
final class ThemePalettes {

    private ThemePalettes() {
        // constants holder, not instantiable
    }

    /** Modern dark (graphite + calm accent) — the new default theme. */
    static final class Modern {
        private Modern() {}
        static final String BG          = "#1b1e24"; // graphite background
        static final String SURFACE     = "#2b303b"; // border / surface
        static final String TEXT        = "#d7dae0"; // normal text
        static final String BRIGHT      = "#ffffff"; // bright / highlight text
        static final String SELECTION   = "#2f6f7e"; // selection background
        static final String ACCENT_TEAL = "#56b6c2"; // primary accent
        static final String ACCENT_BLUE = "#61afef"; // secondary accent / info
        static final String SUCCESS     = "#98c379";
        static final String WARNING     = "#e5c07b";
        static final String ERROR       = "#e06c75";
        static final String INFO        = "#61afef";
        static final String COMMAND     = "#c678dd";
        static final String PATH        = "#56b6c2";
        static final String STRING      = "#e5c07b";
        static final String DIM         = "#5c6370"; // comment / muted

        // Raised surfaces and semantic tints, in the model of the opencode theme: a surface is
        // one small luminance step above the background, and a tint is a dark, desaturated wash
        // of the semantic hue with the accent carried by the TEXT on top of it -- never by a
        // bright background.
        static final String SURFACE_BG        = "#232833"; // raised surface (step strips)
        static final String SURFACE_STRONG_BG = "#2b303b"; // the surface constant itself: banners
        static final String TINT_SUCCESS      = "#243019"; // dark moss under the green
        static final String TINT_WARNING      = "#332a14"; // dark amber under the amber
        static final String TINT_ERROR        = "#37222c"; // dark red under the red
    }

    /** Matrix — layered greens on near-black. */
    static final class Matrix {
        private Matrix() {}
        static final String BG          = "#0b0f0b"; // near-black background
        static final String NORMAL      = "#00b341"; // normal green
        static final String BRIGHT      = "#39ff14"; // bright phosphor green
        static final String HIGHLIGHT   = "#d6ffd6"; // pale green highlight
        static final String DIM         = "#0a7d34"; // dim green
        static final String ACCENT1     = "#aaffaa"; // soft bright accent
        static final String ACCENT2     = "#00ffae"; // mint accent
        static final String SUCCESS     = "#39ff14";
        static final String WARNING     = "#d7ff5c";
        static final String ERROR       = "#ff5f5f";
        static final String INFO        = "#2fffb0";
        static final String SELECTION   = "#00b341"; // selection uses the normal green

        // Raised surfaces and tints: green-on-black has its own ramp, so the surfaces step up
        // through near-black greens and the tints are the same greens at a much lower luminance.
        static final String SURFACE_BG        = "#131b13";
        static final String SURFACE_STRONG_BG = "#1c271c";
        static final String TINT_SUCCESS      = "#0e2c11";
        static final String TINT_WARNING      = "#26340f";
        static final String TINT_ERROR        = "#3a1414";
    }

    /** Solarized Dark (Ethan Schoonover). */
    static final class SolarizedDark {
        private SolarizedDark() {}
        static final String BASE03  = "#002b36"; // background
        static final String BASE02  = "#073642"; // background highlights / selection
        static final String BASE01  = "#586e75"; // comments / dim
        static final String BASE00  = "#657b83";
        static final String BASE0   = "#839496"; // body text
        static final String BASE1   = "#93a1a1"; // emphasised text
        static final String YELLOW  = "#b58900";
        static final String ORANGE  = "#cb4b16";
        static final String RED     = "#dc322f";
        static final String MAGENTA = "#d33682";
        static final String VIOLET  = "#6c71c4";
        static final String BLUE    = "#268bd2";
        static final String CYAN    = "#2aa198";
        static final String GREEN   = "#859900";

        // BASE02 is Solarized's own background-highlight; the strong surface is one step above
        // it, and the tints are the accent hues at Solarized's low saturation.
        static final String SURFACE_BG        = "#073642"; // BASE02
        static final String SURFACE_STRONG_BG = "#0b4553";
        static final String TINT_SUCCESS      = "#1e3308";
        static final String TINT_WARNING      = "#39300c";
        static final String TINT_ERROR        = "#3e1412";
    }

    /** Solarized Light (Ethan Schoonover). */
    static final class SolarizedLight {
        private SolarizedLight() {}
        static final String BASE3   = "#fdf6e3"; // background
        static final String BASE2   = "#eee8d5"; // background highlights / selection
        static final String BASE1   = "#93a1a1";
        static final String BASE0   = "#839496";
        static final String BASE00  = "#657b83"; // body text
        static final String BASE01  = "#586e75"; // emphasised text / dim
        static final String YELLOW  = "#b58900";
        static final String ORANGE  = "#cb4b16";
        static final String RED     = "#dc322f";
        static final String MAGENTA = "#d33682";
        static final String VIOLET  = "#6c71c4";
        static final String BLUE    = "#268bd2";
        static final String CYAN    = "#2aa198";
        static final String GREEN   = "#859900";

        // A light theme lowers the surface below the background instead of raising it, and the
        // tints are pastels of the accent hues -- darker than the cream, never brighter.
        static final String SURFACE_BG        = "#eee8d5"; // BASE2
        static final String SURFACE_STRONG_BG = "#e3dac1";
        static final String TINT_SUCCESS      = "#dce8c2";
        static final String TINT_WARNING      = "#f0e2ba";
        static final String TINT_ERROR        = "#f2ceca";
    }

    /** Monokai (Wimer Hazenberg). */
    static final class Monokai {
        private Monokai() {}
        static final String BG      = "#272822"; // background
        static final String TEXT    = "#f8f8f2"; // foreground
        static final String COMMENT = "#75715e"; // comments / dim
        static final String PINK    = "#f92672"; // error / accent
        static final String ORANGE  = "#fd971f"; // warning
        static final String YELLOW  = "#e6db74"; // strings
        static final String GREEN   = "#a6e22e"; // success / path
        static final String BLUE    = "#66d9ef"; // info / type
        static final String PURPLE  = "#ae81ff"; // command / constants
        static final String SELECTION = "#49483e"; // selection background

        // Surfaces step up from the background toward the selection colour; tints follow the
        // accent hues down to a low luminance.
        static final String SURFACE_BG        = "#32332b";
        static final String SURFACE_STRONG_BG = "#3b3b33";
        static final String TINT_SUCCESS      = "#2d3a16";
        static final String TINT_WARNING      = "#3c2d10";
        static final String TINT_ERROR        = "#3d1c2a";
    }

    /** Nord (Arctic Ice Studio). */
    static final class Nord {
        private Nord() {}
        // Polar Night
        static final String NIGHT0 = "#2e3440"; // background
        static final String NIGHT1 = "#3b4252"; // surface / selection
        static final String NIGHT2 = "#434c5e"; // borders
        static final String NIGHT3 = "#4c566a"; // muted / dim
        // Snow Storm
        static final String SNOW0  = "#d8dee9"; // body text
        static final String SNOW1  = "#e5e9f0";
        static final String SNOW2  = "#eceff4"; // bright text
        // Frost
        static final String FROST0 = "#8fbcbb"; // teal accent / path
        static final String FROST1 = "#88c0d0"; // cyan info / accent
        static final String FROST2 = "#81a1c1"; // blue
        static final String FROST3 = "#5e81ac"; // deep blue
        // Aurora
        static final String RED     = "#bf616a"; // error
        static final String ORANGE  = "#d08770"; // warning
        static final String YELLOW  = "#ebcb8b"; // string
        static final String GREEN   = "#a3be8c"; // success
        static final String PURPLE  = "#b48ead"; // command

        // Polar Night provides the surfaces: the ramp between NIGHT0 and NIGHT1 is Nord's own
        // raised layer. Tints sit inside the same ramp's hue family.
        static final String SURFACE_BG        = "#353d4d";
        static final String SURFACE_STRONG_BG = "#3b4252"; // NIGHT1
        static final String TINT_SUCCESS      = "#2e3a2c";
        static final String TINT_WARNING      = "#3a2e28";
        static final String TINT_ERROR        = "#382a30";
    }

    /** Dracula (Zeno Rocha). */
    static final class Dracula {
        private Dracula() {}
        static final String BG          = "#282a36"; // background
        static final String CURRENT     = "#44475a"; // current line / selection
        static final String TEXT        = "#f8f8f2"; // foreground
        static final String COMMENT     = "#6272a4"; // comments / dim
        static final String CYAN        = "#8be9fd"; // info
        static final String GREEN       = "#50fa7b"; // success
        static final String ORANGE      = "#ffb86c"; // warning
        static final String PINK        = "#ff79c6"; // command / accent
        static final String PURPLE      = "#bd93f9"; // accent
        static final String RED         = "#ff5555"; // error
        static final String YELLOW      = "#f1fa8c"; // string

        // The current-line colour is Dracula's own raised layer; the strong surface sits between
        // it and the selection. Tints are the accent hues at Dracula's low background luminance.
        static final String SURFACE_BG        = "#313441";
        static final String SURFACE_STRONG_BG = "#3a3d4b";
        static final String TINT_SUCCESS      = "#1e3a28";
        static final String TINT_WARNING      = "#3a2f1e";
        static final String TINT_ERROR        = "#3d2126";
    }

    /** Gruvbox Dark (Pavel Pertsev). */
    static final class GruvboxDark {
        private GruvboxDark() {}
        static final String BG      = "#282828"; // background (bg0)
        static final String BG_SOFT = "#3c3836"; // bg1 / surface / selection
        static final String GRAY    = "#928374"; // comments / dim
        static final String FG      = "#ebdbb2"; // foreground
        static final String FG_DIM  = "#a89984"; // muted foreground
        static final String RED     = "#fb4934"; // error
        static final String GREEN   = "#b8bb26"; // success
        static final String YELLOW  = "#fabd2f"; // warning / string
        static final String BLUE    = "#83a598"; // info
        static final String PURPLE  = "#d3869b"; // command
        static final String AQUA    = "#8ec07c"; // path
        static final String ORANGE  = "#fe8019"; // accent

        // bg1 is Gruvbox's own surface; the tints are the accent hues darkened into it.
        static final String SURFACE_BG        = "#32302e";
        static final String SURFACE_STRONG_BG = "#3c3836"; // BG_SOFT
        static final String TINT_SUCCESS      = "#34371b";
        static final String TINT_WARNING      = "#3d341a";
        static final String TINT_ERROR        = "#3f1e19";
    }

    /** One Dark (Atom). */
    static final class OneDark {
        private OneDark() {}
        static final String BG      = "#282c34"; // background
        static final String GUTTER  = "#5c6370"; // gutter / comments / dim
        static final String TEXT    = "#abb2bf"; // foreground
        static final String BRIGHT  = "#ffffff"; // bright highlight
        static final String SELECTION = "#3e4451"; // selection background
        static final String RED     = "#e06c75"; // error
        static final String GREEN   = "#98c379"; // success / path
        static final String YELLOW  = "#e5c07b"; // warning / string
        static final String BLUE    = "#61afef"; // info
        static final String MAGENTA = "#c678dd"; // command
        static final String CYAN    = "#56b6c2"; // accent

        // Surfaces climb from the background toward the selection colour; the tints follow the
        // accent hues down to One Dark's own low background luminance.
        static final String SURFACE_BG        = "#2e333d";
        static final String SURFACE_STRONG_BG = "#353b45";
        static final String TINT_SUCCESS      = "#243019";
        static final String TINT_WARNING      = "#332b18";
        static final String TINT_ERROR        = "#37222c";
    }

    /** Default — a clean modern truecolor blue (Turbo Vision-inspired). */
    static final class Default {
        private Default() {}
        static final String BG        = "#1f2430"; // deep slate window background
        static final String SURFACE   = "#2a3142"; // border / surface
        static final String DEEP_BLUE = "#002b6b"; // classic deep blue accent panel
        static final String TEXT      = "#e6e6e6"; // foreground
        static final String BRIGHT    = "#ffffff"; // bright text
        static final String GOLD      = "#ffcc66"; // primary accent
        static final String SELECTION = "#2f5fae"; // selection background
        static final String SUCCESS   = "#a3d977";
        static final String WARNING   = "#ffd866";
        static final String ERROR     = "#ff6b6b";
        static final String INFO      = "#6cb6ff";
        static final String COMMAND   = "#d3a0ff";
        static final String PATH      = "#a3d977";
        static final String STRING    = "#ffd866";
        static final String DIM       = "#7a8290"; // muted

        // The surface constant is the strong banner layer; the raised surface sits just above the
        // window background. Tints follow the semantic hues down into the deep slate.
        static final String SURFACE_BG        = "#262d3b";
        static final String SURFACE_STRONG_BG = "#2a3142"; // SURFACE
        static final String TINT_SUCCESS      = "#26341c";
        static final String TINT_WARNING      = "#3a3014";
        static final String TINT_ERROR        = "#3d2020";
    }
}
