package com.winlator.cmod.container;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copy-on-write prefix overlay: one shared, read-only base prefix per Wine/Proton version and a
 * thin per-container "upper" prefix that only holds what the container changed.
 *
 * The overlay itself is implemented by libcontaineroverlay.so (LD_PRELOAD, see cpp/containeroverlay). This class
 * owns the environment contract with that library and the on-disk layout of a thin container.
 */
public final class ContainerOverlay {
    private static final String TAG = "ContainerOverlay";

    public static final String OVERLAY_DIR = ".containeroverlay";
    public static final String WHITEOUT_DIR = "wh";
    public static final String OPAQUE_DIR = "opaque";
    public static final String LIB_NAME = "libcontaineroverlay.so";

    public static final String ENV_UPPER = "CONTAINER_OVERLAY_UPPER";
    public static final String ENV_LOWER = "CONTAINER_OVERLAY_LOWER";
    public static final String ENV_ALIASES = "CONTAINER_OVERLAY_ALIASES";
    public static final String ENV_DEBUG = "CONTAINER_OVERLAY_DEBUG";

    private static final String[] REGISTRY_FILES = {"system.reg", "user.reg", "userdef.reg"};

    /** Directories the app writes into from Java (tar extraction needs the parent to exist). */
    private static final String[] UPPER_SKELETON_DIRS = {
            "drive_c/windows/system32",
            "drive_c/windows/syswow64",
            "drive_c/windows/temp",
    };

    private static final String DATA_DATA = "/data/data/";
    private static final String DATA_USER_0 = "/data/user/0/";

    private ContainerOverlay() {}

