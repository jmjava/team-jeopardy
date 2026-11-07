package com.jmjava.teamjeopardy.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Files an ingest may read. Symlinks are refused so a checked-out tree cannot
 * point a source name at a file outside the root.
 */
public final class IngestedTree {

    private IngestedTree() {
    }

    public static boolean isContainedRegularFile(Path root, Path candidate) {
        if (root == null || candidate == null) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(candidate)) {
                return false;
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Path realRoot = root.toRealPath();
            Path realFile = candidate.toRealPath();
            return realFile.startsWith(realRoot);
        } catch (IOException ex) {
            return false;
        }
    }
}
