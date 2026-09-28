package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.util.ProjectTreeWalk;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finding the file a model named without looking.
 *
 * <h2>Why a guess is tried before a walk</h2>
 *
 * <p>A model asked to read {@code UserService.java} usually names the file and not its path, and the
 * path it belongs at follows from the project's shape far more cheaply than from a tree walk. The
 * conventional locations are tried first, then the package a name of that shape conventionally lives
 * in, and the walk is what is left when neither answers.</p>
 *
 * <h2>Why the walk's result is cached, misses included</h2>
 *
 * <p>The walk covers the project, and a run that keeps asking for a file that is not there would
 * repeat it every time. Caching the absence is the point: the second question about a missing file
 * costs nothing. The cache is bounded and evicts by least-recent use, so a long session cannot grow
 * it without limit, and it is shared because the project does not change per command instance.</p>
 */
final class ProjectFileSearch {

    /** Entries retained before the least recently used is dropped. */
    private static final int CACHE_MAX_SIZE = 100;

    /**
     * How long "the project has no file of that name" is believed before the walk is repeated.
     *
     * <p>A miss was cached for the life of the process, which is only safe for a project that does
     * not change -- and this tool writes files. An agent that asked for a file at one step, created
     * it at another and named it again later was told the project has no such file, from an answer
     * recorded before the file existed.</p>
     *
     * <p>Long enough that a burst of questions about the same missing file still costs one walk,
     * which is what the cache is for; short enough that nothing the run itself created stays
     * invisible. A hit needs no window: whether the file is still where it was found is one
     * question to the filesystem, so it is asked instead of assumed.</p>
     */
    static final long MISS_TTL_MILLIS = 5_000L;

    /** What a walk found, and when. A {@code path} of {@code null} is a remembered absence. */
    private record Answer(String path, long walkedAt) { }

