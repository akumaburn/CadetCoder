package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.security.ReadOnlyGuard;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;

public class AutoCommitScheduler {

    /** Directories whose contents this tool writes itself, so a change in them means nothing. */
    private static final Set<String> NOT_THE_PROJECTS = Set.of(".git", ".cadet");

    private final    ScheduledExecutorService scheduler           = Executors.newScheduledThreadPool(1);
    private final    GitIntegration           gitIntegration;
    private final    Debouncer                debouncer           = new Debouncer(5000); // 5 seconds debounce
    private          WatchService             watchService;
    private          Thread                   watchThread;
    private volatile boolean                  watchServiceRunning = false;

    public AutoCommitScheduler(GitIntegration gitIntegration) {
        this.gitIntegration = gitIntegration;
    }

    public void start() {
        Configuration.GitConfig gitConfig = ConfigManager.getInstance().getConfig().getGit();

        if (gitConfig.isAutoCommitEnabled()) {
            // Auto-commit writes to the repository on a timer, which a read-only run must not do.
            // Refused here rather than at each tick, so the reason is stated once instead of
            // becoming an error every hour.
            if (ReadOnlyGuard.isEnabled()) {
                OutputFormatter.printInfo("Auto-commit is off for this run: read-only mode is enabled.");
                return;
            }

            // The trigger comes from the config file, so it can be absent as well as unrecognised;
            // both are reported rather than thrown.
            String trigger = gitConfig.getCommitTrigger() == null
                             ? ""
                             : gitConfig.getCommitTrigger().toLowerCase();
            switch (trigger) {
                case "oninterval":
                    int intervalMinutes = gitConfig.getAutoCommitIntervalMinutes();
                    if (intervalMinutes < 1) {
                        OutputFormatter.printError(
                                "git.autoCommitIntervalMinutes must be at least 1; auto-commit is off.");
                        return;
                    }
                    scheduler.scheduleAtFixedRate(
                            () -> commitWhatChanged(gitConfig.getCommitMessageTemplate()),
                            intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
                    OutputFormatter.printSuccess("Auto-commit scheduler started with interval: " +
                                                 intervalMinutes +
                                                 " minutes.");
                    break;

                case "onchange":
                    try {
                        startWatchService();
                        OutputFormatter.printSuccess("Auto-commit scheduler started with 'onChange' trigger.");
                    } catch (IOException e) {
                        OutputFormatter.printError("Failed to start WatchService for 'onChange' trigger: " +
                                                   e.getMessage());
                    }
                    break;

                default:
                    OutputFormatter.printError(
                            "Unknown git.commitTrigger value: " + gitConfig.getCommitTrigger()
                            + ". Expected \"onChange\" or \"onInterval\".");
            }
        }
    }

    /**
     * Stages what the working tree has changed and commits it, if anything has.
     *
     * <h2>Why this stages rather than just committing</h2>
     *
     * <p>A commit records what is in the index, and nothing here puts anything there: the person
     * who would have chosen what to include is the whole reason this scheduler exists. Committing
     * without staging wrote an empty commit every interval, or on every save, while the edits it
     * was supposed to be preserving stayed in the working tree -- a history full of entries that
     * record nothing, and no backup of the one thing it was for.</p>
     *
     * @param template the configured message template
     */
    private void commitWhatChanged(String template) {
        try {
            gitIntegration.commitEverything(CommitMessage.render(template, null));
        } catch (RuntimeException | GitAPIException e) {
            OutputFormatter.printError("Auto-commit failed: " + e.getMessage());
        }
    }

    private void startWatchService() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        Path projectDir = Paths.get(System.getProperty("user.dir"));
        registerAll(projectDir);

        watchThread = new Thread(() -> {
            watchServiceRunning = true;
            while (watchServiceRunning) {
                WatchKey key;
                try {
                    key = watchService.take(); // Blocks until an event occurs
                } catch (InterruptedException e) {
                    watchServiceRunning = false;
                    Thread.currentThread().interrupt();
                    return;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    // We only care about file modifications, creations, and deletions
                    if (kind == StandardWatchEventKinds.ENTRY_MODIFY ||
                        kind == StandardWatchEventKinds.ENTRY_CREATE ||
                        kind == StandardWatchEventKinds.ENTRY_DELETE) {

                        // Nothing is filtered by name here. What must not trigger a commit is
                        // everything under .git and .cadet, and registerAll never registers those, so
                        // no event from them can arrive. The name test that used to stand here could
                        // not have done that job anyway: event.context() is the file name relative to
                        // the watched directory, so it never contains ".git" for a file inside .git --
                        // while .gitignore and .gitattributes, which ARE the project's files, matched
                        // it and were the only things it ever discarded.
                        debouncer.debounce(() -> commitWhatChanged(
                                ConfigManager.getInstance().getConfig().getGit()
                                             .getCommitMessageTemplate()));
                    }
                }

                boolean valid = key.reset(); // Reset the key to receive further events
                if (!valid) {
                    OutputFormatter.printWarning("WatchKey no longer valid, stopping WatchService.");
                    watchServiceRunning = false;
                }
            }
        });
        watchThread.setDaemon(true); // Allow the application to exit even if this thread is running
        watchThread.start();
    }

    /**
     * Watches the project tree for changes.
     *
     * <p>{@code .git} and {@code .cadet} are skipped whole. Not registering the directory itself was
     * not enough: the walk carried on into it and registered every one of its subdirectories, whose
     * names are not {@code .git}. So a commit wrote objects and refs under {@code .git}, those writes
     * arrived as changes, and the change triggered the next commit -- the scheduler feeding itself,
     * on a tree with more directories under {@code .git} than in the project.</p>
     *
     * @param start the project directory
     * @throws IOException if the tree cannot be walked or a directory cannot be registered
     */
    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                if (!isTheProjects(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Whether a directory holds the project's own work rather than bookkeeping kept beside it.
     *
     * @param directory the directory being visited
     * @return false for {@code .git} and {@code .cadet}, whose contents change as a result of what
     *         this scheduler does
     */
    static boolean isTheProjects(Path directory) {
        Path name = directory.getFileName();
        return name == null || !NOT_THE_PROJECTS.contains(name.toString());
    }

    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            OutputFormatter.printSuccess("Auto-commit scheduler stopped.");
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            OutputFormatter.printError("Auto-commit scheduler interrupted during shutdown.");
        }

        // Shutdown the debouncer to prevent thread leaks
        debouncer.shutdown();

        if (watchServiceRunning) {
            watchServiceRunning = false;
            if (watchThread != null) {
                watchThread.interrupt();
                try {
                    watchThread.join(5000); // Wait for the thread to finish
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException e) {
                    OutputFormatter.printError("Error closing WatchService: " + e.getMessage());
                }
            }
            OutputFormatter.printSuccess("WatchService stopped.");
        }
    }

    // Simple Debouncer class to prevent too frequent commits
    private static class Debouncer {
        private final    long                     delayMs;
        private final    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private volatile Future<?>                lastFuture;

        public Debouncer(long delayMs) {
            this.delayMs = delayMs;
        }

        public void debounce(Runnable action) {
            if (lastFuture != null) {
                lastFuture.cancel(false);
            }
            lastFuture = scheduler.schedule(action, delayMs, TimeUnit.MILLISECONDS);
        }

        public void shutdown() {
            scheduler.shutdownNow();
        }
    }
}
