package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.security.SecurityValidator;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The project files a request names in its own words.
 *
 * <h2>Why a request is read for filenames at all</h2>
 *
 * <p>The context an edit is given comes out of the index, and that search matches on what is in a
 * file rather than on what it is called -- so "rewrite the install section of CONTRIBUTING.md" is
 * not certain to return CONTRIBUTING.md, and the model is then asked to edit a file it was never
 * shown. {@code edit} used to cover the one case someone had hit, with {@code README.md} written
 * into an {@code if}: that file was loaded whole and every other file in the project got whatever
 * the search happened to return. Reading the names out of the request treats them alike.</p>
 *
 * <h2>What a name has to satisfy</h2>
 *
 * <p>It has to resolve inside the project, exist, be a regular file, be text, be no larger than the
 * index's own ceiling, and not be a credential file. Containment is unconditional here, unlike
 * {@link SecurityValidator#isFileAccessAllowed}, whose project boundary a configuration can switch
 * off: this is free text on its way to a model provider, and "summarise ~/.aws/credentials" has to
 * name nothing.</p>
 *
 * <h2>Why a bare name is not hunted down the tree</h2>
 *
 * <p>A name that does not resolve as it was written is left alone. Most projects hold several
 * {@code Main.java}, and picking one of them and presenting it as the file the request meant is a
 * guess handed to the model as fact. Finding a file by what is in it is what the index is for.</p>
 */
public final class NamedFiles {

    /**
     * What a filename looks like inside a sentence.
     *
     * <p>A path or a bare name ending in an extension, and not preceded by anything that would make
     * it the tail of a longer word. Whether the thing named is really a file is not decided here:
     * "e.g" matches, resolves to nothing, and is dropped, which is the right answer for it.</p>
     */
    private static final Pattern A_NAME = Pattern.compile(
            "(?<![\\w./\\\\-])[\\w.-]*(?:[/\\\\][\\w.-]+)*\\.[A-Za-z][A-Za-z0-9]{0,15}(?![\\w])");

    private NamedFiles() {
    }

    /**
     * Every project file the request names, in the order it names them.
     *
     * @param request     what was asked for, in the user's own words
     * @param projectRoot the directory a name resolves against, and the boundary it may not cross
     * @return absolute paths that may be put in front of a model, without repeats
     */
    public static List<Path> in(String request, Path projectRoot) {
        if (request == null || projectRoot == null) {
            return List.of();
        }
        Path              root        = projectRoot.toAbsolutePath().normalize();
        SecurityValidator credentials = new SecurityValidator();
        Set<Path>         named       = new LinkedHashSet<>();
        Matcher           names       = A_NAME.matcher(request);
        int               most        = most();
        while (named.size() < most && names.find()) {
            Path file = ProjectFile.inside(names.group(), root);
            if (file != null && ProjectFile.mayBeShown(file, credentials)) {
                named.add(file);
            }
        }
        return List.copyOf(named);
    }

    /**
     * How many named files one request may pull in.
     *
     * <p>The knob that bounds how many files a context search returns bounds this too. Both answer
     * the same question -- how many whole files one prompt may carry -- and a second number of its
     * own would be a second answer to it.</p>
     */
    private static int most() {
        return Math.max(0, ConfigManager.getInstance().getConfig().getContext().getMaxFiles());
    }
}
