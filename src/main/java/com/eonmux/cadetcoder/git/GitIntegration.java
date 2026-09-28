package com.eonmux.cadetcoder.git;

import com.eonmux.cadetcoder.util.TextFiles;
import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.ui.UnifiedOutput;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public class GitIntegration {
    private final Repository repository;
    private final Git        git;

    /** Opens the repository the tool is running in. */
    public GitIntegration() throws IOException {
        this(new File("."));
    }

    /**
     * Opens the repository in a named directory.
     *
     * <h2>Why the directory is resolved before the builder sees it</h2>
     *
     * <p>The builder works out where the working tree is from the parent of the git directory it is
     * handed, and a relative path like {@code ".git"} has no parent to name. Given one, every
     * repository came back bare -- no working tree and no index -- and the first thing anything did
     * with it, staging a change, failed with "Bare Repository has neither a working tree, nor an
     * index" on a perfectly ordinary checkout.</p>
     *
     * @param projectDir the directory holding the {@code .git} directory
     * @throws IOException if the repository cannot be opened
     */
    public GitIntegration(File projectDir) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        File                  gitDir  = new File(projectDir.getAbsoluteFile(), ".git");
        if (!gitDir.exists()) {
            OutputFormatter.printError("Git repository not found. Please initialize Git in the project directory.");
            throw new RepositoryNotFoundException("Git repository not found.");
        }
        repository = builder.setGitDir(gitDir)
                            .readEnvironment()
                            .findGitDir()
                            .build();
        git        = new Git(repository);
    }

    /**
     * Stages all changes in the repository.
     *
     * <p>Two passes, for the same reason {@link #commitEverything(String)} makes two: an add records
     * what is on disk, and a file that has been deleted is not on disk. Staged in one pass only, a
     * commit made to record the state of the tree recorded a file the tree no longer has, and
     * {@code commit -a "remove the obsolete module"} removed nothing while reporting success.</p>
     *
     * @throws GitAPIException if staging fails
     */
    public void stageAllChanges() throws GitAPIException {
        git.add().addFilepattern(".").call();
        git.add().addFilepattern(".").setUpdate(true).call();
        OutputFormatter.printSuccess("All changes staged.");
    }

    /**
     * The files {@link #stageAllChanges()} would add: new and changed ones, as git reports them.
     *
     * <p>Git's status leaves out what {@code .gitignore} excludes and what has not changed, so this
     * is what a check before staging should look at. Walking the working tree instead checked
     * ignored build output that is never staged.</p>
     *
     * @return the files, as paths in the working tree; deleted files are not among them
     * @throws GitAPIException if the status cannot be read
     */
    public java.util.List<java.nio.file.Path> pathsStageAllWouldAdd() throws GitAPIException {
        org.eclipse.jgit.api.Status status = git.status().call();
        java.util.Set<String> named = new java.util.TreeSet<>();
        named.addAll(status.getUntracked());
        named.addAll(status.getModified());
        named.addAll(status.getAdded());
        named.addAll(status.getChanged());
        java.nio.file.Path tree = repository.getWorkTree().toPath();
        return named.stream().map(tree::resolve).collect(java.util.stream.Collectors.toList());
    }

    /**
     * Commits staged changes with the provided commit message.
     *
     * @param message The commit message
     * @return whether anything was committed
     * @throws GitAPIException if commit fails
     */
    public boolean commit(String message) throws GitAPIException {
        return commit(message, false);
    }

    /**
     * Commits staged changes with the provided commit message.
     *
     * <p>An index that says what the last commit says is nothing to record, and recording it anyway
     * puts an entry in the history that describes no change. Reported rather than written, so the
     * caller can say the true thing instead of announcing a commit that preserves nothing.</p>
     *
     * @param message    The commit message
     * @param skipVerify when {@code true} the soft pre-commit lint is skipped entirely
     * @return whether anything was committed, which is {@code false} when nothing was staged
     * @throws GitAPIException if commit fails
     */
    public boolean commit(String message, boolean skipVerify) throws GitAPIException {
        if (nothingStaged()) {
            OutputFormatter.printWarning("Nothing is staged, so there is nothing to commit.");
            return false;
        }
        if (!skipVerify) {
            warnOnStagedTodos();
        }

        git.commit()
           .setMessage(message)
           .call();
        OutputFormatter.printSuccess("Committed changes with message: " + message);
        return true;
    }

    /**
     * Stages everything the working tree has changed and commits it.
     *
     * <h2>Why staging is part of this rather than left to whoever calls it</h2>
     *
     * <p>{@link #commit(String)} commits what is staged, which is right for a person who has
     * chosen what to include. A commit that happens on a timer or because a file changed has
     * nobody to have chosen: staging nothing first leaves it recording an empty change while the
     * edits it was supposed to preserve sit in the working tree, unstaged and uncommitted. The
     * record and the thing being recorded have to be the same act.</p>
     *
     * @param message what the commit is to say
     * @return whether anything was committed, which is {@code false} when there was nothing to
     *         commit
     * @throws GitAPIException if staging or committing fails
     */
    public boolean commitEverything(String message) throws GitAPIException {
        git.add().addFilepattern(".").call();
        // A second pass for the files that are gone. An add records what is on disk, so a deletion
        // is staged only by an update pass over what the repository already knows about -- without
        // it, a commit meant to preserve the tree preserves a file the tree no longer has.
        git.add().addFilepattern(".").setUpdate(true).call();

        return commit(message);
    }

    /** Whether the index and the last commit say the same thing, so a commit would record nothing. */
    private boolean nothingStaged() throws GitAPIException {
        org.eclipse.jgit.api.Status status = git.status().call();
        return status.getAdded().isEmpty()
               && status.getChanged().isEmpty()
               && status.getRemoved().isEmpty();
    }

    /**
     * Best-effort pre-commit lint: WARNS (never aborts) when a staged file contains a {@code TODO}
     * marker. Scoped to the STAGED files via JGit status so it never scans {@code .git}, build
     * output, or the whole tree, and a soft lint can never block a user- or AI-requested commit
     * (the previous whole-tree {@code grep -r TODO .} aborted virtually every commit).
     */
    private void warnOnStagedTodos() {
        try {
            org.eclipse.jgit.api.Status status = git.status().call();
            java.util.Set<String>       staged = new java.util.LinkedHashSet<>();
            staged.addAll(status.getAdded());
            staged.addAll(status.getChanged());

            java.io.File           workTree  = git.getRepository().getWorkTree();
            java.util.List<String> withTodos = new java.util.ArrayList<>();
            for (String relativePath : staged) {
                java.io.File file = new java.io.File(workTree, relativePath);
                if (!file.isFile()) {
                    continue;
                }
                try {
                    String content = TextFiles.readText(file.toPath());
                    if (content.contains("TODO")) {
                        withTodos.add(relativePath);
                    }
                } catch (IOException ignored) {
                    // Unreadable/binary staged file: skip the lint for it.
                }
            }
            if (!withTodos.isEmpty()) {
                OutputFormatter.printWarning("Staged files contain TODO markers (committing anyway): "
                        + String.join(", ", withTodos));
            }
        } catch (Exception e) {
            // The lint must never block a commit; ignore any failure computing it.
        }
    }

    /**
     * Pushes committed changes to the remote repository.
     *
     * <h2>Why the result is read rather than discarded</h2>
     *
     * <p>An ordinary refusal -- the remote has moved on, the branch is protected, the ref is not
     * fast-forward -- is not an exception in JGit. It is a status on the update it refused, and a
     * push that throws away its results cannot tell a refusal from a success. Every one of them
     * printed "Pushed commits to the remote repository" and exited 0 with nothing on the remote,
     * which is the one thing a push must never say.</p>
     *
     * @return what the remote refused, one line per ref, empty when everything was accepted
     * @throws GitAPIException if the push itself fails
     */
    public java.util.List<String> push() throws GitAPIException {
        return push(false, null, false);
    }

    /**
     * Pushes, as the caller asked.
     *
     * <h2>Why these three were not supported before</h2>
     *
     * <p>{@code push} accepted {@code --force}, {@code --set-upstream} and {@code --branch},
     * recognised all three, and answered each with "not supported by the JGit integration. Please
     * use git command line." JGit supports all three; nothing had asked it to. The options were
     * therefore documented in the README as working, refused by the tool, and omitted from its own
     * usage text to keep the refusal from being advertised -- three descriptions of one feature,
     * none of which agreed.</p>
     *
     * <h2>What "set upstream" means here</h2>
     *
     * <p>Git's {@code -u} is not part of the push: it is a write to {@code .git/config} recording
     * which remote branch this one tracks, which git performs after a push succeeds. So it is done
     * here in that order, and a failure to record it does not turn a push that reached the remote
     * into a reported failure -- the commits are there either way, and saying otherwise would send
     * the user to push again.</p>
     *
     * @param force       whether to overwrite the remote's history with this branch's
     * @param branch      the branch to push, or {@code null} for the current one and its upstream
     * @param setUpstream whether to record the pushed branch as this branch's upstream
     * @return the remote's refusals, empty when every ref was accepted
     * @throws GitAPIException if the push itself could not be carried out
     */
    public java.util.List<String> push(boolean force, String branch, boolean setUpstream)
            throws GitAPIException {
        java.util.List<String> refused = new java.util.ArrayList<>();
        PushCommand push = git.push().setForce(force);
        String      pushed = branch;
        if (branch != null && !branch.isBlank()) {
            // Named explicitly, so the ref to push is spelled out rather than left to the
            // configured refspec -- which would push the CURRENT branch whatever was asked for.
            push.setRefSpecs(new org.eclipse.jgit.transport.RefSpec(
                    "refs/heads/" + branch + ":refs/heads/" + branch));
        } else {
            pushed = currentBranchOrNull();
        }
        for (org.eclipse.jgit.transport.PushResult result : push.call()) {
            for (org.eclipse.jgit.transport.RemoteRefUpdate update : result.getRemoteUpdates()) {
                if (wasAccepted(update)) {
                    continue;
                }
                String detail = update.getMessage() == null || update.getMessage().isBlank()
                                ? ""
                                : " (" + update.getMessage() + ")";
                refused.add(update.getRemoteName() + ": " + update.getStatus() + detail);
            }
        }
        if (refused.isEmpty()) {
            OutputFormatter.printSuccess("Pushed commits to the remote repository.");
            if (setUpstream) {
                recordUpstream(pushed);
            }
        }
        return refused;
    }

    /**
     * The branch currently checked out, or {@code null} when it cannot be read.
     *
     * <p>Only wanted in order to record an upstream, which is best effort, so a repository that
     * will not say must not fail the push that has already been asked for.</p>
     *
     * @return the branch name, or {@code null}
     */
    private String currentBranchOrNull() {
        try {
            return repository.getBranch();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Records which remote branch a local branch tracks, the way {@code git push -u} does.
     *
     * <p>Best effort by design: the push has already succeeded when this runs, so a configuration
     * that could not be written is reported as the smaller thing it is rather than allowed to make
     * a successful push look like a failed one.</p>
     *
     * @param branch the local branch that was pushed
     */
    private void recordUpstream(String branch) {
        if (branch == null || branch.isBlank()) {
            OutputFormatter.printWarning("Could not tell which branch to set an upstream for.");
            return;
        }
        try {
            org.eclipse.jgit.lib.StoredConfig config = repository.getConfig();
            String remote = org.eclipse.jgit.lib.Constants.DEFAULT_REMOTE_NAME;
            config.setString("branch", branch, "remote", remote);
            config.setString("branch", branch, "merge", "refs/heads/" + branch);
            config.save();
            OutputFormatter.printInfo("Branch '" + branch + "' now tracks " + remote + "/" + branch + ".");
        } catch (IOException e) {
            OutputFormatter.printWarning(
                    "Pushed, but could not record the upstream for '" + branch + "': " + e.getMessage());
        }
    }

    /**
     * @param update one ref the remote was asked to move
     * @return whether the remote took it, counting a ref that was already there as taken
     */
    private static boolean wasAccepted(org.eclipse.jgit.transport.RemoteRefUpdate update) {
        return update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK
               || update.getStatus() == org.eclipse.jgit.transport.RemoteRefUpdate.Status.UP_TO_DATE;
    }

    /**
     * Performs a hard reset to the specified commit hash.
     *
     * @param commitHash The commit hash to reset to
     * @throws GitAPIException if reset fails
     */
    public void undo(String commitHash) throws GitAPIException {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(commitHash).call();
        OutputFormatter.printSuccess("Rolled back to commit: " + commitHash);
    }

    /**
     * Retrieves the latest commit hash from the repository.
     *
     * <h2>Why the iterator is taken once</h2>
     *
     * <p>The log is a walk, and each call to {@code iterator()} continues it. Asked once whether
     * there was a commit and again for the commit, the second iterator started past the only one a
     * walk limited to one returns, so every repository was reported as having no commits -- straight
     * after {@code commit} had made one.</p>
     *
     * <h2>Why a missing HEAD is caught</h2>
     *
     * <p>A repository with no commits has no HEAD, and the log refuses to start rather than coming
     * back empty. That is the case the warning below is for, and without the catch it was never
     * reached: callers asking whether there was anything to undo or push got an exception instead
     * of the answer.</p>
     *
     * @return The latest commit hash, or {@code null} when the repository has no commits
     * @throws GitAPIException if retrieving the commit hash fails
     */
    public String getLatestCommitHash() throws GitAPIException {
        try {
            Iterator<RevCommit> logs = git.log().setMaxCount(1).call().iterator();
            if (logs.hasNext()) {
                return logs.next().getName();
            }
        } catch (NoHeadException noCommitsYet) {
            // Reported below, as the empty history it is.
        }
        OutputFormatter.printWarning("No commits found in the repository.");
        return null;
    }

}
