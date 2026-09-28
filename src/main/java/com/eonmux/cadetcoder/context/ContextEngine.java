package com.eonmux.cadetcoder.context;

import com.eonmux.cadetcoder.OutputFormatter;
import com.eonmux.cadetcoder.config.ConfigManager;
import com.eonmux.cadetcoder.config.Configuration;
import com.eonmux.cadetcoder.security.SecurityValidator;
import com.eonmux.cadetcoder.util.TextFiles;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ContextEngine {

    /**
     * Held in fields, not fetched where they are configured.
     *
     * <p>{@code java.util.logging} keeps only a weak reference to a logger nobody else holds, so a
     * level set on one that is then collected is silently lost and the logger comes back at its
     * default. That is exactly what happened: the level was set in the constructor and the notice
     * still appeared on the longer-running commands, because by the time Lucene got round to
     * logging it, the configured logger had been collected and recreated.</p>
     */
    private static final java.util.logging.Logger LUCENE_LOGGER =
            java.util.logging.Logger.getLogger("org.apache.lucene");
    private static final java.util.logging.Logger LUCENE_VECTOR_LOGGER =
            java.util.logging.Logger.getLogger("org.apache.lucene.internal.vectorization");

    /**
     * The most terms this tool puts in one search.
     *
     * <p>Relevance saturates long before this. A query is scored on how many of its terms a file
     * carries, so past a couple of hundred every file matches a few and none stands out. What
     * follows in a long request is context for the model to read, not words to search on.</p>
     */
    private static final int MAX_QUERY_TERMS = 256;

    /**
     * The largest file worth putting in front of a model.
     *
     * <p>Above this the content is almost always generated -- a bundled asset, a lock file, a data
     * dump -- and one such file can outweigh the whole project in the snippets a search returns.</p>
     *
     * <p>The same ceiling bounds what a name in a request pulls in ({@link NamedFiles}), because it
     * is the same question. A file too large to be worth searching is not one a request should be
     * able to paste into a prompt whole by naming it.</p>
     */
    public static final long MAX_CONTEXT_FILE_BYTES = 500L * 1024L;

    private static ContextEngine   instance;
    private final  Analyzer        analyzer;

    /**
     * The shared credential denylist.
     *
     * <p>What is in the index is what {@code search} prints and what {@code edit} and {@code agent}
     * put into the prompt they send to the provider, so it answers to the same rule as
     * {@code read}: hidden files were skipped, which caught {@code .env} by accident and nothing
     * else.</p>
     */
    private final  SecurityValidator credentialRules = new SecurityValidator();

    /**
     * {@link #lastIndexedAt} before anything has been indexed.
     *
     * <p>Not zero: zero is a perfectly ordinary reading of a clock, and a test that starts one
     * there would find the engine claiming it had never indexed anything however often it had.</p>
     */
    private static final long NEVER_INDEXED = -1L;

    /** Minutes are what the setting is written in; milliseconds are what the clock reports. */
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final  String          indexPath;
    private        IndexWriter     writer;

    /**
     * The open reader and the searcher over it, replaced together whenever the index is refreshed.
     *
     * <h2>Why a search does not simply read these</h2>
     *
     * <p>This engine is a singleton and up to eight workers reach it at once through
     * {@code AgentPrompts.searchRelevantSnippets}, while any one of them can trigger a refresh --
     * {@link #ensureIndexed(long)} does, on the interval the configuration names. A refresh closes
     * the reader a search is part-way through reading, which Lucene answers with
     * {@link org.apache.lucene.store.AlreadyClosedException}; the caller turns that into "Could not
     * retrieve context" and that worker's whole turn goes to the model with no project context at
     * all, silently. The fields are volatile so a search sees a complete swap rather than half of
     * one, and a search holds its reader open for its duration through
     * {@link #acquireSearcher()}.</p>
     */
    private volatile DirectoryReader reader;
    private volatile IndexSearcher   searcher;
    private        FSDirectory     directory;

    /**
     * When this process last brought the index up to date, or 0 if it never has.
     *
     * <p>A moment rather than a flag: see {@link #ensureIndexed()} for why a flag was wrong.</p>
     */
    private        long            lastIndexedAt  = NEVER_INDEXED;

    /** Set when an unreadable index had to be discarded at open, for the caller to report. */
    private        boolean         indexRebuilt   = false;

    private ContextEngine() throws IOException {
        Configuration config = ConfigManager.getInstance().getConfig();
        quietenLuceneUnlessDebugging(config);
        this.analyzer = new StandardAnalyzer();
        this.indexPath = config.getIndexing().getIndexLocation();
        initializeIndex();
    }

    /**
     * Keeps Lucene's own start-up notices out of a command's output.
     *
     * <p>Lucene announces the storage and vectorisation implementations it picked through
     * {@code java.util.logging} at INFO, and on JDK 19 and newer that is two multi-line notices on
     * stderr the first time an index is opened -- in the middle of whatever the user actually ran.
     * They are facts about the JVM, not about the project, and nobody typing {@code search} is
     * asking for them. Warnings and errors from Lucene are untouched, and {@code --debug} keeps the
     * lot, because someone who has asked for detail should get it.</p>
     */
    private static void quietenLuceneUnlessDebugging(Configuration config) {
        try {
            if (config.getLogging() != null && config.getLogging().isDebugEnabled()) {
                return;
            }
            LUCENE_LOGGER.setLevel(java.util.logging.Level.WARNING);
            // One step further for the vectorisation provider, which reports the absence of an
            // OPTIONAL incubator module at WARNING. It is advice about a JVM launch flag this
            // program does not control, it says nothing about the user's project, and it is
            // printed on every single run. Other Lucene warnings still come through.
            LUCENE_VECTOR_LOGGER.setLevel(java.util.logging.Level.SEVERE);
        } catch (RuntimeException ignored) {
            // Log configuration is a convenience; failing to set it must not stop indexing.
        }
    }

    public static synchronized ContextEngine getInstance() throws IOException {
        if (instance == null) {
            instance = new ContextEngine();
        }
        return instance;
    }

    /**
     * Reset the singleton instance for testing purposes.
     * Properly closes all resources before clearing the instance.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            try {
                instance.close();
            } catch (IOException e) {
                // Log but don't throw during cleanup
                // Use UnifiedOutput to respect OutputRouter if active
                try {
                    if (com.eonmux.cadetcoder.ui.OutputRouter.getInstance().isRouting()) {
                        com.eonmux.cadetcoder.ui.UnifiedOutput.printlnErr("Warning: Failed to close ContextEngine during reset: " + e.getMessage());
                    } else {
                        System.err.println("Warning: Failed to close ContextEngine during reset: " + e.getMessage());
                    }
                } catch (Exception ex) {
                    // Fallback to direct System.err if OutputRouter is not available
                    System.err.println("Warning: Failed to close ContextEngine during reset: " + e.getMessage());
                }
            }
            instance = null;
        }
    }

    /**
     * Close all Lucene resources properly
     *
     * <p>Synchronized with the rest of the lifecycle so a caller shutting the engine down cannot
     * land between a refresh letting go of one reader and opening the next.</p>
     */
    public synchronized void close() throws IOException {
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (writer != null) {
            writer.close();
            writer = null;
        }
        if (directory != null) {
            directory.close();
            directory = null;
        }
        searcher = null;
    }

    /**
     * Opens the index, discarding and recreating it if what is on disk cannot be read.
     *
     * <p>An index is written by the Lucene of the day, and the version that wrote it may be older
     * than the version reading it -- a checkout carried across an upgrade, or an index left by an
     * earlier build. Lucene reports that as {@code Could not load codec 'Lucene95'}, which reached
     * the user as an unactionable sentence about a JAR file, and it stayed broken forever after:
     * {@code index}, {@code search}, {@code edit} and {@code agent} all open the index, so all four
     * failed until someone thought to delete a directory nobody had mentioned.</p>
     *
     * <p>The index is a derived cache of files that are all still on disk, so there is nothing to
     * preserve and no reason to ask: throwing it away and rebuilding costs one scan and is what the
     * user would have had to do by hand.</p>
     */
    private void initializeIndex() throws IOException {
        try {
            openIndex();
        } catch (org.apache.lucene.store.LockObtainFailedException inUse) {
            // NOT an unreadable index, and the recovery below would be a catastrophe here: Lucene
            // takes an exclusive write lock, so this means another CadetCoder has the index open,
            // and discarding it would delete the segments that process is still writing into --
            // along with the lock file itself. The index is somebody else's; it is not ours to
            // throw away.
            closeQuietly();
            throw new IOException(
                    "The search index at " + indexPath + " is open in another CadetCoder process. "
                    + "Close it and try again, or point this run at an index of its own with "
                    + "'config indexing.indexLocation <path>'.", inUse);
        } catch (IOException | RuntimeException first) {
            closeQuietly();
            if (!discardIndexFiles()) {
                throw first;
            }
            openIndex();
            indexRebuilt = true;
        }
    }

    private void openIndex() throws IOException {
        this.directory = FSDirectory.open(Paths.get(indexPath));
        IndexWriterConfig configWriter = new IndexWriterConfig(analyzer);
        this.writer   = new IndexWriter(directory, configWriter);
        this.reader   = DirectoryReader.open(writer);
        this.searcher = new IndexSearcher(reader);
    }

    /** Releases whatever a failed open managed to acquire, so the files can be replaced. */
    private void closeQuietly() {
        try {
            close();
        } catch (IOException | RuntimeException ignored) {
            // Already failing; a second failure while cleaning up adds nothing.
        }
    }

    /**
     * Deletes the index's own files, leaving the directory in place.
     *
     * @return whether the directory is now empty of index files
     */
    private boolean discardIndexFiles() {
        File dir = new File(indexPath);
        if (!dir.isDirectory()) {
            // Nothing on disk to blame: the open failed for some other reason, so let it surface.
            return false;
        }
        File[] existing = dir.listFiles();
        if (existing == null) {
            return false;
        }
        boolean cleared = true;
        for (File file : existing) {
            if (file.isFile() && !file.delete()) {
                cleared = false;
            }
        }
        return cleared;
    }

    /**
     * Whether the index on disk could not be read and was rebuilt from scratch when this engine
     * opened it.
     *
     * @return {@code true} if an unreadable index was discarded
     */
    public boolean wasIndexRebuilt() {
        boolean thisTime = indexRebuilt;
        // Answered once. The engine is a singleton for the life of the process, and the flag was
        // never put back, so every `index` after the one that actually discarded a stale index
        // repeated "the existing index could not be read and has been rebuilt from scratch" about
        // a run that had read a perfectly good index and discarded nothing.
        indexRebuilt = false;
        return thisTime;
    }

    /**
     * Adds one file to the index and makes it searchable.
     *
     * <p>Synchronized because it ends in a refresh, and a refresh replaces the reader every search
     * is reading through. Without the monitor a search could acquire the reader this method is in
     * the middle of retiring.</p>
     *
     * @param file the file to index
     * @throws IOException if the index cannot be written
     */
    public synchronized void indexFile(File file) throws IOException {
        if (writer == null) {
            throw new IllegalStateException("ContextEngine has been closed");
        }
        Document doc = new Document();
        doc.add(new StringField("path", file.getAbsolutePath(), Field.Store.YES));
        doc.add(new TextField("content", TextFiles.readText(file.toPath()), Field.Store.YES));
        doc.add(new StringField("filename", file.getName(), Field.Store.YES));
        writer.updateDocument(new Term("path", file.getAbsolutePath()), doc);
        writer.commit();
        refreshSearcher(); // Refresh searcher to make newly indexed content searchable
    }

    /**
     * Points the searcher at what the writer has written.
     *
     * <h2>Why the index is only thrown away once reopening has actually been tried</h2>
     *
     * <p>Emptying the index costs a full reindex of the project, so it is the last thing to try
     * rather than the first. Closing the old reader and opening a new one were in one {@code try},
     * which meant a failure to CLOSE the old reader -- which says nothing at all about whether a new
     * one can be opened -- went straight to {@code deleteAll}. The two are separated here: letting
     * go of the old reader cannot fail the refresh, opening the new one is attempted on its own,
     * and everything is discarded only when that attempt has been made and has failed.</p>
     */
    private synchronized void refreshSearcher() throws IOException {
        if (writer == null) {
            throw new IllegalStateException("ContextEngine has been closed");
        }
        letGoOfReader();
        try {
            openSearcher();
            return;
        } catch (IOException cannotOpen) {
            OutputFormatter.printWarning("Failed to refresh searcher: " +
                                         cannotOpen.getMessage() +
                                         ". Attempting to rebuild index from scratch.");
        }
        try {
            writer.deleteAll();
            writer.commit();
            letGoOfReader();
            openSearcher();
        } catch (IOException ex) {
            OutputFormatter.printError("Rebuilding index failed: " + ex.getMessage());
            throw ex;
        }
    }

    /** Opens a reader on what the writer has, and a searcher over it. */
    private void openSearcher() throws IOException {
        reader   = DirectoryReader.open(writer);
        searcher = new IndexSearcher(reader);
    }

    /**
     * Drops the reader the searcher was using.
     *
     * <p>A reader that will not close is a leaked file handle, not a broken index, and it is not a
     * reason to refuse to open a new one -- still less to empty the index. It is forgotten either
     * way, so nothing goes on reading through it.</p>
     *
     * <p>Closing a Lucene reader releases this engine's reference to it rather than tearing it down
     * where it stands: a search that has taken a reference of its own through
     * {@link #acquireSearcher()} goes on reading the segments it started with, and the files are
     * released when the last of those searches has finished. That is what makes a refresh during a
     * search safe.</p>
     */
    private void letGoOfReader() {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException wontClose) {
            OutputFormatter.printWarning("The previous index reader would not close: "
                                         + wontClose.getMessage());
        }
        reader = null;
    }

    /**
     * Brings the index up to date with the project on disk.
     *
     * <p>Prints nothing. It used to announce every file it touched and then that it had finished,
     * which on a project of any size buried whatever the user had actually asked for under one line
     * per file -- and {@code search}, {@code edit} and {@code agent} all reach this the first time
     * they need context, so the spam arrived in the middle of their output too. What was done is
     * returned instead, for the caller to report in one line if it wants to.</p>
     *
     * @return how many files were indexed, left alone, and could not be read
     * @throws IOException if the index cannot be written
     */
    public ReindexSummary reindex() throws IOException {
        return reindexAt(System.currentTimeMillis());
    }

    /**
     * The same, recording a stated moment as the one the index is now current as of.
     *
     * @param now the current time in milliseconds
     * @return how many files were indexed, left alone, and could not be read
     * @throws IOException if the index cannot be written
     */
    synchronized ReindexSummary reindexAt(long now) throws IOException {
        if (writer == null || searcher == null) {
            throw new IllegalStateException("ContextEngine has been closed");
        }
        List<File>  filesToIndex = discoverFiles();
        Set<String> discovered   = new HashSet<>();
        for (File file : filesToIndex) {
            discovered.add(file.getAbsolutePath());
        }
        int        removed      = forgetEverythingNotDiscovered(discovered);
        int        indexed      = 0;
        int        unchanged    = 0;
        int        unreadable   = 0;
        for (File file : filesToIndex) {
            try {
                String  newChecksum = computeChecksum(file);
                Query   query       = new TermQuery(new Term("path", file.getAbsolutePath()));
                TopDocs topDocs     = searcher.search(query, 1);
                boolean shouldIndex = true;
                if (topDocs.totalHits.value > 0) {
                    Document doc         = searcher.doc(topDocs.scoreDocs[0].doc);
                    String   oldChecksum = doc.get("checksum");
                    if (newChecksum.equals(oldChecksum)) {
                        shouldIndex = false;
                    }
                }
                if (shouldIndex) {
                    indexFileWithChecksum(file, newChecksum);
                    indexed++;
                } else {
                    unchanged++;
                }
            } catch (Exception e) {
                noteUnreadable(file);
                unreadable++;
            }
        }
        writer.commit();
        refreshSearcher();
        lastIndexedAt = now;
        return new ReindexSummary(indexed, unchanged, unreadable, removed);
    }

    /**
     * Records a file that could not be read, without letting that failure end the reindex.
     *
     * <h2>Why the recovery needed a recovery</h2>
     *
     * <p>The file is indexed with an empty checksum so the next run reconsiders it -- but indexing
     * it means READING it, which is the thing that just failed. So the handler for an unreadable
     * file threw the same exception the handler was there to absorb, this time out of
     * {@link #reindex()} itself: one file at mode 000, one broken symlink, one file deleted between
     * discovery and indexing, and the walk was abandoned where it stood. Everything after it in the
     * project went unindexed and {@code search} answered from a partial index without saying so.</p>
     *
     * <p>A file that cannot be read is simply left out. It carries no document, so the next reindex
     * finds nothing for its path and tries it again -- which is what the empty checksum was for.</p>
     *
     * @param file the file that could not be read
     */
    private void noteUnreadable(File file) {
        try {
            indexFileWithChecksum(file, "");
        } catch (IOException | RuntimeException cannotEither) {
            com.eonmux.cadetcoder.logging.DebugLogger.getInstance().debug(
                    "ContextEngine",
                    "Skipping unreadable file " + file.getAbsolutePath() + ": "
                    + cannotEither.getMessage());
        }
    }

    /**
     * Removes from the index every document the project no longer offers.
     *
     * <p>{@code reindex} only ever added and updated, and the index outlives the run, so it
     * accumulated. A file deleted from the project was still returned by {@code search} as though
     * it were current -- stale context handed to the model as fact -- and a credential file
     * indexed before the denylist reached this class would have gone on being served after it. One
     * rule covers both: the index holds what discovery would index, and nothing else.</p>
     *
     * @param discovered absolute paths of the files this run would index
     * @return how many documents were removed
     * @throws IOException if the index cannot be read or written
     */
    private int forgetEverythingNotDiscovered(Set<String> discovered) throws IOException {
        int held = searcher.getIndexReader().numDocs();
        if (held == 0) {
            return 0;
        }

        TopDocs everything = searcher.search(new MatchAllDocsQuery(), held);
        int     removed    = 0;
        for (ScoreDoc scoreDoc : everything.scoreDocs) {
            String path = searcher.doc(scoreDoc.doc).get("path");
            if (path != null && !discovered.contains(path)) {
                writer.deleteDocuments(new Term("path", path));
                removed++;
            }
        }
        return removed;
    }

    /**
     * Indexes the project once per process, for callers that need to search rather than to index.
     *
     * <p>This is the work the constructor used to do. Doing it there meant {@code index} paid for
     * it twice -- once on the way to getting the engine, once when the command asked for the
     * reindex it exists to perform -- and that every full scan happened before the caller could say
     * whether it wanted one. Doing it here means one scan, on the first search that needs it, and
     * none at all when indexing is switched off.</p>
     *
     * <h2>Why once per process was not enough</h2>
     *
     * <p>"Once per process" is the whole of a one-shot command's life and no part of an interactive
     * session's. A shell left open all day indexed the project when it started and never again, so
     * every file written after that -- by the user's editor, by this tool's own {@code write} and
     * {@code edit} -- was invisible to {@code search} and absent from the context the model was
     * given, for as long as the session lasted. {@code indexing.refreshIntervalMinutes} has
     * described the cure since the configuration was first written; nothing read it.</p>
     *
     * @throws IOException if the index cannot be written
     */
    public synchronized void ensureIndexed() throws IOException {
        ensureIndexed(System.currentTimeMillis());
    }

    /**
     * The same, at a stated moment.
     *
     * @param now the current time in milliseconds, so the interval can be exercised without waiting
     * @throws IOException if the index cannot be written
     */
    synchronized void ensureIndexed(long now) throws IOException {
        Configuration.IndexingConfig indexing =
                ConfigManager.getInstance().getConfig().getIndexing();
        if (!indexing.isEnabled() || !dueForIndexing(indexing.getRefreshIntervalMinutes(), now)) {
            return;
        }
        reindexAt(now);
    }

    /**
     * Whether the index should be brought up to date now.
     *
     * @param refreshIntervalMinutes how long an index stays fresh; {@code 0} means it is indexed
     *                               once and then left alone for the rest of the run
     * @param now                    the current time in milliseconds
     * @return true if nothing has been indexed yet, or the interval has elapsed since it was
     */
    private boolean dueForIndexing(int refreshIntervalMinutes, long now) {
        if (lastIndexedAt == NEVER_INDEXED) {
            return true;
        }
        if (refreshIntervalMinutes <= 0) {
            return false;
        }
        return now - lastIndexedAt >= refreshIntervalMinutes * MILLIS_PER_MINUTE;
    }

    /** What a {@link #reindex()} did, for the caller that asked for it to report. */
    public static final class ReindexSummary {
        private final int indexed;
        private final int unchanged;
        private final int unreadable;
        private final int removed;

        /**
         * @param indexed    files whose contents were written to the index
         * @param unchanged  files already in the index with the same contents
         * @param unreadable files that could not be read
         */
        public ReindexSummary(int indexed, int unchanged, int unreadable) {
            this(indexed, unchanged, unreadable, 0);
        }

        /**
         * @param indexed    files whose contents were written to the index
         * @param unchanged  files already in the index with the same contents
         * @param unreadable files that could not be read
         * @param removed    documents dropped because the project no longer offers the file
         */
        public ReindexSummary(int indexed, int unchanged, int unreadable, int removed) {
            this.indexed    = indexed;
            this.unchanged  = unchanged;
            this.unreadable = unreadable;
            this.removed    = removed;
        }

        /** @return documents dropped because the project no longer offers the file */
        public int getRemoved() {
            return removed;
        }

        /** @return files whose contents were written to the index */
        public int getIndexed() {
            return indexed;
        }

        /** @return files already in the index with the same contents */
        public int getUnchanged() {
            return unchanged;
        }

        /** @return files that could not be read, indexed empty so the next run retries them */
        public int getUnreadable() {
            return unreadable;
        }

        /** @return every file considered */
        public int getTotal() {
            return indexed + unchanged + unreadable;
        }

        /** @return one line describing the run, for the command that asked for it */
        public String describe() {
            int    total = getTotal();
            String files = total + (total == 1 ? " file" : " files");
            StringBuilder text = new StringBuilder();
            if (indexed == 0 && unreadable == 0) {
                text.append(files).append(", all unchanged");
            } else if (unchanged == 0 && unreadable == 0) {
                text.append(files).append(" indexed");
            } else {
                text.append(files).append(": ").append(indexed).append(" indexed");
                if (unchanged > 0) {
                    text.append(", ").append(unchanged).append(" unchanged");
                }
            }
            if (unreadable > 0) {
                text.append(", ").append(unreadable).append(" unreadable");
            }
            if (removed > 0) {
                text.append(", ").append(removed).append(" removed");
            }
            return text.append('.').toString();
        }
    }

    private List<File> discoverFiles() {
        List<File> files      = new ArrayList<>();
        File       projectDir = new File(System.getProperty("user.dir"));
        discoverFilesRecursive(projectDir, files);
        return files;
    }

    private void discoverFilesRecursive(File dir, List<File> files) {
        File[] children = dir.listFiles();
        if (children == null) {
            // Not a directory, deleted mid-walk, or unreadable: skip it, keep traversing.
            return;
        }
        for (File file : children) {
            if (file.isHidden() || dir.isHidden()) {
                continue;
            }
            if (file.isDirectory()) {
                // Exclude the internal .cadet directory from being indexed
                if (".cadet".equals(file.getName())) {
                    continue;
                }
                if (!com.eonmux.cadetcoder.util.ProjectTreeWalk.isExcludedName(file.getName())) {
                    discoverFilesRecursive(file, files);
                }
            } else if (file.length() <= MAX_CONTEXT_FILE_BYTES
                       && !credentialRules.isSensitiveCredentialFile(file.getAbsolutePath())
                       && !isKnownToBeBinary(file)) {
                files.add(file);
            }
        }
    }

    /**
     * Whether a file's content is something other than text.
     *
     * <p>The index stores whole file contents and {@code search}, {@code edit} and {@code agent}
     * read snippets back out of it, the last two straight into the prompt they send to the
     * provider. A {@code .class} file under the size cap was read with {@code new String(bytes)}
     * and stored, so a constant pool was offered to the model as project context.</p>
     *
     * <p>A file that cannot be read is NOT called binary: it is discovered, and {@link #reindex}
     * counts it among the files it could not read. Answering "binary" here would drop it from the
     * project without saying so.</p>
     *
     * @param file the candidate
     * @return whether its content is known not to be text
     */
    private boolean isKnownToBeBinary(File file) {
        try {
            return !TextFiles.isTextFile(file.toPath());
        } catch (IOException e) {
            return false;
        }
    }

    public List<String> searchRelevantSnippets(String naturalLanguage) throws Exception {
        return search(naturalLanguage);
    }

    /** The same, at a stated moment; see {@link #searchAt(String, long)}. */
    List<String> searchRelevantSnippetsAt(String naturalLanguage, long now) throws Exception {
        return searchAt(naturalLanguage, now);
    }

    public List<String> search(String queryStr) throws Exception {
        return searchAt(queryStr, System.currentTimeMillis());
    }

    /**
     * The same, at a stated moment.
     *
     * <p>A search brings the index up to date first, so it answers to
     * {@code indexing.refreshIntervalMinutes} exactly as {@link #ensureIndexed()} does and has to be
     * given the same clock to be exercised against it.</p>
     *
     * @param queryStr what to search for
     * @param now      the current time in milliseconds
     * @return one snippet per matching file
     * @throws Exception if the index cannot be read
     */
    List<String> searchAt(String queryStr, long now) throws Exception {
        List<String> snippets = new ArrayList<>();
        for (ContextMatch match : findAt(queryStr, now)) {
            snippets.add(match.forPrompt());
        }
        return snippets;
    }

    /**
     * The files matching a query, each carrying the lines that matched.
     *
     * @param queryStr what to search for
     * @return the matches, most relevant first
     * @throws Exception if the index cannot be read
     */
    public List<ContextMatch> find(String queryStr) throws Exception {
        return findAt(queryStr, System.currentTimeMillis());
    }

    /**
     * The same, at a stated moment; see {@link #searchAt(String, long)} for why the clock is passed.
     *
     * @param queryStr what to search for
     * @param now      the current time in milliseconds
     * @return the matches, most relevant first
     * @throws Exception if the index cannot be read
     */
    List<ContextMatch> findAt(String queryStr, long now) throws Exception {
        if (searcher == null) {
            throw new IllegalStateException("ContextEngine has been closed");
        }
        ensureIndexed(now);
        Query query = queryFor(queryStr);
        if (query == null) {
            return new ArrayList<>();
        }

        // Taken once and held for the whole search. Read from the field a statement at a time, the
        // search could begin on one reader and finish on another -- or on one that a refresh closed
        // in between, which is the failure this guards.
        IndexSearcher held = acquireSearcher();
        try {
            TopDocs results = held.search(
                    query, ConfigManager.getInstance().getConfig().getContext().getMaxFiles());

            // The terms Lucene actually matched on, taken from the parsed query rather than from the
            // query text. They are the same terms the index holds -- analysed, folded and stripped of
            // the syntax the parser understood -- so the lines picked out below are the lines that
            // earned the hit, and not the lines that happen to contain what the user typed.
            Set<Term> matchedTerms = new HashSet<>();
            query.visit(QueryVisitor.termCollector(matchedTerms));
            Set<String> wanted = new HashSet<>();
            for (Term term : matchedTerms) {
                if ("content".equals(term.field())) {
                    wanted.add(term.text());
                }
            }

            List<ContextMatch> matches = new ArrayList<>();
            for (ScoreDoc sd : results.scoreDocs) {
                Document doc     = held.doc(sd.doc);
                String   content = doc.get("content");
                RelevantLines.Selection selection =
                        RelevantLines.from(content, wanted, analyzer);
                matches.add(new ContextMatch(displayPath(doc.get("path"), doc.get("filename")),
                                             sd.score, selection.lines(), selection.elided()));
            }
            return matches;
        } finally {
            release(held);
        }
    }

    /**
     * A searcher to run one search on, with its reader held open until the search is done.
     *
     * <h2>Why a search cannot just read the field</h2>
     *
     * <p>Eight workers share this singleton, and a search is a sequence of calls -- one to score the
     * query, then one per hit to fetch the stored document. Between any two of them another thread
     * can finish an {@code indexFile} or reach the refresh interval, and the refresh closes the
     * reader underneath. Lucene reports that as {@link org.apache.lucene.store.AlreadyClosedException},
     * which becomes "Could not retrieve context" and a turn sent to the model with none of the
     * project in it -- a failure that looks like a project with nothing relevant in it rather than
     * like a bug.</p>
     *
     * <p>Taking a reference under the monitor settles both halves: the monitor makes the reader and
     * the searcher one indivisible pair, and the reference keeps that reader alive for as long as
     * the search needs it however many times the index is refreshed meanwhile. The search itself
     * runs outside the monitor, so concurrent searches still overlap and a search does not hold up
     * an indexing run.</p>
     *
     * @return the searcher to use; the caller must hand it back to {@link #release(IndexSearcher)}
     * @throws IllegalStateException if the engine has been closed
     */
    private synchronized IndexSearcher acquireSearcher() {
        IndexSearcher current = searcher;
        if (current == null || !current.getIndexReader().tryIncRef()) {
            throw new IllegalStateException("ContextEngine has been closed");
        }
        return current;
    }

    /**
     * Gives back a reader taken by {@link #acquireSearcher()}.
     *
     * <p>The last reference closes the files, which is how a reader retired mid-search is eventually
     * released. A failure to let go is a leaked file handle and nothing more, so it is recorded
     * rather than raised: the search it belongs to has already produced its answer.</p>
     *
     * @param held the searcher that was acquired
     */
    private static void release(IndexSearcher held) {
        try {
            held.getIndexReader().decRef();
        } catch (IOException | RuntimeException wontClose) {
            com.eonmux.cadetcoder.logging.DebugLogger.getInstance().debug(
                    "ContextEngine", "An index reader would not close after a search: " + wontClose);
        }
    }

    /**
     * The query to run for what somebody wrote.
     *
     * <h2>Why the query language is never involved</h2>
     *
     * <p>Nobody who uses this types a query language. {@code edit} passes the edit request, the
     * agent loop passes the task, and {@code search} passes the words the user typed. All three are
     * prose, and prose is full of the characters Lucene's parser reserves. One {@code /} opens a
     * regular expression that runs to the end of the text, so any mention of a file path threw:</p>
     *
     * <pre>
     * Cannot parse 'edit src/main/java/.../ColorTheme.java': Lexical error at line 1, column 60.
     * Encountered: &lt;EOF&gt; after prefix "/ColorTheme.java" (in lexical state 2)
     * </pre>
     *
     * <p>Escaping the text and parsing it anyway would answer that and leave a second failure in
     * place: the parser refuses a query of more than {@link IndexSearcher#getMaxClauseCount()}
     * terms, which a pasted specification reaches. Running the text through the analyzer instead
     * settles both. The parser never sees it, so no character is syntax, and the clauses are counted
     * as they are made rather than discovered to be too many at the end.</p>
     *
     * <p>The analyzer is the one the index was built with, so the terms here are the terms the index
     * holds. Repeats are dropped: a word twice in a request is not twice as relevant, and the room
     * it would take belongs to the next word.</p>
     *
     * @param text what to search for, as it was written
     * @return the query, or {@code null} when there is nothing to search for
     * @throws IOException if the analyzer cannot read the text
     */
    private Query queryFor(String text) throws IOException {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream tokens = analyzer.tokenStream("content", text == null ? "" : text)) {
            CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
            tokens.reset();
            while (terms.size() < maxQueryTerms() && tokens.incrementToken()) {
                terms.add(term.toString());
            }
            tokens.end();
        }
        if (terms.isEmpty()) {
            // A search for nothing, which is what a blank request or one made entirely of
            // punctuation comes to. Answered with no matches rather than with a failure.
            return null;
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String term : terms) {
            query.add(new TermQuery(new Term("content", term)), BooleanClause.Occur.SHOULD);
        }
        return query.build();
    }

    /**
     * The most terms one search may carry.
     *
     * <p>{@link #MAX_QUERY_TERMS} is the number this tool wants, and Lucene's own clause limit is
     * the number it will accept. The smaller of the two applies, so raising ours can never build a
     * query Lucene refuses.</p>
     *
     * @return the cap
     */
    private static int maxQueryTerms() {
        return Math.min(MAX_QUERY_TERMS, IndexSearcher.getMaxClauseCount());
    }

    /**
     * A matched file's path as the reader should see it.
     *
     * <p>The index keys documents by absolute path, which is what makes them unique; what a person
     * types and what a model passes to {@code read} is the path relative to the project. A file
     * outside the project keeps its absolute path, because relativising it produces a chain of
     * {@code ../} that is worse than the path it came from.</p>
     *
     * @param absolutePath the path the index holds
     * @param fileName     the name to fall back to when there is no usable path
     * @return the path to show
     */
    private static String displayPath(String absolutePath, String fileName) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return fileName == null ? "(unknown)" : fileName;
        }
        try {
            java.nio.file.Path project = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
                                              .normalize();
            java.nio.file.Path file    = Paths.get(absolutePath).toAbsolutePath().normalize();
            if (file.startsWith(project)) {
                return project.relativize(file).toString();
            }
        } catch (RuntimeException e) {
            // An unparseable path is still worth reporting as the index holds it.
        }
        return absolutePath;
    }

    private void indexFileWithChecksum(File file, String checksum) throws IOException {
        if (writer == null) {
            throw new IllegalStateException("ContextEngine has been closed");
        }
        Document doc = new Document();
        doc.add(new StringField("path", file.getAbsolutePath(), Field.Store.YES));
        doc.add(new TextField("content", TextFiles.readText(file.toPath()), Field.Store.YES));
        doc.add(new StringField("filename", file.getName(), Field.Store.YES));
        doc.add(new StringField("checksum", checksum, Field.Store.YES));
        // Neither committed nor searcher-refreshed here: this runs once per file inside
        // reindex()'s loop, and a Lucene commit is a durable fsync of the whole index. Committing
        // per file made indexing a 438-file project 438 commits instead of one. reindex() commits
        // the batch when the loop is done.
        writer.updateDocument(new Term("path", file.getAbsolutePath()), doc);
    }

    private String computeChecksum(File file) throws IOException {
        try {
            MessageDigest digest        = MessageDigest.getInstance("MD5");
            byte[]        fileBytes     = Files.readAllBytes(file.toPath());
            byte[]        checksumBytes = digest.digest(fileBytes);
            StringBuilder sb            = new StringBuilder();
            for (byte b : checksumBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }
}
