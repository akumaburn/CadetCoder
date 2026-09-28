package com.eonmux.cadetcoder.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writing over a file that already exists.
 *
 * <h2>What was missing</h2>
 *
 * <p>Every writer in this tool stages its content beside the target and moves the finished file
 * into place, so the target is never seen half-written. {@link java.nio.file.Files#createTempFile}
 * creates that staging file readable and writable by its owner alone, and the move carries those
 * permissions onto the target. Editing a build script therefore left it at {@code 0600}: no longer
 * executable, no longer readable by anyone else, and nothing said so. The content was right and the
 * file no longer worked.</p>
 *
 * <h2>What is locked here</h2>
 *
 * <p>That a replaced file keeps the permissions it had, that a file being created keeps the
 * tighter default nobody has said otherwise about, and that a filesystem with no POSIX permissions
 * at all is still written to.</p>
 */
class AreplacedFileIsStillTheFileItWasTest {

    @Test
    void anExecutableFileIsStillExecutableAfterwards() throws IOException {
        Path script = onePosixFile("#!/bin/sh\necho one\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));

        AtomicFileWrite.writeString(script, "#!/bin/sh\necho two\n");

        assertThat(Files.getPosixFilePermissions(script))
                .isEqualTo(PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(Files.readString(script)).isEqualTo("#!/bin/sh\necho two\n");
    }

    @Test
    void afileOthersCouldReadIsStillOneOthersCanRead() throws IOException {
        Path notes = onePosixFile("one\n");
        Files.setPosixFilePermissions(notes, PosixFilePermissions.fromString("rw-r--r--"));

        AtomicFileWrite.writeString(notes, "two\n");

        assertThat(Files.getPosixFilePermissions(notes))
                .contains(PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
    }

    @Test
    void afileBeingCreatedIsNoWiderOpenThanItHasToBe() throws IOException {
        Path folder = Files.createTempDirectory("cadet-write");
        assumeTrue(Files.getFileStore(folder).supportsFileAttributeView("posix"));
        Path fresh = folder.resolve("new.txt");

        AtomicFileWrite.writeString(fresh, "hello\n");

        Set<PosixFilePermission> given = Files.getPosixFilePermissions(fresh);
        assertThat(given)
                .as("nothing has said what a brand new file should be, so it stays narrow")
                .doesNotContain(PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_WRITE,
                                PosixFilePermission.GROUP_WRITE);
        assertThat(Files.readString(fresh)).isEqualTo("hello\n");
    }

    @Test
    void awriteStillHappensWhereThereAreNoPermissionsToKeep() throws IOException {
        // The check has to survive a filesystem that answers nothing about POSIX modes, because
        // the content is the point and the mode is what is being preserved alongside it.
        Path notes = Files.createTempFile("cadet-write", ".txt");

        AtomicFileWrite.writeString(notes, "written\n");

        assertThat(Files.readString(notes)).isEqualTo("written\n");
    }

    /** A file on a filesystem that has POSIX permissions, or the test is not the one to run. */
    private static Path onePosixFile(String contents) throws IOException {
        Path file = Files.createTempFile("cadet-write", ".txt");
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView("posix"));
        Files.writeString(file, contents);
        return file;
    }
}
