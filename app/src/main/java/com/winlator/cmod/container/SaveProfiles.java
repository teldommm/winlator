package com.winlator.cmod.container;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.GameSaveManager;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Save profiles: the user-profile folders games write their saves into live in shared storage
 * (Winlator/saves/&lt;Game&gt;/) instead of inside the container, and are linked into whichever
 * container the game is started on. Saves therefore survive switching or recreating containers
 * and can be reached from any Android file manager.
 *
 * Layout:
 *   Winlator/saves/.nomedia
 *   Winlator/saves/_Common/             launches without a shortcut, and shortcuts set to "Shared"
 *   Winlator/saves/&lt;Game&gt;/.profile.json  {"id": saveProfileId of the shortcut, "name": ...}
 *   Winlator/saves/&lt;Game&gt;/AppData, Documents, Saved Games, Public Documents
 *
 * A profile is found by the id in .profile.json, never by its folder name, so renaming a shortcut
 * (or the folder) keeps the link. The id is the shortcut's "saveProfileId" extra; cloning a
 * shortcut into another container copies the whole .desktop file, so the copy shares the profile.
 *
 * Links are made in the container's physical upper layer (drive_c/users/...). Wine reaches
 * shared storage directly (native launch, all-files access); libcontaineroverlay passes paths
 * that resolve outside the overlay straight through. Desktop stays in the container: Winlator
 * keeps its shortcuts there.
 */
public final class SaveProfiles {
    private static final String TAG = "SaveProfiles";

    public static final String PREF_ENABLED = "save_profiles_enabled";
    public static final boolean DEFAULT_ENABLED = true;

    public static final String EXTRA_ID = "saveProfileId";
    public static final String EXTRA_MODE = "saveProfileMode";
    public static final String MODE_OWN = "own";
    public static final String MODE_SHARED = "shared";

    public static final String COMMON_DIR = "_Common";
    public static final String META_FILE = ".profile.json";
    private static final String COMMON_ID = "common";
    private static final String NOMEDIA = ".nomedia";

    /** Link point under drive_c/users (container side) -> folder inside the profile. */
    private static final String[][] LINKS = {
            {ImageFs.USER + "/AppData", "AppData"},
            {ImageFs.USER + "/Documents", "Documents"},
            {ImageFs.USER + "/Saved Games", "Saved Games"},
            {"Public/Documents", "Public Documents"},
    };

    private static final String TEMP_DIR = "AppData/Local/Temp";

    /** Steam emulator identity (user name, Steam id): copied from _Common into a new profile. */
    private static final String[] IDENTITY_SEEDS = {
            "AppData/Roaming/GSE Saves/settings",
            "AppData/Roaming/Goldberg SteamEmu Saves/settings",
    };

    private static final int MIRROR_MAX_DEPTH = 8;

    private SaveProfiles() {}

    // ---------- Settings ----------

