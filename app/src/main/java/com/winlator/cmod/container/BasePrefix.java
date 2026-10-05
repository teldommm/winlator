package com.winlator.cmod.container;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared, read-only lower layer of the prefix overlay: one full Wine prefix per Wine/Proton
 * version, stored at {@code <wine dir>/base_prefix/.wine}. Containers only keep what differs.
 *
 * The base is built with the same code that used to build every container's own prefix
 * (container pattern + common DLLs copied from the Wine build + container_pattern_common).
 * It is rebuilt when this class, the Wine version or the imagefs version changes.
 */
public final class BasePrefix {
    private static final String TAG = "BasePrefix";
    // 2: common DLLs are hard-linked to the Wine build instead of copied (a v1 base is a full copy).
    private static final int BUILD_VERSION = 2;
    private static final List<String> BASE_DRIVES = Arrays.asList("c:", "z:");
    public static final String DIR_NAME = "base_prefix";
    static final String COMPLETE_MARKER = ".complete";
    static final String BUILDING_MARKER = ".building";
    private static final String STAGING_DIR = ".staging";
    private static final String OLD_DIR = ".wine.old";
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private BasePrefix() {}

    private static Object lockFor(String key) {
        synchronized (LOCKS) {
            Object lock = LOCKS.get(key);
            if (lock == null) {
                lock = new Object();
                LOCKS.put(key, lock);
            }
            return lock;
        }
    }

