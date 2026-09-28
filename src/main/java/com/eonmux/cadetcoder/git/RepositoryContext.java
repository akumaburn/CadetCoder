package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.logging.DebugLogger;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the repository the project lives in has been doing, for the model to read.
 *
 * <h2>Why a model is told about git at all</h2>
 *
 * <p>A task is nearly always asked in the middle of something: a branch with a name that says what
 * it is for, and a handful of commits that say what has been tried. Told neither, a model writes
 * against the project as though it had just been checked out -- proposing work that was done three
 * commits ago, or on the assumption that the current branch is the main one.</p>
 *
 * <p>It is off by default and asked for by {@code git.includeBranches} and
 * {@code git.includeCommitHistory}, because it is not free: every line of it is prompt the code the
 * user asked about does not get, and on a repository with a long history it is a lot of lines.
 * {@code git.maxCommitHistory} bounds it.</p>
 */
public final class RepositoryContext {

    /** How the block announces itself, which is also how the tests and the prompt agree on it. */
    public static final String HEADING = "REPOSITORY:";

    /** Branches listed beside the current one, so a long-lived checkout does not fill the prompt. */
    private static final int MAX_BRANCHES_SHOWN = 10;

    /** How much of a commit subject is worth carrying. */
    private static final int MAX_SUBJECT_LENGTH = 72;

    private RepositoryContext() {
    }

    /**
     * The block for the project this process is running in.
     *
     * @return the block, ending in a blank line, or {@code ""} when there is nothing to say -- git
     *         is off, neither part was asked for, there is no repository, or it cannot be read
     */
    public static String forPrompt() {
        Configuration.GitConfig git;
        try {
            git = ConfigManager.getInstance().getConfig().getGit();
        } catch (RuntimeException unreadable) {
            return "";
        }
        return render(git, new File(System.getProperty("user.dir", ".")));
    }

    /**
     * The block for a stated repository and configuration.
     *
     * @param config     what the user asked to be told; {@code null} means nothing
     * @param projectDir the directory holding the {@code .git} directory
     * @return the block, or {@code ""} when there is nothing to say
     */
    static String render(Configuration.GitConfig config, File projectDir) {
        if (config == null || !config.isEnabled()) {
            return "";
        }
        if (!config.isIncludeBranches() && !config.isIncludeCommitHistory()) {
            return "";
        }
        File gitDir = new File(projectDir.getAbsoluteFile(), ".git");
        if (!gitDir.exists()) {
            // Not every project is a checkout. Silence, because there is nothing wrong.
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir).build();
             Git git = new Git(repository)) {
            if (config.isIncludeBranches()) {
                appendBranches(body, repository, git);
            }
            if (config.isIncludeCommitHistory()) {
                appendHistory(body, git, config.getMaxCommitHistory());
            }
        } catch (IOException | GitAPIException | RuntimeException unreadable) {
            // A prompt is worth sending without this. Recorded rather than printed: the person is
            // waiting on an answer, not on a report about their repository's internals.
            DebugLogger.getInstance().debug("RepositoryContext",
                    "Repository context left out: " + unreadable.getMessage());
            return "";
        }
        return body.isEmpty() ? "" : HEADING + "\n" + body + "\n";
    }

    /**
     * The branch the work is on, and what else exists beside it.
     *
     * @param body       what is being built
     * @param repository the open repository
     * @param git        a handle on it
     * @throws GitAPIException if the branches cannot be listed
     */
    private static void appendBranches(StringBuilder body, Repository repository, Git git)
            throws IOException, GitAPIException {
        String current = repository.getBranch();
        if (current != null && !current.isBlank()) {
            body.append("Current branch: ").append(current).append('\n');
        }
        List<String> others = new ArrayList<>();
        for (Ref branch : git.branchList().call()) {
            String name = Repository.shortenRefName(branch.getName());
            if (!name.equals(current)) {
                others.add(name);
            }
        }
        if (others.isEmpty()) {
            return;
        }
        int shown = Math.min(MAX_BRANCHES_SHOWN, others.size());
        body.append("Other branches: ").append(String.join(", ", others.subList(0, shown)));
        if (others.size() > shown) {
            body.append(", and ").append(others.size() - shown).append(" more");
        }
        body.append('\n');
    }

    /**
     * The commits behind the current branch, newest first.
     *
     * @param body     what is being built
     * @param git      a handle on the repository
     * @param maxShown how many to carry; {@code 0} or less means none
     * @throws GitAPIException if the log cannot be read
     */
    private static void appendHistory(StringBuilder body, Git git, int maxShown)
            throws GitAPIException {
        if (maxShown <= 0) {
            return;
        }
        List<String> lines = new ArrayList<>();
        try {
            for (RevCommit commit : git.log().setMaxCount(maxShown).call()) {
                lines.add("- " + commit.getName().substring(0, 7) + " " + subjectOf(commit));
            }
        } catch (org.eclipse.jgit.api.errors.NoHeadException nothingCommittedYet) {
            // A repository with no commits has no history, which is not a failure to read one.
            return;
        }
        if (lines.isEmpty()) {
            return;
        }
        body.append("Recent commits (newest first):\n");
        for (String line : lines) {
            body.append(line).append('\n');
        }
    }

    /**
     * A commit's first line, bounded.
     *
     * @param commit the commit
     * @return its subject, cut at {@link #MAX_SUBJECT_LENGTH} with an ellipsis if it was longer
     */
    private static String subjectOf(RevCommit commit) {
        String subject = commit.getShortMessage() == null ? "" : commit.getShortMessage().strip();
        return subject.length() <= MAX_SUBJECT_LENGTH
               ? subject
               : subject.substring(0, MAX_SUBJECT_LENGTH) + "...";
    }
}
