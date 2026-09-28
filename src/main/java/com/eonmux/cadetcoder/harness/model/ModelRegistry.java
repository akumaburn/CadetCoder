package com.eonmux.cadetcoder.harness.model;

import com.eonmux.cadetcoder.harness.Json;
import com.eonmux.cadetcoder.harness.model.lang.Program;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every version of the agent's theory, kept under the name it can never stop having.
 *
 * <h2>Why a store and not a file</h2>
 *
 * <p>A model that is edited in place has no history, and a certificate issued against it means
 * nothing an hour later: the source it certified is gone and the certificate still looks current,
 * so a plan can be committed against a stale model after an edit. Here a version is named by the
 * digest of its source, so a certificate names exactly one text forever, and {@link #load} refuses
 * a file that no longer hashes to the name it is filed under rather than handing back something
 * that is not the model that was certified.</p>
 *
 * <h2>Why the history is kept</h2>
 *
 * <p>The one question that needs more than the current version is whether the model is being
 * corrected or merely patched. {@link #epicycleWarning()} answers it from what the store remembers:
 * complexity that grows while the rules the ledger exercises stay put.</p>
 */
public final class ModelRegistry {

    /** What the store is called inside a workspace. */
    public static final String DIRECTORY = "models";

    /** What a stored model source is called, after its digest. */
    public static final String SOURCE_SUFFIX = ".model";

    /** What a stored certificate is called, after the digest of the model it certifies. */
    public static final String CERTIFICATE_SUFFIX = ".cert.json";

    /** The name that always means the most recent version. */
    public static final String LATEST = "latest";

    /** Where a certificate says how many of a model's rules the ledger has exercised. */
    public static final String COVERED_PATH = "coverage.covered";

    /** How many versions back an epicycle is looked for. */
    public static final int EPICYCLE_WINDOW = 3;

    private static final String INDEX_NAME = "index.jsonl";

    private final Path                     root;
    private final Path                     index;
    private final Map<String, ModelRecord> records = new LinkedHashMap<>();

    /**
     * Opens the store in a workspace, reading anything already there.
     *
     * @param workspace the directory the harness keeps its state in
     * @throws ModelStoreException if the store cannot be created or does not read back
     */
    public ModelRegistry(Path workspace) {
        this.root  = workspace.resolve(DIRECTORY);
        this.index = root.resolve(INDEX_NAME);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new ModelStoreException("cannot create the model store at " + root, e);
        }
        read();
    }

    private void read() {
        if (!Files.exists(index)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ModelStoreException("cannot read the model index at " + index, e);
        }
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                ModelRecord record = entry(lines.get(i), i + 1);
                records.put(record.digest(), record);
            }
        }
    }

    private ModelRecord entry(String line, int number) {
        try {
            Object value = Json.parse(line);
            if (!(value instanceof Map)) {
                throw new ModelStoreException("model index line " + number + " is not a record");
            }
            @SuppressWarnings ("unchecked")
            Map<String, Object> record = (Map<String, Object>) value;
            return ModelRecord.fromValue(record);
        } catch (IllegalArgumentException e) {
            throw new ModelStoreException("model index line " + number + " is not a record: "
                                          + e.getMessage(), e);
        }
    }

    /** Where the store keeps its files. */
    public Path root() {
        return root;
    }

    /**
     * Stores a version, refusing it first if it is not a model at all.
     *
     * @param source       the model as written
     * @param ledgerLength how many transitions had happened when it was written
     * @param note         why it was written
     * @return the loaded model
     * @throws ModelException      if the source does not fit {@link ModelContract}
     * @throws ModelStoreException if it cannot be stored
     */
    public WorldModel save(String source, int ledgerLength, String note) {
        return save(source, ledgerLength, null, note);
    }

    /**
     * Stores a version, saying which version it was written from.
     *
     * @param source       the model as written
     * @param ledgerLength how many transitions had happened when it was written
     * @param parent       the version it came from; {@code null} means whichever is latest
     * @param note         why it was written
     * @return the loaded model
     * @throws ModelException      if the source does not fit {@link ModelContract}
     * @throws ModelStoreException if it cannot be stored
     */
    public WorldModel save(String source, int ledgerLength, String parent, String note) {
        WorldModel model  = WorldModel.load(source);
        String     digest = model.digest();
        if (records.containsKey(digest)) {
            return model;
        }
        ModelRecord record = new ModelRecord(digest, parent == null ? latest() : resolve(parent),
                                             ledgerLength, model.complexity(),
                                             note == null ? "" : note);
        write(sourceFile(digest), source);
        append(record);
        records.put(digest, record);
        return model;
    }

    /**
     * Reads a version back.
     *
     * @param reference a digest, a unique prefix of one, or {@link #LATEST}
     * @return the model that was stored
     * @throws ModelStoreException if no version answers to that name, or the file has been edited
     * @throws ModelException      if what was stored no longer fits the contract
     */
    public WorldModel load(String reference) {
        return WorldModel.load(source(reference));
    }

    /**
     * The source a version was stored with.
     *
     * @param reference a digest, a unique prefix of one, or {@link #LATEST}
     * @return the source exactly as it was saved
     * @throws ModelStoreException if no version answers to that name, or the file has been edited
     */
    public String source(String reference) {
        String digest  = resolve(reference);
        Path   file    = sourceFile(digest);
        String source  = contents(file);
        String written = Json.digestOfText(source, Program.DIGEST_LENGTH);
        if (!written.equals(digest)) {
            throw new ModelStoreException("the source filed under " + digest + " now reads as "
                                          + written + "; a model version cannot change what it says");
        }
        return source;
    }

    /**
     * Which version a name means.
     *
     * @param reference a digest, a unique prefix of one, or {@link #LATEST}; nothing means latest
     * @return the full digest
     * @throws ModelStoreException if the name matches no version, or more than one
     */
    public String resolve(String reference) {
        if (reference == null || reference.isEmpty() || LATEST.equals(reference)) {
            String latest = latest();
            if (latest == null) {
                throw new ModelStoreException("there are no models in " + root + " yet");
            }
            return latest;
        }
        if (records.containsKey(reference)) {
            return reference;
        }
        List<String> matches = new ArrayList<>();
        for (String digest : records.keySet()) {
            if (digest.startsWith(reference)) {
                matches.add(digest);
            }
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            throw new ModelStoreException("there is no model called " + reference);
        }
        throw new ModelStoreException(reference + " names more than one model: "
                                      + String.join(", ", matches));
    }

    /** The most recent version, or {@code null} when nothing has been stored. */
    public String latest() {
        String latest = null;
        for (String digest : records.keySet()) {
            latest = digest;
        }
        return latest;
    }

    /** Every version, oldest first. */
    public List<String> digests() {
        return List.copyOf(records.keySet());
    }

    /** How many versions have been stored. */
    public int size() {
        return records.size();
    }

    /**
     * What the store remembers about a version.
     *
     * @param reference a digest, a unique prefix of one, or {@link #LATEST}
     * @return its record
     * @throws ModelStoreException if the name matches no version, or more than one
     */
    public ModelRecord record(String reference) {
        return records.get(resolve(reference));
    }

    /**
     * Files a certificate against the version it was issued for.
     *
     * @param reference   a digest, a unique prefix of one, or {@link #LATEST}
     * @param certificate the certificate as a plain value
     * @throws ModelStoreException if the name matches no version, or it cannot be written
     */
    public void saveCertificate(String reference, Object certificate) {
        write(certificateFile(resolve(reference)), Json.canonical(certificate));
    }

    /**
     * The certificate a version last earned.
     *
     * @param reference a digest, a unique prefix of one, or {@link #LATEST}
     * @return the certificate, or {@code null} when nothing has certified this version
     * @throws ModelStoreException if the name matches no version, or the file cannot be read
     */
    public Object certificate(String reference) {
        Path file = certificateFile(resolve(reference));
        return Files.exists(file) ? Json.parse(contents(file)) : null;
    }

    /**
     * Whether the recent history looks like patching rather than learning.
     *
     * @return the warning, or {@code null} when there is nothing to warn about
     */
    public String epicycleWarning() {
        return epicycleWarning(EPICYCLE_WINDOW);
    }

    /**
     * Whether a run of recent versions looks like patching rather than learning.
     *
     * @param window how many versions back to look
     * @return the warning, or {@code null} when there is nothing to warn about
     */
    public String epicycleWarning(int window) {
        List<String>      recent   = digests();
        List<ModelRecord> versions = new ArrayList<>();
        List<Integer>     covered  = new ArrayList<>();
        for (String digest : recent.subList(Math.max(0, recent.size() - window), recent.size())) {
            versions.add(records.get(digest));
            covered.add(coveredArms(digest));
        }
        return Epicycles.warning(versions, covered);
    }

    /** How many arms the ledger had exercised, as the certificate recorded it. */
    private Integer coveredArms(String digest) {
        Object certificate = certificate(digest);
        if (certificate == null || !Json.has(certificate, COVERED_PATH)) {
            return null;
        }
        Object count = Json.at(certificate, COVERED_PATH);
        return count instanceof Number number ? number.intValue() : null;
    }

    private Path sourceFile(String digest) {
        return root.resolve(digest + SOURCE_SUFFIX);
    }

    private Path certificateFile(String digest) {
        return root.resolve(digest + CERTIFICATE_SUFFIX);
    }

    private String contents(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ModelStoreException("cannot read " + file, e);
        }
    }

    private void append(ModelRecord record) {
        try {
            Files.writeString(index, Json.canonical(record.toValue()) + System.lineSeparator(),
                              StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new ModelStoreException("cannot append to the model index at " + index, e);
        }
    }

    /** A half-written model source would be a version that hashes to nothing, so nothing is. */
    private void write(Path file, String contents) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, file.getFileName().toString(), ".writing");
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ModelStoreException("cannot write " + file, e);
        } finally {
            discard(temporary);
        }
    }

    private static void discard(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException leftBehind) {
            // The write has either succeeded or already thrown. Replacing that answer with a
            // complaint about a stray file would hide the one the caller needs.
        }
    }
}