    public static File getBaseDir(Context context, ContentsManager contentsManager, String wineVersion) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        if (wineInfo.path == null || wineInfo.path.isEmpty()) return null;
        return new File(wineInfo.path, DIR_NAME);
    }

    /**
     * Returns the base prefix ({@code .../base_prefix/.wine}) for a Wine version, building it first
     * if it does not exist yet. Returns null if it cannot be provided.
     */
    public static File ensure(Context context, ContentsManager contentsManager, String wineVersion) {
        File baseDir = getBaseDir(context, contentsManager, wineVersion);
        if (baseDir == null) {
            Log.w(TAG, "No base prefix location for wine version " + wineVersion);
            return null;
        }
        int imgVersion = ImageFs.find(context).getVersion();
        String identity = BUILD_VERSION + ":" + wineVersion + ":" + imgVersion;
        File wineDir = new File(baseDir, ".wine");
        synchronized (lockFor(baseDir.getAbsolutePath())) {
            if (isComplete(baseDir, identity)) return wineDir;
            Log.i(TAG, "Building base prefix for " + wineVersion + " at " + baseDir);
            if (build(context, contentsManager, wineVersion, baseDir, identity)) return wineDir;
            if (wineDir.isDirectory() && new File(baseDir, COMPLETE_MARKER).isFile()) {
                Log.w(TAG, "Base prefix rebuild failed for " + wineVersion + ", keeping the previous base");
                return wineDir;
            }
            return null;
        }
    }

    static boolean isComplete(File baseDir, String identity) {
        File marker = new File(baseDir, COMPLETE_MARKER);
        if (!marker.isFile() || new File(baseDir, BUILDING_MARKER).exists()) return false;
        if (!new File(baseDir, ".wine").isDirectory()) return false;
        String content = FileUtils.readString(marker);
        return content != null && content.trim().equals(identity);
    }

    /** Keeps only the drives the base needs: c: (relative link) and z: pointing at /. */
    static boolean normalize(File wineDir) {
        File dosdevices = new File(wineDir, ContainerFiles.DOSDEVICES);
        File[] entries = dosdevices.listFiles();
        boolean ok = true;
        if (entries != null) {
            for (File entry : entries) {
                if (BASE_DRIVES.contains(entry.getName().toLowerCase())) continue;
                ok &= ContainerFiles.deleteRecursively(entry);
            }
        }
        return ok & ensureRootDrive(wineDir);
    }

    static boolean ensureRootDrive(File wineDir) {
        java.nio.file.Path link = new File(new File(wineDir, ContainerFiles.DOSDEVICES), "z:").toPath();
        try {
            if (java.nio.file.Files.isSymbolicLink(link) && "/".equals(java.nio.file.Files.readSymbolicLink(link).toString())) return true;
            java.nio.file.Files.deleteIfExists(link);
            java.nio.file.Files.createSymbolicLink(link, java.nio.file.Paths.get("/"));
            return true;
        }
        catch (java.io.IOException e) {
            Log.w(TAG, "Could not point z: at / in " + wineDir + ": " + e);
            return false;
        }
    }

    private static void abortBuild(File staging, File building, File wineDir, File baseDir) {
        FileUtils.delete(staging);
        building.delete();
        if (!wineDir.exists()) baseDir.delete();
    }

    private static boolean build(Context context, ContentsManager contentsManager, String wineVersion, File baseDir, String identity) {
        File staging = new File(baseDir, STAGING_DIR);
        File building = new File(baseDir, BUILDING_MARKER);
        File wineDir = new File(baseDir, ".wine");
        File oldDir = new File(baseDir, OLD_DIR);
        try {
            if (!baseDir.getParentFile().isDirectory()) {
                Log.e(TAG, "Wine content for " + wineVersion + " is missing at " + baseDir.getParentFile());
                return false;
            }
            if (!baseDir.isDirectory() && !baseDir.mkdir()) return false;
            if (!FileUtils.writeString(building, identity)) {
                Log.e(TAG, "Failed to write the build marker at " + building);
                return false;
            }
            FileUtils.delete(staging);
            if (!staging.mkdirs()) {
                building.delete();
                return false;
            }

            File stagingWine = new File(staging, ".wine");
            ContainerManager containerManager = new ContainerManager(context);

            // 1. Wine container pattern + the common DLLs copied from the Wine build.
            if (!containerManager.extractContainerPatternFile(null, wineVersion, contentsManager, staging, null)) {
                Log.e(TAG, "Failed to extract the container pattern for " + wineVersion);
                abortBuild(staging, building, wineDir, baseDir);
                return false;
            }

            // 2. container_pattern_common is laid out as home/xuser/.wine/...; remap it onto the base.
            String commonPrefix = new File(staging, "home/" + ImageFs.USER + "/.wine").getAbsolutePath();
            OnExtractFileListener commonRemap = (file, size) -> {
                String path = file.getAbsolutePath();
                if (!path.equals(commonPrefix) && !path.startsWith(commonPrefix + "/")) return null;
                File dst = new File(stagingWine, path.substring(commonPrefix.length()));
                // Common DLLs are hard-linked to the Wine build: unlink first so that extracting
                // over one (e.g. notepad.exe) never writes through into the Wine build's own file.
                if (dst.isFile()) dst.delete();
                return dst;
            };
            if (!containerManager.extractContainerPatternCommon(staging, commonRemap)) {
                Log.e(TAG, "Failed to extract container_pattern_common into the base for " + wineVersion);
                abortBuild(staging, building, wineDir, baseDir);
                return false;
            }

            if (!new File(stagingWine, "drive_c/windows").isDirectory()) {
                Log.e(TAG, "Base prefix for " + wineVersion + " has no drive_c/windows");
                abortBuild(staging, building, wineDir, baseDir);
                return false;
            }
            normalize(stagingWine);

            FileUtils.delete(oldDir);
            if (wineDir.exists() && !wineDir.renameTo(oldDir)) {
                Log.e(TAG, "Failed to move the previous base aside at " + wineDir);
                abortBuild(staging, building, wineDir, baseDir);
                return false;
            }
            if (!stagingWine.renameTo(wineDir)) {
                Log.e(TAG, "Failed to install the base prefix at " + wineDir);
                if (oldDir.exists()) oldDir.renameTo(wineDir);
                abortBuild(staging, building, wineDir, baseDir);
                return false;
            }
            if (!FileUtils.writeString(new File(baseDir, COMPLETE_MARKER), identity)) {
                Log.e(TAG, "Failed to write the completion marker for " + wineVersion);
                return false;
            }
            building.delete();
            FileUtils.delete(oldDir);
            FileUtils.delete(staging);
            Log.i(TAG, "Base prefix ready for " + wineVersion);
            return true;
        }
        catch (Throwable t) {
            Log.e(TAG, "Base prefix build failed for " + wineVersion, t);
            FileUtils.delete(staging);
            building.delete();
            return false;
        }
    }
}
