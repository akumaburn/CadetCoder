package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.security.SecurityValidator;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole project files one request puts in front of the model.
 *
 * <h2>Two sources, one answer</h2>
 *
 * <p>A file gets into a prompt whole for one of two reasons: the request named it, or the project
 * said it always should. The second is {@code context.priorityFiles}, and it exists because an
 * index search matches on what is inside a file rather than on what a project has decided matters:
 * a coding-conventions document is read by nobody's search and needed by every edit.</p>
 *
 * <p>They are combined here rather than at each caller so that the ceiling on how many whole files
 * a prompt may carry is applied once, to the total. Two callers each honouring it separately would
 * agree on the number and still send twice it.</p>
 *
 * <h2>Order</h2>
 *
 * <p>Configured files first. The prompt is trimmed from the end when it does not fit the token
 * budget, so what goes in first is what survives, and a project that has said which files matter
 * has said which ones should.</p>
 */
public final class ContextFiles {

    private ContextFiles() {
    }

    /**
     * Every project file to be put in front of the model whole, in the order they should appear.
     *
     * @param request     what was asked for, in the user's own words; may be {@code null}
     * @param projectRoot the directory names resolve against, and the boundary they may not cross
     * @return absolute paths that may be shown to a model provider, without repeats
     */
    public static List<Path> forRequest(String request, Path projectRoot) {
        if (projectRoot == null) {
            return List.of();
        }
        Path      root    = projectRoot.toAbsolutePath().normalize();
        int       ceiling = ceiling();
        Set<Path> files   = new LinkedHashSet<>(always(root, ceiling));

        for (Path named : NamedFiles.in(request, root)) {
            if (files.size() >= ceiling) {
                break;
            }
            files.add(named);
        }
        return List.copyOf(files);
    }

    /**
     * The configured files, in configured order, that are really there and really showable.
     *
     * <p>A name that resolves to nothing is passed over without a word. The setting is a standing
     * instruction rather than a request, so it outlives the files it names -- warning about a
     * deleted one on every single edit would be the same line forever.</p>
     */
    private static List<Path> always(Path root, int ceiling) {
        String[] configured =
                ConfigManager.getInstance().getConfig().getContext().getPriorityFiles();
        SecurityValidator credentials = new SecurityValidator();
        Set<Path>         files       = new LinkedHashSet<>();

        for (String name : configured) {
            if (files.size() >= ceiling) {
                break;
            }
            Path file = ProjectFile.inside(name, root);
            if (file != null && ProjectFile.mayBeShown(file, credentials)) {
                files.add(file);
            }
        }
        return List.copyOf(files);
    }

    /**
     * How many whole files one prompt may carry.
     *
     * <p>The knob that bounds a context search bounds this too: all of them answer the same
     * question, and a second number of its own would be a second answer to it.</p>
     */
    private static int ceiling() {
        return Math.max(0, ConfigManager.getInstance().getConfig().getContext().getMaxFiles());
    }
}
