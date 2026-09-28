package com.eonmux.cadetcoder.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Files holding a long-lived credential must not be readable by other local accounts.
 */
class OwnerOnlyFileTest {

    @TempDir
    Path directory;

    private static boolean posixAvailable(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    @Test
    void aCredentialFileEndsUpReadableByItsOwnerAlone() throws IOException {
        Path file = Files.writeString(directory.resolve("token.json"), "{}");
        assumeTrue(posixAvailable(file), "POSIX permissions unavailable on this filesystem");
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                                                   PosixFilePermission.OWNER_WRITE,
                                                   PosixFilePermission.GROUP_READ,
                                                   PosixFilePermission.OTHERS_READ));

        assertThat(OwnerOnlyFile.restrict(file)).isNull();

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                           PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void anAbsentFileIsNothingToDoRatherThanAFailure() {
        assertThat(OwnerOnlyFile.restrict(directory.resolve("never-written.json"))).isNull();
        assertThat(OwnerOnlyFile.restrict(null)).isNull();
    }

    /**
     * A credential file is owner-only from the moment it exists, not from shortly afterwards.
     *
     * <p><b>The defect</b>: both writers of a secret restricted the file, then wrote it. Restricting
     * a file that is not there yet does nothing, so on the first save -- the one that creates it --
     * the secret went to disk at whatever the umask allowed, world-readable on most machines, and
     * was narrowed only after it was already there to be read.</p>
     */
    @Test
    void afileMadeForAcredentialIsOwnerOnlyBeforeAnythingIsWrittenToIt() throws IOException {
        Path file = directory.resolve("token.json");
        assumeTrue(posixAvailable(directory), "POSIX permissions unavailable on this filesystem");

        assertThat(OwnerOnlyFile.createOwnerOnly(file)).isNull();

        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.getPosixFilePermissions(file))
                .as("no window in which the file exists wider than this")
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                           PosixFilePermission.OWNER_WRITE);

        Files.writeString(file, "{\"github_oauth_token\": \"gho_secret\"}");

        assertThat(Files.getPosixFilePermissions(file))
                .as("writing into it does not widen it")
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                           PosixFilePermission.OWNER_WRITE);
    }

    /** One that is already there is narrowed rather than refused, and nothing in it is lost. */
    @Test
    void afileThatIsAlreadyThereIsNarrowedAndLeftAsItWas() throws IOException {
        Path file = Files.writeString(directory.resolve("token.json"), "{\"kept\": true}");
        assumeTrue(posixAvailable(file), "POSIX permissions unavailable on this filesystem");
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ,
                                                   PosixFilePermission.OWNER_WRITE,
                                                   PosixFilePermission.OTHERS_READ));

        assertThat(OwnerOnlyFile.createOwnerOnly(file)).isNull();

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                           PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readString(file)).isEqualTo("{\"kept\": true}");
    }

    @Test
    void nowhereToMakeAfileIsNothingToDoRatherThanAfailure() {
        assertThat(OwnerOnlyFile.createOwnerOnly(null)).isNull();
    }

    /**
     * The tool's own directory holds sessions, logs and a search index of the user's code, all of
     * which can carry file contents and command output. Restricting the directory protects every
     * file in it, whatever mode each one was written with.
     */
    @Test
    void adirectoryIsNarrowedToItsOwnerAlone() throws IOException {
        Path folder = Files.createDirectory(directory.resolve("cadet"));
        assumeTrue(posixAvailable(folder), "POSIX permissions unavailable on this filesystem");
        Files.setPosixFilePermissions(folder, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(OwnerOnlyFile.restrictDirectory(folder)).isNull();

        assertThat(Files.getPosixFilePermissions(folder))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                                           PosixFilePermission.OWNER_WRITE,
                                           PosixFilePermission.OWNER_EXECUTE);
    }

    @Test
    void anAbsentDirectoryIsNothingToDoRatherThanAFailure() {
        assertThat(OwnerOnlyFile.restrictDirectory(directory.resolve("never-made"))).isNull();
        assertThat(OwnerOnlyFile.restrictDirectory(null)).isNull();
    }
}
