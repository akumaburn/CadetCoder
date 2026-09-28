package com.eonmux.cadetcoder.ui;

import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a block of (ANSI-stripped) text as styled TamboUI {@link Line}s, applying a pragmatic
 * subset of Markdown so AI responses and tool output read richly in the interactive shell:
 *
 * <ul>
 *   <li><b>Headings</b> {@code #}/{@code ##}/{@code ###} — emphasised, accent-coloured.</li>
 *   <li><b>Fenced code blocks</b> ` ``` ` — boxed with an optional language label, content shown
 *       verbatim (no inline parsing), hard-wrapped so nothing is lost.</li>
 *   <li><b>Inline</b> {@code **bold**}, {@code *italic*}, {@code `code`} and {@code [text](url)}
 *       links (emitted as OSC-8 terminal hyperlinks).</li>
 *   <li><b>Lists</b> ({@code -}/{@code *}/{@code +} and {@code 1.}) with a bullet glyph and hanging
 *       indent, and <b>horizontal rules</b> ({@code ---}).</li>
 * </ul>
 *
 * <p>Lines that the CLI's own {@link ThemedOutputFormatter} produced (recognised by
 * {@link OutputLineStyler} markers -- either vocabulary: {@code ✓ ⚠ ✗ ℹ ▎ ▸} or the bracketed
 * fallbacks {@code [OK] [WARN] [ERR] [i] === … === -- … --}) are kept
 * on their semantic colour rather than reinterpreted as Markdown, so structured tool output is
 * unchanged. Inline emphasis uses conservative <em>flanking</em> rules so identifiers and paths like
 * {@code my_file_name} or {@code a*b} are not accidentally italicised. Glyphs degrade to ASCII via
 * {@link Glyphs}. The renderer is pure and side-effect free.</p>
 */
public final class MarkdownRenderer {

    private static final Pattern ORDERED_ITEM = Pattern.compile("^(\\s*)(\\d{1,9})[.)]\\s+(.*)$");
    private static final Pattern UNORDERED_ITEM = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");

    private final TuiTheme theme;
    private final Glyphs   glyphs;

    private final Style normal;
    private final Style h1;
    private final Style h2;
    private final Style h3;
    private final Style codeStyle;
    private final Style inlineCode;
    private final Style linkStyle;
    private final Style dim;
    private final Style bulletStyle;

    public MarkdownRenderer(TuiTheme theme, Glyphs glyphs) {
        this.theme  = theme;
        this.glyphs = glyphs == null ? Glyphs.ASCII : glyphs;

        this.normal      = nn(theme == null ? null : theme.getTextNormal());
        this.h1          = nn(theme == null ? null : theme.getAccent1()).bold();
        this.h2          = nn(theme == null ? null : theme.getAccent2()).bold();
        this.h3          = nn(theme == null ? null : theme.getInfo()).bold();
        this.codeStyle   = nn(theme == null ? null : theme.getString());
        this.inlineCode  = nn(theme == null ? null : theme.getString());
        this.linkStyle   = nn(theme == null ? null : theme.getAccent2()).underlined();
        this.dim         = nn(theme == null ? null : theme.getDim());
        this.bulletStyle = nn(theme == null ? null : theme.getAccent1());
    }

    /** Render {@code rawLines} as styled, width-wrapped {@link Line}s. */
    public List<Line> render(List<String> rawLines, int width) {
        List<Line> out = new ArrayList<>();
        if (rawLines == null || width <= 0) {
            return out;
        }
        // Prose is set to a readable measure; a code block keeps the full width. See ProseMeasure
        // for why, and for what the distinction is between the two.
        int prose = ProseMeasure.fit(width);
        int i = 0;
        while (i < rawLines.size()) {
            String raw = rawLines.get(i);
            String stripped = raw == null ? "" : raw.stripLeading();

            // Fenced code block — consume until the closing fence (or end of input).
            if (isFence(stripped)) {
                i = renderCodeBlock(rawLines, i, width, out);
                continue;
            }

            // Preserve the CLI's own structured/semantic lines exactly as before.
            OutputLineStyler.Kind kind = OutputLineStyler.classify(raw);
            if (kind != OutputLineStyler.Kind.NORMAL) {
                Style st = OutputLineStyler.styleFor(kind, theme);
                for (String frag : wrapMarked(raw == null ? "" : raw, prose)) {
                    out.add(Line.styled(frag, st));
                }
                i++;
                continue;
            }

            if (isThematicBreak(stripped)) {
                // A break is separation, and separation is what an empty line already is. Drawing a
                // rule for it puts characters into every copied transcript that carry no meaning
                // once the text leaves the terminal.
                out.add(Line.empty());
                i++;
                continue;
            }

            Matcher h = HEADING.matcher(stripped);
            if (h.matches()) {
                renderHeading(h.group(1).length(), h.group(2), prose, out);
                i++;
                continue;
            }

            Matcher ul = UNORDERED_ITEM.matcher(raw == null ? "" : raw);
            if (ul.matches()) {
                renderListItem(ul.group(1), glyphs.bullet() + " ", ul.group(2), prose, out);
                i++;
                continue;
            }
            Matcher ol = ORDERED_ITEM.matcher(raw == null ? "" : raw);
            if (ol.matches()) {
                renderListItem(ol.group(1), ol.group(2) + ". ", ol.group(3), prose, out);
                i++;
                continue;
            }

            if (stripped.isEmpty()) {
                out.add(Line.empty());
                i++;
                continue;
            }

            // Paragraph text with inline formatting, keeping whatever indent the line arrived with.
            //
            // wrapToks drops whitespace at the start of a line, which is right for a line the WRAP
            // created -- it should not begin with the space it broke on -- but wrong for the indent
            // the source line actually had. Losing it turned every aligned block the CLI prints,
            // continuation lines of printInfo and indented lists alike, into ragged prose.
            String indent = leadingIndent(raw);
            List<Span> firstPrefix = new ArrayList<>();
            if (!indent.isEmpty()) {
                firstPrefix.add(Span.styled(indent, normal));
            }
            wrapToks(inline(raw == null ? "" : raw.stripLeading()), prose, firstPrefix, indent, out);
            i++;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Block renderers
    // ------------------------------------------------------------------

    private int renderCodeBlock(List<String> rawLines, int start, int width, List<Line> out) {
        String fence = rawLines.get(start).stripLeading();
        String lang  = fence.length() > 3 ? fence.substring(3).trim() : "";
        String label = lang.isEmpty() ? "code" : lang;

        // The language, dim, on its own line. The rules that used to flank it delimited the block
        // on screen and then travelled into anything copied out of it; a blank line above and below
        // separates it just as clearly and copies as nothing.
        out.add(Line.empty());
        out.add(Line.styled(clip(label, width), dim));

        // No per-line gutter. Marking every code row with "│ " made the block's extent obvious on
        // screen but put two characters in front of every line of code anyone copied out of the
        // transcript -- so the code had to be cleaned up before it could be used. The rules above and
        // below delimit the block, and the code keeps its own colour.
        int i = start + 1;
        while (i < rawLines.size() && !isFence(rawLines.get(i).stripLeading())) {
            String codeLine = rawLines.get(i) == null ? "" : stripTabs(rawLines.get(i));
            for (String frag : hardWrap(codeLine, Math.max(1, width))) {
                out.add(Line.styled(frag, codeStyle));
            }
            i++;
        }
        if (i < rawLines.size()) {
            i++; // consume the closing fence
        }
        out.add(Line.empty());
        return i;
    }

    private void renderHeading(int level, String content, int width, List<Line> out) {
        Style style = level <= 1 ? h1 : (level == 2 ? h2 : h3);
        // The bar goes on EVERY level. It used to be given to levels 2 and 3 but withheld from
        // level 1, so a model's "# Title" sat flush at column 0 while its subordinate "## Section"
        // was indented behind a bar -- the opposite of the nesting it was meant to show. It is also
        // the marker printHeader puts on every header the CLI itself emits.
        String prefix = glyphs.isUnicode() ? "▎ " : "";
        List<Span> first = new ArrayList<>();
        if (!prefix.isEmpty()) {
            first.add(Span.styled(prefix, style));
        }
        // Headings are emphasised wholesale; inline markers inside a heading are stripped to text.
        List<Tok> toks = inline(content);
        List<Tok> bolded = new ArrayList<>(toks.size());
        for (Tok t : toks) {
            bolded.add(new Tok(t.text, style, t.href));
        }
        wrapToks(bolded, width, first, prefix.isEmpty() ? "" : "  ", out);
    }

    private void renderListItem(String indent, String marker, String content, int width, List<Line> out) {
        String safeIndent = indent == null ? "" : indent;
        List<Span> first = new ArrayList<>();
        if (!safeIndent.isEmpty()) {
            first.add(Span.styled(safeIndent, normal));
        }
        first.add(Span.styled(marker, bulletStyle));
        String cont = safeIndent + " ".repeat(marker.length());
        wrapToks(inline(content), width, first, cont, out);
    }

    // ------------------------------------------------------------------
    // Inline parsing -> styled tokens (flanking-aware, code/path safe)
    // ------------------------------------------------------------------

    /** A styled run of text; {@code href != null} marks a hyperlink. */
    private final class Tok {
        final String text;
        final Style  style;
        final String href;

        Tok(String text, Style style, String href) {
            this.text  = text;
            this.style = style;
            this.href  = href;
        }
    }

    private List<Tok> inline(String s) {
        List<Tok> toks = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            toks.add(new Tok(s == null ? "" : s, normal, null));
            return toks;
        }
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);

            if (c == '\\' && i + 1 < n && isEscapable(s.charAt(i + 1))) {
                buf.append(s.charAt(i + 1));
                i += 2;
                continue;
            }

            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i) {
                    flush(buf, toks);
                    toks.add(new Tok(s.substring(i + 1, j), inlineCode, null));
                    i = j + 1;
                    continue;
                }
            } else if (c == '[') {
                int close = s.indexOf(']', i + 1);
                if (close > i && close + 1 < n && s.charAt(close + 1) == '(') {
                    int paren = s.indexOf(')', close + 2);
                    if (paren > close) {
                        flush(buf, toks);
                        String text = s.substring(i + 1, close);
                        String url  = s.substring(close + 2, paren).trim();
                        toks.add(new Tok(text, linkStyle, url.isEmpty() ? null : url));
                        i = paren + 1;
                        continue;
                    }
                }
            } else if (c == '*' || c == '_') {
                int consumed = tryEmphasis(s, i, buf, toks);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }

            buf.append(c);
            i++;
        }
        flush(buf, toks);
        return toks;
    }

    /**
     * Try to parse emphasis at {@code i}. Returns the number of characters consumed, or 0 if this is
     * not a valid (left/right-flanked) emphasis run — in which case the caller treats the marker as
     * literal text, so identifiers like {@code my_file_name} and expressions like {@code a*b} are
     * left intact.
     */
    private int tryEmphasis(String s, int i, StringBuilder buf, List<Tok> toks) {
        char   c     = s.charAt(i);
        int    n     = s.length();
        boolean dbl  = i + 1 < n && s.charAt(i + 1) == c;
        int    mlen  = dbl ? 2 : 1;
        char   prev  = i == 0 ? ' ' : s.charAt(i - 1);

        // Opening marker must be left-flanking: preceded by a boundary, followed by a non-space.
        if (Character.isLetterOrDigit(prev)) {
            return 0;
        }
        int contentStart = i + mlen;
        if (contentStart >= n || s.charAt(contentStart) == ' ') {
            return 0;
        }
        String marker = dbl ? (c == '*' ? "**" : "__") : String.valueOf(c);
        int    close  = s.indexOf(marker, contentStart);
        while (close > contentStart) {
            char before = s.charAt(close - 1);
            char after  = close + mlen < n ? s.charAt(close + mlen) : ' ';
            // Closing marker must be right-flanking: preceded by non-space, followed by a boundary.
            if (before != ' ' && !Character.isLetterOrDigit(after)) {
                flush(buf, toks);
                Style style = dbl ? normal.bold() : normal.italic();
                toks.add(new Tok(s.substring(contentStart, close), style, null));
                return (close + mlen) - i;
            }
            close = s.indexOf(marker, close + 1);
        }
        return 0;
    }

    private void flush(StringBuilder buf, List<Tok> toks) {
        if (buf.length() > 0) {
            toks.add(new Tok(buf.toString(), normal, null));
            buf.setLength(0);
        }
    }

    // ------------------------------------------------------------------
    // Span-aware word wrapping
    // ------------------------------------------------------------------

    private void wrapToks(List<Tok> toks, int width, List<Span> firstPrefix, String contIndent,
                          List<Line> out) {
        int prefixW = 0;
        for (Span sp : firstPrefix) {
            prefixW += sp.width();
        }
        List<Span> cur   = new ArrayList<>(firstPrefix);
        int        avail = Math.max(1, width - prefixW);
        int        used  = 0;
        boolean    lineStart = true;

        for (Tok tok : toks) {
            for (Piece piece : splitPieces(tok)) {
                if (piece.space) {
                    if (lineStart) {
                        continue; // drop leading whitespace on a wrapped line
                    }
                    if (used + 1 > avail) {
                        out.add(Line.from(cur));
                        cur = contLine(contIndent);
                        avail = Math.max(1, width - contIndent.length());
                        used = 0;
                        lineStart = true;
                        continue;
                    }
                    cur.add(span(" ", normal, null));
                    used++;
                    continue;
                }

                String word = piece.text;
                if (word.length() > avail && lineStart) {
                    // A single token longer than the line: hard-split across lines.
                    int pos = 0;
                    while (pos < word.length()) {
                        int take = Math.min(avail, word.length() - pos);
                        cur.add(span(word.substring(pos, pos + take), tok.style, tok.href));
                        pos += take;
                        used += take;
                        lineStart = false;
                        if (pos < word.length()) {
                            out.add(Line.from(cur));
                            cur = contLine(contIndent);
                            avail = Math.max(1, width - contIndent.length());
                            used = 0;
                            lineStart = true;
                        }
                    }
                    continue;
                }
                if (used + word.length() > avail) {
                    out.add(Line.from(cur));
                    cur = contLine(contIndent);
                    avail = Math.max(1, width - contIndent.length());
                    used = 0;
                    lineStart = true;
                }
                cur.add(span(word, tok.style, tok.href));
                used += word.length();
                lineStart = false;
            }
        }
        out.add(Line.from(cur));
    }

    private List<Span> contLine(String contIndent) {
        List<Span> spans = new ArrayList<>();
        if (!contIndent.isEmpty()) {
            spans.add(Span.styled(contIndent, normal));
        }
        return spans;
    }

    private static Span span(String text, Style style, String href) {
        Span s = Span.styled(text, style);
        return href == null ? s : s.hyperlink(href);
    }

    /** A wrap atom: either a single space or a maximal run of non-space characters. */
    private static final class Piece {
        final String  text;
        final boolean space;

        Piece(String text, boolean space) {
            this.text  = text;
            this.space = space;
        }
    }

    private static List<Piece> splitPieces(Tok tok) {
        List<Piece> pieces = new ArrayList<>();
        String s = tok.text;
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (s.charAt(i) == ' ') {
                pieces.add(new Piece(" ", true));
                i++;
            } else {
                int j = i;
                while (j < n && s.charAt(j) != ' ') {
                    j++;
                }
                pieces.add(new Piece(s.substring(i, j), false));
                i = j;
            }
        }
        return pieces;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static boolean isFence(String stripped) {
        return stripped.startsWith("```") || stripped.startsWith("~~~");
    }

    private static boolean isThematicBreak(String stripped) {
        String t = stripped.replace(" ", "");
        if (t.length() < 3) {
            return false;
        }
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEscapable(char c) {
        return "\\`*_{}[]()#+-.!~>".indexOf(c) >= 0;
    }

    private static String stripTabs(String s) {
        return s.replace("\t", "    ");
    }

    private static List<String> hardWrap(String line, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            return out;
        }
        if (line.isEmpty()) {
            out.add("");
            return out;
        }
        for (int i = 0; i < line.length(); i += width) {
            out.add(line.substring(i, Math.min(i + width, line.length())));
        }
        return out;
    }

    /**
     * Widest opening marker a continuation line is indented under.
     *
     * <p>{@code "[WARN] "} is the longest of them. Anything wider than this is a sentence that
     * happens to start with a short word, not a marker, and indenting under it would be arbitrary.</p>
     */
    private static final int WIDEST_MARKER = 8;

    /**
     * Wraps a line the CLI already marked, hanging its continuations under the marker.
     *
     * <p>Without the hang, the second half of a wrapped message starts in column zero, where the
     * next message's marker is about to go. Two unrelated lines then read as one, and a listing that
     * was aligned on screen loses its column the moment a row is long enough to break.</p>
     *
     * @param line  the marked line, with its marker still on it
     * @param width the columns available
     * @return the pieces, in order
     */
    private static List<String> wrapMarked(String line, int width) {
        int marker = markerWidth(line);
        if (marker <= 0 || marker >= width) {
            return wrapPlain(line, width);
        }
        List<String> body = wrapPlain(line.substring(marker), width - marker);
        List<String> out  = new ArrayList<>(body.size());
        String       hang = " ".repeat(marker);
        for (int i = 0; i < body.size(); i++) {
            out.add((i == 0 ? line.substring(0, marker) : hang) + body.get(i));
        }
        return out;
    }

    /**
     * @param line a line that classified as something other than {@code NORMAL}
     * @return the columns its opening marker occupies, the space after it included, or {@code 0}
     *         when nothing on it reads as a marker
     */
    private static int markerWidth(String line) {
        int lead = 0;
        while (lead < line.length() && line.charAt(lead) == ' ') {
            lead++;
        }
        int space = line.indexOf(' ', lead);
        if (space < 0) {
            return 0;
        }
        int width = space + 1;
        return width <= WIDEST_MARKER ? width : 0;
    }

    /** Word-aware wrap used for the CLI semantic-marker passthrough (mirrors the shell's wrapping). */
    private static List<String> wrapPlain(String line, int width) {
        List<String> out = new ArrayList<>();
        if (width <= 0) {
            return out;
        }
        if (line.isEmpty()) {
            out.add("");
            return out;
        }
        int i = 0;
        while (i < line.length()) {
            int end = Math.min(i + width, line.length());
            if (end < line.length()) {
                int space = line.lastIndexOf(' ', end - 1);
                if (space > i) {
                    end = space + 1;
                }
            }
            // stripTrailing: `end` is one PAST the space the line broke on, so the fragment would
            // otherwise carry that space to the end of its rendered row -- invisible on screen, and
            // dragged along by the shell's select-and-copy.
            out.add(line.substring(i, end).stripTrailing());
            i = end;
        }
        return out;
    }

    /** @return the run of spaces and tabs a line begins with, as spaces */
    private static String leadingIndent(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < raw.length() && (raw.charAt(end) == ' ' || raw.charAt(end) == '\t')) {
            end++;
        }
        return end == 0 ? "" : stripTabs(raw.substring(0, end));
    }

    private static String clip(String s, int width) {
        return s.length() <= width ? s : s.substring(0, Math.max(0, width));
    }

    private static Style nn(Style s) {
        return s == null ? Style.EMPTY : s;
    }
}
