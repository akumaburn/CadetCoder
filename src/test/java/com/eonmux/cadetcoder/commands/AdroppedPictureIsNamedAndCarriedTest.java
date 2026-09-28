package com.eonmux.cadetcoder.commands;

import com.eonmux.cadetcoder.ai.PromptImage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What dropping a picture on the prompt does.
 *
 * <h2>Why the marker says "image"</h2>
 *
 * <p>Dropping a file on a terminal pastes its path, and a dropped picture is two offers at once:
 * the path, which any command can open, and the picture itself, which only a model that reads
 * images can use. The line should say which was offered, because a text-only model is told about
 * the file and never shown the picture.</p>
 *
 * <h2>Why the line decides, not the drop</h2>
 *
 * <p>Deleting the marker is the only way back out of a drop. A picture attached when the file
 * landed, rather than when Enter was pressed, would be sent by a question that no longer mentions
 * it.</p>
 */
public class AdroppedPictureIsNamedAndCarriedTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final ShellPastes pastes = new ShellPastes();

    private Path png(String name) throws IOException {
        byte[] bytes = new byte[40];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0,
                         bytes, 0, 8);
        Path file = folder.newFile(name).toPath();
        Files.write(file, bytes);
        return file;
    }

    @Test
    public void adroppedPictureIsMarkedAsOneAndStillStandsForItsPath() throws IOException {
        Path file = png("shot.png");

        String marker = pastes.insertionFor("'" + file + "' ");

        assertThat(marker).isEqualTo("[#1: image shot.png]");
        assertThat(pastes.expand("what is in " + marker))
                .isEqualTo("what is in " + file.toAbsolutePath());
    }

    @Test
    public void thepictureItselfIsCarriedByAlineThatStillNamesIt() throws IOException {
        Path   file   = png("shot.png");
        String marker = pastes.insertionFor(file.toString());

        List<PromptImage> carried = pastes.picturesIn("what is in " + marker);

        assertThat(carried).hasSize(1);
        assertThat(carried.get(0).name()).isEqualTo("shot.png");
        assertThat(carried.get(0).mediaType()).isEqualTo("image/png");
    }

    @Test
    public void amarkerDeletedBeforeEnterTakesItsPictureWithIt() throws IOException {
        pastes.insertionFor(png("shot.png").toString());

        assertThat(pastes.picturesIn("never mind, what time is it")).isEmpty();
    }

    @Test
    public void afileThatIsNotApictureIsNamedAndNothingIsCarried() throws IOException {
        Path file = folder.newFile("Foo.java").toPath();
        Files.writeString(file, "class Foo {}");

        String marker = pastes.insertionFor(file.toString());

        assertThat(marker).isEqualTo("[#1: Foo.java]");
        assertThat(pastes.picturesIn("read " + marker)).isEmpty();
    }

    @Test
    public void everyPictureAlineStillNamesIsCarried() throws IOException {
        String first  = pastes.insertionFor(png("before.png").toString());
        String second = pastes.insertionFor(png("after.png").toString());

        assertThat(pastes.picturesIn("compare " + first + " with " + second))
                .extracting(PromptImage::name)
                .containsExactly("before.png", "after.png");
    }
}
