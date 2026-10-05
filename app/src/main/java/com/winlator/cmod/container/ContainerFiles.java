package com.winlator.cmod.container;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/**
 * Java-side helpers for the copy-on-write prefix overlay (see cpp/containeroverlay/README.md).
 *
 * The native library only interposes the Wine processes. Code running inside the app process
 * (registry editing, extracting DXVK, ...) sees the physical "upper" directory of a container,
 * so everything here works on the upper layer and its marker files directly.
 */
public final class ContainerFiles {
    public static final String DOSDEVICES = "dosdevices";

    private ContainerFiles() {}

    public static File upperDir(Container container) {
        return new File(container.getRootDir(), ".wine");
    }

    /** Marks a directory of the upper layer as shadowing the lower one completely. */
    public static boolean markOpaque(File upper, String relPathUnderWine) {
        return writeMarker(new File(new File(new File(upper, ContainerOverlay.OVERLAY_DIR), ContainerOverlay.OPAQUE_DIR), normalize(relPathUnderWine)));
    }

    /**
     * Drops the container's own copy of a file so the base prefix's original shows through again,
     * and clears a whiteout on the path if there is one. This is the overlay equivalent of
     * "restore the original DLL".
     */
    public static boolean removeOverride(File upper, String relPathUnderWine) {
        String rel = normalize(relPathUnderWine);
        if (rel.isEmpty()) return false;
        File upperFile = new File(upper, rel);
        boolean ok = true;
        if (exists(upperFile) && !Files.isDirectory(upperFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.delete(upperFile.toPath());
            }
            catch (IOException e) {
                ok = false;
            }
        }
        return removeWhiteout(upper, rel) && ok;
    }

    private static boolean removeWhiteout(File upper, String rel) {
        File whiteout = new File(new File(new File(upper, ContainerOverlay.OVERLAY_DIR), ContainerOverlay.WHITEOUT_DIR), rel);
        if (!isMarker(whiteout)) return true;
        try {
            Files.delete(whiteout.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean writeMarker(File marker) {
        if (isMarker(marker)) return true;
        if (exists(marker) && !deleteRecursively(marker)) return false;
        try {
            Files.createDirectories(marker.getParentFile().toPath());
            Files.createFile(marker.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean isMarker(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static boolean deleteRecursively(File file) {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!file.canWrite()) file.setWritable(true, true);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        try {
            Files.deleteIfExists(file.toPath());
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static String normalize(String relPath) {
        if (relPath == null) return "";
        String rel = relPath.replace('\\', '/');
        while (rel.contains("//")) rel = rel.replace("//", "/");
        while (rel.startsWith("/")) rel = rel.substring(1);
        while (rel.endsWith("/")) rel = rel.substring(0, rel.length() - 1);
        return rel;
    }
}
