package com.eonmux.cadetcoder.build;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Installing a release over the jar this program runs from.
 *
 * <p><b>What is locked here</b>: that the jar only ever changes to a complete download that
 * matches the checksum the release published, that the replaced jar is kept so the move can be
 * undone, and that every way the install can fail leaves the working jar exactly where it was.
 * The server here is a real one, because the failure modes this exists for -- a truncated body, a
 * checksum that does not match -- are properties of a transfer, not of a function.</p>
 */
public class AnUpdateReplacesTheRunningJarTest {

    /** Bytes a release jar might be; a real jar is not needed to test what replaces what. */
    private static final byte[] NEW_JAR     = "the new build's bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CURRENT_JAR = "the running build's bytes".getBytes(StandardCharsets.UTF_8);

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HttpServer server;
    private Path       jar;

    @Before
    public void setUp() throws Exception {
        jar = tempFolder.getRoot().toPath().resolve("cadet-latest.jar");
        Files.write(jar, CURRENT_JAR);

        server = HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private void serve(byte[] jarBytes, String checksum) {
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] body;
            if (path.endsWith(".sha256")) {
                body = checksum.getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/404")) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            } else {
                body = jarBytes;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private static String base() {
        return "http://localhost:0"; // Replaced with the real port by the callers.
    }

    private Release release() {
        String root = "http://localhost:" + server.getAddress().getPort();
        return new Release("v1.1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                           Instant.parse("2026-11-14T08:00:00Z"), null,
                           root + "/cadet-latest.jar", root + "/cadet-latest.jar.sha256");
    }

    @Test
    public void anUpdateReplacesTheJarAndKeepsTheBuildItReplaced() throws Exception {
        serve(NEW_JAR, sha256(NEW_JAR));

        int exit = SelfUpdate.install(release(), jar);

        assertThat(exit).isZero();
        assertThat(jar).usingCharset(StandardCharsets.UTF_8).hasContent("the new build's bytes");
        Path backup = jar.resolveSibling("cadet-latest.jar.bak");
        assertThat(backup).usingCharset(StandardCharsets.UTF_8)
                .hasContent("the running build's bytes");
        // No half-downloads left lying beside the jar.
        try (var leftovers = Files.list(tempFolder.getRoot().toPath())) {
            assertThat(leftovers.map(Path::getFileName).map(Path::toString))
                    .containsOnly("cadet-latest.jar", "cadet-latest.jar.bak");
        }
    }

    @Test
    public void aDownloadThatDoesNotMatchTheChecksumReplacesNothing() throws Exception {
        serve(NEW_JAR, "deadbeef");

        int exit = SelfUpdate.install(release(), jar);

        assertThat(exit).isNotZero();
        assertThat(jar).usingCharset(StandardCharsets.UTF_8)
                .hasContent("the running build's bytes");
        assertThat(jar.resolveSibling("cadet-latest.jar.bak")).doesNotExist();
    }

    @Test
    public void aDownloadThatNeverArrivesReplacesNothing() {
        serve(NEW_JAR, sha256(NEW_JAR));
        Release unreachable = new Release("v1.1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                          Instant.parse("2026-11-14T08:00:00Z"), null,
                                          base() + "/404", null);

        int exit = SelfUpdate.install(unreachable, jar);

        assertThat(exit).isNotZero();
        assertThat(jar).usingCharset(StandardCharsets.UTF_8)
                .hasContent("the running build's bytes");
        assertThat(jar.resolveSibling("cadet-latest.jar.bak")).doesNotExist();
    }

    @Test
    public void anInstallWithoutAChecksumStillReplacesTheJar() throws Exception {
        serve(NEW_JAR, null);
        Release withoutChecksum = new Release("v1.1", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                                              Instant.parse("2026-11-14T08:00:00Z"), null,
                                              "http://localhost:" + server.getAddress().getPort()
                                              + "/cadet-latest.jar", null);

        int exit = SelfUpdate.install(withoutChecksum, jar);

        assertThat(exit).isZero();
        assertThat(jar).usingCharset(StandardCharsets.UTF_8).hasContent("the new build's bytes");
    }

    /** The checksum the release would publish for these bytes. */
    private static String sha256(byte[] bytes) {
        try {
            Path temp = Files.createTempFile("sha256-source", ".bin");
            try {
                Files.write(temp, bytes);
                return SelfUpdate.sha256(temp);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception couldNotHash) {
            throw new IllegalStateException(couldNotHash);
        }
    }

}