    public static boolean isEnabled(Context context) {
        if (context == null) return DEFAULT_ENABLED;
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, DEFAULT_ENABLED);
    }

    public static File getRoot() {
        return new File(Environment.getExternalStorageDirectory(), "Winlator/saves");
    }

    public static boolean isShared(Shortcut shortcut) {
        return shortcut == null || MODE_SHARED.equals(shortcut.getExtra(EXTRA_MODE, MODE_OWN));
    }

    public static void setShared(Shortcut shortcut, boolean shared) {
        shortcut.putExtra(EXTRA_MODE, shared ? MODE_SHARED : null);
        shortcut.saveData();
    }

    /** Gives the shortcut a profile id if it has none yet. Call before cloning it. */
    public static String ensureId(Shortcut shortcut) {
        String id = shortcut.getExtra(EXTRA_ID, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            shortcut.putExtra(EXTRA_ID, id);
            shortcut.saveData();
        }
        return id;
    }

    // ---------- Lookup ----------

    /** The profile folder of the shortcut (or _Common), or null if it was never created. */
    public static synchronized File findProfileDir(Shortcut shortcut) {
        if (isShared(shortcut)) {
            File common = new File(getRoot(), COMMON_DIR);
            return common.isDirectory() ? common : null;
        }
        String id = shortcut.getExtra(EXTRA_ID, "");
        return id.isEmpty() ? null : findById(id);
    }

    /** The profile folder of the shortcut (or _Common), created when missing. Null on failure. */
    public static synchronized File obtainProfileDir(Shortcut shortcut) {
        // Old backup folders in Winlator/Saves would otherwise take the game's folder name.
        GameSaveManager.migrateLegacyBackups();
        if (!ensureRoot()) return null;
        if (isShared(shortcut)) return obtainCommon();

        String id = ensureId(shortcut);
        File found = findById(id);
        if (found != null) return found;

        File dir = new File(getRoot(), uniqueName(shortcut.name, null));
        if (!dir.mkdirs() && !dir.isDirectory()) {
            Log.e(TAG, "Could not create profile " + dir);
            return null;
        }
        writeMeta(dir, id, shortcut.name);
        seedIdentity(dir);
        Log.i(TAG, "Created save profile " + dir.getName() + " for " + shortcut.name);
        return dir;
    }

    /** Shown path, e.g. "Winlator/saves/Hollow Knight/". */
    public static String displayPath(File profileDir) {
        return "Winlator/saves/" + profileDir.getName() + "/";
    }

    /** True if another shortcut (in any container) uses the same own profile. */
    public static boolean isUsedByOtherShortcut(Context context, Shortcut shortcut) {
        if (isShared(shortcut)) return true;
        String id = shortcut.getExtra(EXTRA_ID, "");
        if (id.isEmpty()) return false;
        try {
            for (Shortcut other : new ContainerManager(context).loadShortcuts()) {
                if (other == null || other.file.getPath().equals(shortcut.file.getPath())) continue;
                if (!isShared(other) && id.equals(other.getExtra(EXTRA_ID, ""))) return true;
            }
        }
        catch (Exception e) {
            Log.w(TAG, "Could not check other shortcuts", e);
            return true;
        }
        return false;
    }

    // ---------- Launch ----------

    /**
     * Links the profile of the game being started into the container (or removes the links when
     * the feature is off). Runs before Wine starts, after the container overlay is prepared.
     * Never deletes save data: a folder that cannot be moved is left in place and not linked.
     */
    public static void apply(Context context, Container container, Shortcut shortcut) {
        File users = new File(ContainerFiles.upperDir(container), "drive_c/users");
        if (!isEnabled(context)) {
            unlinkAll(users);
            return;
        }
        File profile = obtainProfileDir(shortcut);
        if (profile == null) {
            Log.e(TAG, "No save profile, container " + container.id + " keeps its own folders");
            return;
        }

        File lowerUsers = container.getBasePrefix().isEmpty() ? null : new File(container.getBasePrefix(), "drive_c/users");
        for (String[] link : LINKS) {
            File point = new File(users, link[0]);
            File target = new File(profile, link[1]);
            if (!target.isDirectory() && !target.mkdirs()) {
                Log.e(TAG, "Could not create " + target);
                continue;
            }
            if (lowerUsers != null) mirrorDirs(new File(lowerUsers, link[0]), target, 0);
            linkPoint(point, target, link[1]);
        }
        File temp = new File(profile, TEMP_DIR);
        if (temp.isDirectory()) FileUtils.clear(temp);
        Log.i(TAG, "Container " + container.id + " uses save profile " + profile.getName());
    }

    // ---------- Shortcut lifecycle ----------

    /** Renames the profile folder after the shortcut was renamed. Safe to call off the main thread. */
    public static synchronized void onShortcutRenamed(Shortcut shortcut, String newName) {
        if (isShared(shortcut)) return;
        String id = shortcut.getExtra(EXTRA_ID, "");
        if (id.isEmpty()) return;
        File dir = findById(id);
        if (dir == null) return;
        String wanted = uniqueName(newName, dir);
        if (!wanted.equals(dir.getName())) {
            File renamed = new File(getRoot(), wanted);
            if (dir.renameTo(renamed)) dir = renamed;
            else Log.w(TAG, "Could not rename profile " + dir + " to " + wanted);
        }
        writeMeta(dir, id, newName);
    }

    /** Deletes the shortcut's own profile folder. Call off the main thread. */
    public static synchronized boolean deleteProfile(Shortcut shortcut) {
        if (isShared(shortcut)) return false;
        File dir = findProfileDir(shortcut);
        return dir == null || FileUtils.delete(dir);
    }

    // ---------- Internals ----------

    private static boolean ensureRoot() {
        File root = getRoot();
        if (!root.isDirectory() && !root.mkdirs()) {
            Log.e(TAG, "Could not create " + root);
            return false;
        }
        File nomedia = new File(root, NOMEDIA);
        if (!nomedia.exists()) {
            try {
                nomedia.createNewFile();
            }
            catch (IOException ignored) {}
        }
        return true;
    }

    private static File obtainCommon() {
        File common = new File(getRoot(), COMMON_DIR);
        if (!common.isDirectory() && !common.mkdirs()) return null;
        if (!new File(common, META_FILE).isFile()) writeMeta(common, COMMON_ID, COMMON_DIR);
        return common;
    }

    private static File findById(String id) {
        File[] dirs = getRoot().listFiles(File::isDirectory);
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (COMMON_DIR.equals(dir.getName())) continue;
            if (id.equals(readMetaId(dir))) return dir;
        }
        return null;
    }

    private static String readMetaId(File dir) {
        File meta = new File(dir, META_FILE);
        if (!meta.isFile()) return null;
        try {
            return new JSONObject(FileUtils.readString(meta)).optString("id", null);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static void writeMeta(File dir, String id, String name) {
        try {
            JSONObject json = new JSONObject();
            File meta = new File(dir, META_FILE);
            if (meta.isFile()) {
                try {
                    json = new JSONObject(FileUtils.readString(meta));
                }
                catch (Exception ignored) {}
            }
            if (!json.has("createdAt")) json.put("createdAt", System.currentTimeMillis());
            json.put("id", id);
            json.put("name", name);
            json.put("updatedAt", System.currentTimeMillis());
            FileUtils.writeString(meta, json.toString(2));
        }
        catch (Exception e) {
            Log.w(TAG, "Could not write " + META_FILE + " in " + dir, e);
        }
    }

    /** A free folder name for a game; "self" is the folder being renamed (its own name stays free). */
    private static String uniqueName(String name, File self) {
        String base = sanitize(name);
        if (base.equalsIgnoreCase(COMMON_DIR)) base = base + " (game)";
        String candidate = base;
        int n = 2;
        // Shared storage is case-insensitive: compare names the same way.
        while (true) {
            File f = new File(getRoot(), candidate);
            boolean taken = f.exists() && (self == null || !candidate.equalsIgnoreCase(self.getName()));
            if (!taken) return candidate;
            candidate = base + " (" + n++ + ")";
        }
    }

    private static String sanitize(String name) {
        if (name == null) return "Game";
        String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        while (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1).trim();
        while (safe.startsWith(".")) safe = safe.substring(1).trim();
        return safe.isEmpty() ? "Game" : safe;
    }

    private static void seedIdentity(File profile) {
        File common = new File(getRoot(), COMMON_DIR);
        for (String rel : IDENTITY_SEEDS) {
            File src = new File(common, rel);
            if (src.isDirectory()) copyMerge(src, new File(profile, rel));
        }
    }

    /**
     * Makes the container's folder at "point" a link to "target". A real folder found there is
     * moved into _Common first (its owner is unknown: it may hold several games' files).
     */
    private static void linkPoint(File point, File target, String folder) {
        Path p = point.toPath();
        Path t = Paths.get(target.getAbsolutePath());
        try {
            if (Files.isSymbolicLink(p)) {
                if (Files.readSymbolicLink(p).equals(t)) return;
                Files.delete(p);
            }
            else if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    if (!isEmptyTree(point)) {
                        File common = obtainCommon();
                        if (common == null || !copyMerge(point, new File(common, folder))) {
                            Log.e(TAG, "Could not move " + point + " to " + COMMON_DIR + ", not linking it");
                            return;
                        }
                        Log.i(TAG, "Moved existing " + point + " into " + COMMON_DIR + "/" + folder);
                    }
                    if (!FileUtils.delete(point)) {
                        Log.e(TAG, "Could not remove " + point + " after moving it, not linking it");
                        return;
                    }
                }
                else {
                    Files.delete(p);
                }
            }
            File parent = point.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            Files.createSymbolicLink(p, t);
        }
        catch (IOException e) {
            Log.e(TAG, "Could not link " + point + " -> " + target, e);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) point.mkdirs();
        }
    }

    /** Feature off: the links go away and the container gets plain (empty) folders back. */
    private static void unlinkAll(File users) {
        for (String[] link : LINKS) {
            File point = new File(users, link[0]);
            Path p = point.toPath();
            if (!Files.isSymbolicLink(p)) continue;
            try {
                Files.delete(p);
            }
            catch (IOException e) {
                Log.w(TAG, "Could not remove link " + point, e);
                continue;
            }
            point.mkdirs();
        }
    }

    /** True if the tree holds no files (only empty folders, e.g. a fresh prefix skeleton). */
    private static boolean isEmptyTree(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return true;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath())) return false;
            if (!child.isDirectory() || !isEmptyTree(child)) return false;
        }
        return true;
    }

    /**
     * Copies src into dst, keeping whichever copy of a file is newer. Links inside src are not
     * followed. Returns false if any file could not be copied.
     */
    private static boolean copyMerge(File src, File dst) {
        if (!dst.isDirectory() && !dst.mkdirs()) return false;
        File[] children = src.listFiles();
        if (children == null) return src.isDirectory();
        boolean ok = true;
        for (File child : children) {
            Path cp = child.toPath();
            if (Files.isSymbolicLink(cp)) continue;
            File out = new File(dst, child.getName());
            if (child.isDirectory()) {
                ok &= copyMerge(child, out);
            }
            else if (child.isFile()) {
                if (out.isFile() && out.lastModified() >= child.lastModified()) continue;
                try {
                    Files.copy(cp, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    out.setLastModified(child.lastModified());
                }
                catch (IOException e) {
                    Log.w(TAG, "Could not copy " + child + " -> " + out, e);
                    ok = false;
                }
            }
        }
        return ok;
    }

    /** Recreates the folder skeleton of the base prefix (folders only) inside the profile. */
    private static void mirrorDirs(File src, File dst, int depth) {
        if (depth > MIRROR_MAX_DEPTH) return;
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (Files.isSymbolicLink(child.toPath()) || !child.isDirectory()) continue;
            File out = new File(dst, child.getName());
            if (!out.isDirectory() && !out.mkdirs()) continue;
            mirrorDirs(child, out, depth + 1);
        }
    }
}
