package com.eonmux.cadetcoder.build;

import com.eonmux.cadetcoder.OutputFormatter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Installing the latest release over the jar this program is running from.
 *
 * <h2>The order the steps go in, and what each one protects</h2>
 *
 * <p>The download lands beside the target and is moved onto it only once complete, so a connection
 * that dies halfway leaves the running jar exactly as it was rather than leaving a half-jar where
 * the program lives. The checksum is checked before that move, when the release ships one, so
 * bytes that do not match what the repository published never take the old jar's place. And the
 * jar being replaced is copied aside first, so the whole operation is undone by hand with one
 * rename if the new build turns out not to start.</p>
 *
 * <p>Nothing here restarts the program. A running Java process cannot replace its own classes, so
 * the update is complete when the next start of {@code cadet} runs the new bytes, and the command
 * that called this says so.</p>
 */
public final class SelfUpdate {

    /** The asset name every release of this program publishes its jar under. */
    public static final String JAR_ASSET = "cadet-latest.jar";

    private static final String CHECKSUM_ASSET = JAR_ASSET + ".sha256";

    private SelfUpdate() {
    }

    /**
     * The jar this process is running from.
     *
     * @return its path, or {@code null} when the code was not loaded from a jar file -- an IDE or
     *         a classes directory -- which no amount of downloading can update in place
     */
    public static Path currentJar() {
        try {
            Path location = Path.of(SelfUpdate.class.getProtectionDomain().getCodeSource()
                                            .getLocation().toURI());
            return Files.isRegularFile(location) && location.toString().endsWith(".jar")
                   ? location.toAbsolutePath() : null;
        } catch (Exception | LinkageError unreadableLocation) {
            return null;
        }
    }

    /**
     * Fetches the release and puts it where this program is running from.
     *
     * @param release the release to install, as a check has just reported it
     * @param target  the jar file to replace
     * @return the exit code for the calling command: {@code 0} installed, {@code 1} not
     */
    public static int install(Release release, Path target) {
        Path directory = target.toAbsolutePath().getParent();
        Path backup    = target.resolveSibling(target.getFileName() + ".bak");
        Path download  = null;
        try {
            OutputFormatter.printInfo("Downloading " + release.tag() + " from " + release.jarUrl());
            download = Files.createTempFile(directory, "cadet-update", ".jar");
            long size = downloadTo(release.jarUrl(), download);

            String expected = release.sha256Url() == null ? null : downloadText(release.sha256Url());
            if (expected != null) {
                String actual = sha256(download);
                if (!expected.trim().toLowerCase().startsWith(actual)) {
                    OutputFormatter.printError("The downloaded jar does not match the checksum the"
                                               + " release published; installing nothing.");
                    return 1;
                }
                OutputFormatter.printInfo("Checksum verified (" + actual.substring(0, 12) + "...).");
            }

            // The old jar is the only copy of a working program, so it is moved aside before the
            // new one takes its place, and moved back if the replacement cannot happen -- the one
            // state this must never leave behind is a missing jar where the program lives.
            Files.deleteIfExists(backup);
            Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.move(download, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                // A filesystem that cannot promise the atomic move still gets the completed
                // download rather than a truncated one; the window between the two moves is the
                // only cost.
                Files.move(download, target, StandardCopyOption.REPLACE_EXISTING);
            }
            download = null; // Moved into place, not to be cleaned up here.

            OutputFormatter.printSuccess("Updated to " + release.tag() + " (build "
                                         + release.commitShort() + ", " + size + " bytes).");
            OutputFormatter.printInfo("The previous build is kept at " + backup + "; the update"
                                      + " takes effect the next time you start cadet.");
            return 0;
        } catch (IOException | RuntimeException | LinkageError failedSomewhere) {
            // Whatever failed, put the working jar back first and say why afterwards.
            try {
                if (!Files.exists(target) && Files.exists(backup)) {
                    Files.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (IOException restorationAlsoFailed) {
                OutputFormatter.printError("The previous build could not be restored from "
                                           + backup + "; move it back by hand.");
            }
            if (download != null) {
                try {
                    Files.deleteIfExists(download);
                } catch (IOException ignored) {
                    // The half-download is worthless; failing to delete it is not worth reporting
                    // over the failure that got here first.
                }
            }
            OutputFormatter.printError("The update did not install: " + failedSomewhere.getMessage());
            return 1;
        }
    }

    /** @return whether the running program can be replaced here at all */
    public static boolean canUpdateTheRunningProgram() {
        return currentJar() != null;
    }

    /* ------------------------------------------------------------------ *
     * The download, and what it is checked against                        *
     * ------------------------------------------------------------------ */

    /** @return the number of bytes downloaded */
    private static long downloadTo(String url, Path destination) throws IOException {
        HttpRequest request = com.eonmux.cadetcoder.net.HttpRequests.to(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "cadet/" + BuildInfo.current().version() + " (update)")
                .GET()
                .build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = HttpClientHolder.client().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("the download was interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new IOException("the download answered HTTP " + response.statusCode());
        }
        try (InputStream body = response.body();
             java.io.OutputStream out = Files.newOutputStream(destination,
                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            return body.transferTo(out);
        }
    }

    private static String downloadText(String url) throws IOException {
        HttpRequest request = com.eonmux.cadetcoder.net.HttpRequests.to(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "cadet/" + BuildInfo.current().version() + " (update)")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = HttpClientHolder.client().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("the checksum download was interrupted", interrupted);
        }
        if (response.statusCode() != 200) {
            throw new IOException("the checksum download answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** @return the lowercase SHA-256 of the file, the form a checksum asset publishes it in */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException neverOnAStandardJdk) {
            throw new IOException("this JDK cannot compute SHA-256", neverOnAStandardJdk);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * One client for the whole feature. {@link UpdateCheck} has its own for its short calls; the
     * download here wants no connect timeout fighting a slow release host, and its own redirects.
     */
    private static final class HttpClientHolder {
        private static final java.net.http.HttpClient CLIENT =
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .build();

        private static java.net.http.HttpClient client() {
            return CLIENT;
        }
    }
}