    /** Shared, and synchronized because commands run on more than one thread. */
    private static final Map<String, Answer> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, Answer>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
                    return size() > CACHE_MAX_SIZE;
                }
            });

    /** Forgets every remembered walk. For tests, which must not inherit another's project. */
    static void forgetWhatWasFound() {
        CACHE.clear();
    }

    private final LoggingCommandSupport log;

    /**
     * @param log where to record what was looked for and where it turned up
     */
    ProjectFileSearch(LoggingCommandSupport log) {
        this.log = log;
    }

    /**
     * Walks the project for a file of exactly this name.
     *
     * @param fileName the name to look for
     * @return where it is, or {@code null} when the project does not have one
     */
    String locate(String fileName) {
        return locate(fileName, Paths.get("."), System.currentTimeMillis());
    }

    /**
     * Walks {@code start} for a file of exactly this name, answering from the last walk when that
     * answer can still be true.
     *
     * @param fileName the name to look for
     * @param start    the directory to walk
     * @param now      the current time, for deciding whether a remembered absence has expired
     * @return where it is, or {@code null} when that directory does not have one
     */
    String locate(String fileName, Path start, long now) {
        String key       = start + "|" + fileName;
        Answer remembered = CACHE.get(key);
        if (remembered != null && stillTrue(remembered, now)) {
            log.logDebug("File Search", "Cache hit for: " + fileName);
            return remembered.path();
        }

        try {
            log.logDebug("File Search", "Searching for file: " + fileName);
            AtomicReference<String> found = new AtomicReference<>();

            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return ProjectTreeWalk.isPruned(start, dir)
                           ? FileVisitResult.SKIP_SUBTREE
                           : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().equals(fileName)) {
                        found.set(file.toString());
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            String result = found.get();
            CACHE.put(key, new Answer(result, now));
            log.logDebug("File Search", result != null
                                        ? "Found file at: " + result
                                        : "File not found: " + fileName);
            return result;
        } catch (Exception e) {
            log.logWarning("File Search",
                    "Error searching for file: " + fileName + " - " + e.getMessage());
            // Cache the failure too, so a broken search is not repeated for every mention.
            CACHE.put(key, new Answer(null, now));
            return null;
        }
    }

    /**
     * Whether a remembered walk can still be the answer.
     *
     * @param remembered what the last walk found, and when
     * @param now        the current time
     * @return true when a found file is still at that path, or an absence is recent enough to trust
     */
    private static boolean stillTrue(Answer remembered, long now) {
        if (remembered.path() == null) {
            return now - remembered.walkedAt() < MISS_TTL_MILLIS;
        }
        return Files.exists(Paths.get(remembered.path()));
    }

    /**
     * Where a file of this name most likely lives.
     *
     * @param fileName the bare filename
     * @return a path that exists, the walk's answer, or the name itself when nothing is found -- so
     *         the command that runs next fails with a specific error rather than a vague one
     */
    String likelyPath(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }

        try {
            if (fileName.endsWith(".java")) {
                return javaPath(fileName);
            }
            String byExtension = conventionalPath(fileName);
            if (byExtension != null) {
                return byExtension;
            }

            String searched = locate(fileName);
            if (searched != null) {
                log.logDebug("Path Resolution", "Found file by searching: " + searched);
                return searched;
            }

            log.logWarning("Path Resolution",
                    "Could not resolve path for: " + fileName + ", returning as is");
            return fileName;
        } catch (Exception e) {
            log.logWarning("Path Resolution",
                    "Error resolving path for " + fileName + ": " + e.getMessage());
            return fileName;
        }
    }

    /** Source roots, then the package a name of this shape conventionally lives in, then the walk. */
    private String javaPath(String fileName) {
        String direct = firstThatExists("Found file at: ",
                Paths.get("src/main/java").resolve(fileName),
                Paths.get("src/test/java").resolve(fileName),
                Paths.get("src/main/kotlin").resolve(fileName),
                Paths.get("src/test/kotlin").resolve(fileName),
                Paths.get("app/src/main/java").resolve(fileName),   // Android
                Paths.get("app/src/test/java").resolve(fileName),   // Android
                Paths.get(".").resolve(fileName));
        if (direct != null) {
            return direct;
        }

        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        for (String pkg : PACKAGE_NAMES) {
            String lower = baseName.toLowerCase();
            if (!lower.contains(pkg) && !lower.endsWith(pkg)) {
                continue;
            }
            String inPackage = firstThatExists("Found file in package " + pkg + ": ",
                    Paths.get("src/main/java").resolve(pkg).resolve(fileName),
                    Paths.get("src/test/java").resolve(pkg).resolve(fileName),
                    Paths.get("src/main/kotlin").resolve(pkg).resolve(fileName),
                    Paths.get("src/test/kotlin").resolve(pkg).resolve(fileName));
            if (inPackage != null) {
                return inPackage;
            }
        }

        String searched = locate(fileName);
        if (searched != null) {
            log.logDebug("Path Resolution", "Found file by searching: " + searched);
            return searched;
        }
        log.logWarning("Path Resolution",
                "Could not resolve path for: " + fileName + ", returning as is");
        return fileName;
    }

    /** Package names a class is conventionally filed under, matched against its own name. */
    private static final String[] PACKAGE_NAMES = {
            "service", "services",
            "controller", "controllers",
            "model", "models", "entity", "entities",
            "repository", "repositories", "dao",
            "util", "utils", "helper", "helpers",
            "config", "configuration",
            "exception", "exceptions"
    };

    /** Where a file of a non-source kind conventionally lives, or {@code null} for kinds with none. */
    private String conventionalPath(String fileName) {
        if (endsWithAny(fileName, ".properties", ".xml", ".yml", ".yaml", ".json", ".conf")) {
            return firstThatExists("Found config file at: ",
                    Paths.get("src/main/resources").resolve(fileName),
                    Paths.get("src/test/resources").resolve(fileName),
                    Paths.get("config").resolve(fileName),
                    Paths.get(".").resolve(fileName));
        }
        if (endsWithAny(fileName, ".html", ".css", ".js", ".jsx", ".ts", ".tsx")) {
            return firstThatExists("Found web file at: ",
                    Paths.get("src/main/resources/static").resolve(fileName),
                    Paths.get("src/main/resources/public").resolve(fileName),
                    Paths.get("src/main/resources/templates").resolve(fileName),
                    Paths.get("public").resolve(fileName),
                    Paths.get("static").resolve(fileName),
                    Paths.get("web").resolve(fileName),
                    Paths.get("webapp").resolve(fileName),
                    Paths.get(".").resolve(fileName));
        }
        if (endsWithAny(fileName, ".md", ".txt", ".rst")) {
            return firstThatExists("Found documentation file at: ",
                    Paths.get("docs").resolve(fileName),
                    Paths.get("doc").resolve(fileName),
                    Paths.get(".").resolve(fileName));
        }
        return null;
    }

    /** The first of these that is really there, reported under {@code found}. */
    private String firstThatExists(String found, Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                log.logDebug("Path Resolution", found + candidate);
                return candidate.toString();
            }
        }
        return null;
    }

    private static boolean endsWithAny(String fileName, String... extensions) {
        for (String extension : extensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
