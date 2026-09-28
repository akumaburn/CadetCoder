package com.eonmux.cadetcoder.patch;

import java.util.List;

/**
 * One file a patch changes, and every change it makes to it.
 *
 * @param path  the file, relative to wherever the patch is applied
 * @param kind  whether the patch adds the file, edits it, or removes it
 * @param hunks the changes, in the order the patch states them
 */
public record PatchedFile(String path, PatchKind kind, List<PatchedHunk> hunks) {

    public PatchedFile {
        hunks = List.copyOf(hunks);
    }
}