    public static File nativeLibFile(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, LIB_NAME);
    }

    public static boolean isLibraryAvailable(Context context) {
        return nativeLibFile(context).isFile();
    }

    /**
     * Same approach as libfakeinput.so: the library is preloaded from the imagefs lib dir. The copy
     * is refreshed when the APK ships a different build. Returns null when the library is missing.
     */
    public static File ensurePreloadLib(Context context, ImageFs imageFs) {
        File src = nativeLibFile(context);
        if (!src.isFile()) return null;
        File dst = new File(imageFs.getLibDir(), LIB_NAME);
        if (!dst.isFile() || dst.length() != src.length() || dst.lastModified() < src.lastModified()) {
            if (!FileUtils.copy(src, dst) || !dst.isFile()) {
                Log.e(TAG, "Failed to copy " + LIB_NAME + " to " + dst);
                return null;
            }
            FileUtils.chmod(dst, 0755);
        }
        return dst;
    }

    public static String normalizeDataPath(String path) {
        if (path == null) return null;
        if (path.startsWith(DATA_DATA)) return DATA_USER_0 + path.substring(DATA_DATA.length());
        return path;
    }

    public static String dataDataSpelling(String path) {
        if (path == null) return null;
        if (path.startsWith(DATA_USER_0)) return DATA_DATA + path.substring(DATA_USER_0.length());
        return path;
    }

    public static String canonicalHostPath(File file) {
        String path;
        try {
            path = file.getCanonicalPath();
        }
        catch (IOException e) {
            path = file.getAbsolutePath();
        }
        return normalizeDataPath(path);
    }

    /**
     * Other spellings of the upper prefix. Wine is started with WINEPREFIX=imagefs/home/xuser/.wine,
     * and home/xuser is a symlink to the active container, so that spelling has to count as upper too.
     */
    public static List<String> aliases(String upper, String imageFsRoot) {
        Set<String> result = new LinkedHashSet<>();
        if (imageFsRoot != null && !imageFsRoot.isEmpty()) {
            String alias = normalizeDataPath(stripTrailingSlash(imageFsRoot) + ImageFs.WINEPREFIX);
            result.add(alias);
            result.add(dataDataSpelling(alias));
        }
        if (upper != null) {
            result.add(dataDataSpelling(upper));
            result.remove(upper);
        }
        return new ArrayList<>(result);
    }

    public static Map<String, String> buildEnv(String upper, String lower, List<String> aliases, boolean debug) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_UPPER, upper);
        env.put(ENV_LOWER, lower);
        env.put(ENV_ALIASES, String.join(":", aliases));
        if (debug) env.put(ENV_DEBUG, "1");
        return env;
    }

    public static String appendPreload(String current, String lib, String separator) {
        if (current == null || current.trim().isEmpty()) return lib;
        for (String entry : current.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.trim().equals(lib)) return current;
        }
        return current + separator + lib;
    }

    /**
     * Prepares a container for launch, before anything touches its prefix: builds the shared base
     * prefix of its Wine version if needed, repoints the container at it and makes sure the
     * directories the app extracts into exist in the container's layer.
     *
     * @return false if the container cannot be launched (library or base prefix unavailable): a
     *         prefix started without its overlay would be missing most of Windows.
     */
    public static boolean prepare(Context context, ContentsManager contentsManager, Container container) {
        if (container.getBasePrefix().isEmpty()) {
            // Created before the overlay: it owns a complete private prefix. Running it on top of a
            // base would only add the base's size on top of its own, so it has to be recreated.
            Log.e(TAG, "Container " + container.id + " has a full private prefix, recreate it");
            return false;
        }
        if (!isLibraryAvailable(context)) {
            Log.e(TAG, "Overlay library missing, cannot launch container " + container.id);
            return false;
        }
        File baseWine = BasePrefix.ensure(context, contentsManager, container.getWineVersion());
        if (baseWine == null) {
            Log.e(TAG, "No base prefix for " + container.getWineVersion() + ", container " + container.id);
            return false;
        }
        File upperWine = new File(container.getRootDir(), ".wine");
        ensureSkeleton(upperWine);

        String basePath = canonicalHostPath(baseWine);
        if (!basePath.equals(container.getBasePrefix())) {
            container.setBasePrefix(basePath);
            container.saveData();
        }
        return true;
    }

    /**
     * Adds the overlay to the Wine launch environment and returns the new LD_PRELOAD value.
     *
     * @throws IllegalStateException if the library or the base prefix is missing
     */
    public static String applyLaunchEnv(Context context, ImageFs imageFs, Container container, EnvVars env, String preload) {
        File lib = ensurePreloadLib(context, imageFs);
        if (lib == null) {
            throw new IllegalStateException("Overlay library missing, cannot launch container " + container.id);
        }
        File lower = new File(container.getBasePrefix());
        if (container.getBasePrefix().isEmpty() || !lower.isDirectory()) {
            throw new IllegalStateException("Base prefix missing for container " + container.id + " (" + lower + "), recreate the container");
        }
        String upperPath = canonicalHostPath(new File(container.getRootDir(), ".wine"));
        String lowerPath = canonicalHostPath(lower);
        List<String> aliasList = aliases(upperPath, imageFs.getRootDir().getAbsolutePath());
        boolean debug = new EnvVars(container.getEnvVars()).has(ENV_DEBUG);

        for (Map.Entry<String, String> entry : buildEnv(upperPath, lowerPath, aliasList, debug).entrySet()) {
            env.put(entry.getKey(), entry.getValue());
        }
        Log.i(TAG, "Overlay enabled for container " + container.id + ": upper=" + upperPath + " lower=" + lowerPath);
        return appendPreload(preload, lib.getAbsolutePath(), ":");
    }

    /** Creates the layer of a new thin container: registry, dosdevices, users and the marker dir. */
    public static boolean createThinPrefix(File baseWine, File upperWine) {
        try {
            Files.createDirectories(upperWine.toPath());
            if (!copyTree(new File(baseWine, ContainerFiles.DOSDEVICES), new File(upperWine, ContainerFiles.DOSDEVICES))) return false;
            for (String name : REGISTRY_FILES) {
                File src = new File(baseWine, name);
                File dst = new File(upperWine, name);
                if (src.isFile()) copyFile(src.toPath(), dst.toPath());
            }
            if (!copyTree(new File(baseWine, "drive_c/users"), new File(upperWine, "drive_c/users"))) return false;
            Files.createDirectories(new File(upperWine, OVERLAY_DIR).toPath());
            Files.createDirectories(new File(upperWine, ContainerFiles.DOSDEVICES).toPath());
            ensureSkeleton(upperWine);
            return ContainerFiles.markOpaque(upperWine, ContainerFiles.DOSDEVICES);
        }
        catch (IOException e) {
            Log.w(TAG, "createThinPrefix failed for " + upperWine + ": " + e);
            return false;
        }
    }

    /** Makes sure the directories the app extracts into exist in the layer. Cheap, safe to call on every launch. */
    public static void ensureSkeleton(File upperWine) {
        for (String rel : UPPER_SKELETON_DIRS) {
            File dir = new File(upperWine, rel);
            if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "Could not create " + dir);
        }
        new File(upperWine, OVERLAY_DIR).mkdirs();
    }

    private static boolean copyTree(File src, File dst) {
        if (!ContainerFiles.exists(src)) return true;
        final Path srcRoot = src.toPath();
        final Path dstRoot = dst.toPath();
        try {
            Files.walkFileTree(srcRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Path target = dstRoot.resolve(srcRoot.relativize(dir).toString());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        return Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                    }
                    Files.createDirectories(target);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Path target = dstRoot.resolve(srcRoot.relativize(file).toString());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return FileVisitResult.CONTINUE;
                    if (attrs.isSymbolicLink()) {
                        Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                    }
                    else if (attrs.isRegularFile()) {
                        copyFile(file, target);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        }
        catch (IOException e) {
            Log.w(TAG, "copyTree failed " + src + " -> " + dst + ": " + e);
            return false;
        }
    }

    private static void copyFile(Path src, Path dst) throws IOException {
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        dst.toFile().setWritable(true, true);
    }

    private static String stripTrailingSlash(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
