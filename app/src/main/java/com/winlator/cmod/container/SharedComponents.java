package com.winlator.cmod.container;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import com.winlator.cmod.contents.D7VKManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.OnExtractFileListener;
import com.winlator.cmod.core.TarCompressorUtils;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * One shared, extracted copy of every bundled component archive (DXVK, VKD3D, d8vk, nglide,
 * ddraw wrappers, wincomponents, WowBox64, FEXCore), hard-linked into the containers that use it.
 *
 * Layout: {@code files/shared_components/<asset name>@<apk stamp>/...}. An archive is extracted
 * once per APK build; containers get hard links to its files, so e.g. the 228 MB of the
 * direct3d wincomponent exist once for the whole app instead of once per container.
 *
 * Linked files keep their normal (writable) mode on purpose: a read-only mode would show up in
 * Wine as FILE_ATTRIBUTE_READONLY and make installers fail to replace or delete those DLLs.
 * Instead, nothing writes through a shared inode:
 *  - Wine: libcontaineroverlay gives the container a private copy before any write to an upper
 *    file that has more than one link (see containeroverlay_core.c, unshare_file).
 *  - App: FileUtils / TarCompressorUtils writers call {@link FileUtils#unshareForWrite} first.
 */
public final class SharedComponents {
    private static final String TAG = "SharedComponents";
    public static final String DIR_NAME = "shared_components";
    private static final String COMPLETE_MARKER = ".complete";
    private static final String STAGING_PREFIX = ".staging-";
    private static final char STAMP_SEPARATOR = '@';
    private static final Map<String, Object> LOCKS = new HashMap<>();

    private SharedComponents() {}

    public static File getStoreRoot(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /**
     * Makes the files of a bundled .tzst asset appear under destDir, like
     * {@code TarCompressorUtils.extract(ZSTD, context, assetFile, destDir, listener)}, but as hard
     * links into the shared store. The listener is applied to every destination path exactly as
     * the tar extraction would (null skips the entry, another File redirects it).
     * Falls back to a plain extraction if the store cannot be used.
     */
    public static boolean extractAndLink(Context context, String assetFile, File destDir, OnExtractFileListener listener) {
        // d7vk is redirected by TarCompressorUtils to the installed D7VK content: keep that path.
        if (D7VKManager.isD7VKAssetRequest(assetFile)) {
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetFile, destDir, listener);
        }
        File storeDir = ensure(context, assetFile);
        if (storeDir == null) {
            Log.w(TAG, "Shared store unavailable for " + assetFile + ", extracting a private copy");
            return TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetFile, destDir, listener);
        }
        boolean ok = linkTree(storeDir, destDir, listener);
        if (!ok) Log.e(TAG, "Linking " + assetFile + " into " + destDir + " was incomplete");
        return ok;
    }

    /** Returns the extracted store directory of an asset, extracting it first if needed. */
    static File ensure(Context context, String assetFile) {
        String name = storeName(assetFile);
        long stamp = apkStamp(context);
        String dirName = name + STAMP_SEPARATOR + Long.toHexString(stamp);
        String identity = assetFile + ":" + stamp;
        File root = getStoreRoot(context);
        File dir = new File(root, dirName);

        synchronized (lockFor(dirName)) {
            if (isComplete(dir, identity)) return dir;
            if (!root.isDirectory() && !root.mkdirs()) {
                Log.e(TAG, "Cannot create " + root);
                return null;
            }
            File staging = new File(root, STAGING_PREFIX + dirName);
            FileUtils.delete(staging);
            FileUtils.delete(dir);
            if (!staging.mkdirs()) {
                Log.e(TAG, "Cannot create " + staging);
                return null;
            }
            if (!TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, assetFile, staging)) {
                Log.e(TAG, "Failed to extract " + assetFile + " into the shared store");
                FileUtils.delete(staging);
                return null;
            }
            if (!FileUtils.writeString(new File(staging, COMPLETE_MARKER), identity) || !staging.renameTo(dir)) {
                Log.e(TAG, "Failed to publish " + dir);
                FileUtils.delete(staging);
                return null;
            }
            removeStale(root, name, dirName);
            Log.i(TAG, "Extracted " + assetFile + " into " + dir);
            return dir;
        }
    }

    private static boolean isComplete(File dir, String identity) {
        File marker = new File(dir, COMPLETE_MARKER);
        if (!marker.isFile()) return false;
        String content = FileUtils.readString(marker);
        return content != null && content.trim().equals(identity);
    }

    /**
     * Drops store copies of the same asset from older APK builds. Containers that still link
     * those files keep them alive through their own links; only the store's name goes away.
     */
    private static void removeStale(File root, String name, String keep) {
        File[] entries = root.listFiles();
        if (entries == null) return;
        String prefix = name + STAMP_SEPARATOR;
        for (File entry : entries) {
            String n = entry.getName();
            if (n.equals(keep)) continue;
            if (n.startsWith(prefix) || n.startsWith(STAGING_PREFIX + prefix)) FileUtils.delete(entry);
        }
    }

    private static boolean linkTree(File storeDir, File destDir, OnExtractFileListener listener) {
        if (!destDir.isDirectory() && !destDir.mkdirs()) return false;
        int rootLen = storeDir.getAbsolutePath().length() + 1;
        boolean ok = true;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(storeDir);
        while (!pending.isEmpty()) {
            File[] children = pending.removeFirst().listFiles();
            if (children == null) continue;
            for (File src : children) {
                String rel = src.getAbsolutePath().substring(rootLen);
                if (rel.equals(COMPLETE_MARKER)) continue;
                boolean isLink = FileUtils.isSymlink(src);
                boolean isDir = !isLink && src.isDirectory();

                File dst = new File(destDir, rel);
                if (listener != null) dst = listener.onExtractFile(dst, isDir || isLink ? 0 : src.length());

                if (dst != null) {
                    if (isDir) {
                        if (!dst.isDirectory() && !dst.mkdirs()) ok = false;
                    }
                    else if (isLink) {
                        FileUtils.symlink(FileUtils.readSymlink(src), dst.getAbsolutePath());
                    }
                    else if (!linkFile(src, dst)) {
                        ok = false;
                    }
                }
                // Like the tar extraction, children go through the listener on their own even
                // when the directory itself was skipped or redirected.
                if (isDir) pending.add(src);
            }
        }
        return ok;
    }

    /** Replaces dst with a hard link to src (copy if linking is refused). */
    private static boolean linkFile(File src, File dst) {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
        String s = src.getAbsolutePath();
        String d = dst.getAbsolutePath();
        try {
            StructStat ss = Os.stat(s);
            try {
                StructStat ds = Os.lstat(d);
                if (OsConstants.S_ISDIR(ds.st_mode)) {
                    Log.w(TAG, "Not replacing directory " + d);
                    return false;
                }
                if (ds.st_ino == ss.st_ino && ds.st_dev == ss.st_dev) return true;
                Os.remove(d);
            }
            catch (ErrnoException e) {
                if (e.errno != OsConstants.ENOENT) throw e;
            }
            Os.link(s, d);
            return true;
        }
        catch (ErrnoException e) {
            Log.w(TAG, "Hard link " + s + " -> " + d + " failed (" + e.getMessage() + "), copying");
            return FileUtils.copy(src, dst) && dst.isFile();
        }
    }

    /**
     * Copies a container directory tree, keeping hard links to shared component files as links
     * (a plain copy would turn every linked DLL back into a private copy). Symlinks are
     * recreated; other files are copied. Copied files and directories get mode 0771, like
     * container duplication always did. Returns false only if the destination cannot be created.
     */
    public static boolean copyTreePreservingLinks(File src, File dst) {
        if (FileUtils.isSymlink(src)) {
            FileUtils.symlink(FileUtils.readSymlink(src), dst.getAbsolutePath());
            return true;
        }
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs()) return false;
            FileUtils.chmod(dst, 0771);
            String[] names = src.list();
            if (names != null) {
                for (String n : names) {
                    // Like FileUtils.copy: a single failed entry is logged, not fatal.
                    if (!copyTreePreservingLinks(new File(src, n), new File(dst, n)))
                        Log.e(TAG, "Failed to copy " + new File(src, n));
                }
            }
            return true;
        }
        try {
            StructStat st = Os.lstat(src.getAbsolutePath());
            if (OsConstants.S_ISREG(st.st_mode) && st.st_nlink > 1) {
                Os.link(src.getAbsolutePath(), dst.getAbsolutePath());
                return true;
            }
        }
        catch (ErrnoException e) {
            Log.w(TAG, "Could not link " + src + ", copying: " + e.getMessage());
        }
        if (!FileUtils.copy(src, dst) || !dst.isFile()) return false;
        FileUtils.chmod(dst, 0771);
        return true;
    }

    private static String storeName(String assetFile) {
        String n = assetFile.replace('\\', '/');
        if (n.endsWith(".tzst")) n = n.substring(0, n.length() - 5);
        return n.replace('/', '_').replace(STAMP_SEPARATOR, '_');
    }

    private static long apkStamp(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        }
        catch (Exception e) {
            Log.w(TAG, "Cannot read package info: " + e);
            return 0;
        }
    }

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
}
