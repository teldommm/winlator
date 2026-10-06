package com.winlator.cmod.core;

import android.os.Environment;
import android.util.Log;


import com.winlator.cmod.container.SaveProfiles;
import com.winlator.cmod.container.SaveRegistry;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Per-game save backups: a history of save states, for rolling back a broken or unwanted save
 * (the live saves are in the save profile or the container; a backup is a snapshot of them).
 *
 * Kinds (from the file name): manual "yyyy-MM-dd_HH-mm-ss.zip", kept until deleted; automatic
 * "auto_....zip", made when the game exits if the per-shortcut switch is on and the saves' content
 * differs from the newest backup (path + size + CRC32 of every file, so rewriting a save with the
 * same data or a log changing doesn't count, a deleted file does), newest AUTO_KEEP kept; "before-restore_....zip", the saves as they were
 * before a restore, newest few kept.
 *
 * Backups live beside the public artwork directories:
 *   /storage/emulated/0/Winlator/backups/<Game Name>/
 *
 * Save locations are discovered under the Wine user's common save roots, then persisted in
 * save.json so discovery only has to succeed once. Automatic backups are intentionally scoped:
 * if no per-game root is known they do nothing instead of archiving the whole Wine profile.
 *
 * For a shortcut with a save profile (own/shared), the "Wine profile" read and restored here is
 * its folder in Winlator/saves (same relative layout: AppData, Documents, Saved Games, ...). For a
 * shortcut without one it is the container's users folder; when another game's profile is still
 * linked there, the container's own folders are put back first (SaveProfiles.prepareLocal).
 */
public final class GameSaveManager {
    private static final String TAG = "GameSaveManager";
    public static final String EXTRA_ENABLED = "gameSavesEnabled";
    public static final String EXTRA_AUTO_BACKUP = "autoSaveBackup";
    /** Automatic backups (on game exit) keep the newest AUTO_KEEP; manual ones are never pruned. */
    private static final String AUTO_PREFIX = "auto_";
    private static final String BEFORE_RESTORE_PREFIX = "before-restore_";
    private static final String LEGACY_AUTO_FILE = "auto-latest.zip";
    public static final int AUTO_KEEP = 5;
    private static final int BEFORE_RESTORE_KEEP = 3;
    /** Content fingerprint (path, size, CRC32) of the saves as of the newest backup or restore. */
    private static final String FINGERPRINT_FILE = ".fingerprint.json";

    public static final String KIND_MANUAL = "manual";
    public static final String KIND_AUTO = "auto";
    public static final String KIND_BEFORE_RESTORE = "before-restore";

    /** Never part of a backup: whole folders by path (relative to the profile, lowercase)... */
    private static final String[] NOISE_PATHS = {
            "appdata/local/temp",
            "appdata/local/microsoft",
            "appdata/roaming/microsoft",
            "appdata/locallow/microsoft",
            "appdata/local/d3dscache",
            "appdata/local/nvidia",
            "appdata/local/amd",
    };
    /** ...and caches/crash data wherever they are, by folder name (lowercase). */
    private static final Set<String> NOISE_NAMES = new HashSet<>(java.util.Arrays.asList(
            "crashdumps", "crashes", "crashreports", "logs",
            "shadercache", "gpucache", "code cache", "webcache", "dxcache"
    ));
    private static final String MAP_FILE = "save.json";
    private static final int KEEP_THRESHOLD = 50;

    private static final String[] SAVE_ROOTS = {
            "Documents/My Games",
            "Saved Games",
            "AppData/Roaming",
            "Documents",
            "AppData/Local",
            "AppData/LocalLow",
            "Public Documents"
    };

    private static final String[] IDENTITY_ROOTS = {
            "AppData/Roaming/FLT",
            "AppData/Roaming/GSE Saves/settings"
    };

    private GameSaveManager() {}

    public static final class BackupResult {
        public final boolean ok;
        public final String path;
        public final int fileCount;
        public final boolean wholeProfile;
        public final String error;

        BackupResult(boolean ok, String path, int fileCount, boolean wholeProfile, String error) {
            this.ok = ok;
            this.path = path;
            this.fileCount = fileCount;
            this.wholeProfile = wholeProfile;
            this.error = error;
        }
    }

    public static final class RestoreResult {
        public final boolean ok;
        public final int fileCount;
        public final String error;

        RestoreResult(boolean ok, int fileCount, String error) {
            this.ok = ok;
            this.fileCount = fileCount;
            this.error = error;
        }
    }

    private static final class Candidate {
        final String relPath;
        final int score;

        Candidate(String relPath, int score) {
            this.relPath = relPath;
            this.score = score;
        }
    }

    public static File getBackupsRoot() {
        return new File(Environment.getExternalStorageDirectory(), "Winlator/backups");
    }

    public static File getGameDir(Shortcut shortcut) {
        return new File(getBackupsRoot(), sanitize(shortcut.name));
    }

    public static boolean isEnabled(Shortcut shortcut) {
        return "1".equals(shortcut.getExtra(EXTRA_ENABLED, "0"));
    }

    public static void setEnabled(Shortcut shortcut, boolean enabled) {
        shortcut.putExtra(EXTRA_ENABLED, enabled ? "1" : "0");
        if (!enabled) shortcut.putExtra(EXTRA_AUTO_BACKUP, "0");
        shortcut.saveData();
    }

    public static boolean isAutoBackupEnabled(Shortcut shortcut) {
        return "1".equals(shortcut.getExtra(EXTRA_AUTO_BACKUP, "0"));
    }

    public static void setAutoBackupEnabled(Shortcut shortcut, boolean enabled) {
        shortcut.putExtra(EXTRA_AUTO_BACKUP, enabled ? "1" : "0");
        if (enabled) shortcut.putExtra(EXTRA_ENABLED, "1");
        shortcut.saveData();
    }

    public static boolean shouldAutoBackup(Shortcut shortcut) {
        return isAutoBackupEnabled(shortcut);
    }

    /** All backups of the game, newest first. */
    public static List<File> listBackups(Shortcut shortcut) {
        return listBackups(getGameDir(shortcut));
    }

    private static List<File> listBackups(File dir) {
        File[] files = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (files == null || files.length == 0) return new ArrayList<>();
        List<File> list = new ArrayList<>(java.util.Arrays.asList(files));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    public static File getLatestBackup(Shortcut shortcut) {
        List<File> list = listBackups(shortcut);
        return list.isEmpty() ? null : list.get(0);
    }

    /** manual, auto or before-restore, from the file name. */
    public static String kindOf(File backup) {
        String name = backup.getName();
        if (name.startsWith(AUTO_PREFIX) || name.equals(LEGACY_AUTO_FILE)) return KIND_AUTO;
        if (name.startsWith(BEFORE_RESTORE_PREFIX)) return KIND_BEFORE_RESTORE;
        return KIND_MANUAL;
    }

    /** Keeps the backups folder with the game when its shortcut is renamed. */
    public static void onShortcutRenamed(String oldName, String newName) {
        File from = new File(getBackupsRoot(), sanitize(oldName));
        File to = new File(getBackupsRoot(), sanitize(newName));
        if (!from.isDirectory() || from.getName().equalsIgnoreCase(to.getName())) return;
        if (to.exists()) {
            Log.w(TAG, "Backups of " + newName + " already exist, keeping " + from.getName());
            return;
        }
        if (!from.renameTo(to)) Log.w(TAG, "Could not rename backups " + from + " -> " + to);
    }

    public static List<String> getSaveRoots(Shortcut shortcut) {
        List<String> remembered = loadMap(shortcut);
        if (!remembered.isEmpty()) return remembered;
        return rediscoverSaveRoots(shortcut);
    }

    public static List<String> rediscoverSaveRoots(Shortcut shortcut) {
        List<String> roots = discover(shortcut);
        if (!roots.isEmpty()) saveMap(shortcut, roots);
        return roots;
    }

    public static BackupResult backup(Shortcut shortcut, boolean automatic) {
        return backup(shortcut, automatic ? KIND_AUTO : KIND_MANUAL, null);
    }

    /**
     * kind: manual (kept forever), auto (game exit; skipped when nothing changed since the newest
     * backup, newest AUTO_KEEP kept) or before-restore (safety copy; newest few kept).
     * protect: a backup that pruning must not delete (the one about to be restored).
     */
    private static BackupResult backup(Shortcut shortcut, String kind, File protect) {
        boolean automatic = KIND_AUTO.equals(kind);
        try {
            File profile = profileDir(shortcut, false);
            if (profile == null || !profile.isDirectory()) {
                return new BackupResult(false, null, 0, false,
                        profile == null ? "No save folder yet: start the game once" : "Wine profile not found");
            }

            // A game's own save profile holds only that game: all of it (minus caches) is its saves.
            // Shared and container folders hold other games too: only the detected save folders.
            List<String> effectiveRoots = scopeRoots(shortcut, profile);
            boolean wholeProfile = effectiveRoots == null && !SaveProfiles.isOwn(shortcut);
            if (automatic && wholeProfile) {
                return new BackupResult(false, null, 0, false, "No per-game save location detected");
            }

            List<File> files = collectFiles(profile, effectiveRoots);
            if (files.isEmpty()) {
                return new BackupResult(false, null, 0, wholeProfile, "No save files to back up");
            }

            File gameDir = getGameDir(shortcut);
            Map<String, long[]> previous = loadFingerprint(gameDir);
            Map<String, long[]> current = fingerprint(profile, files, previous);
            if (automatic && !previous.isEmpty() && getLatestBackup(shortcut) != null && sameContent(previous, current)) {
                return new BackupResult(false, null, 0, false, "No changes since the last backup");
            }
            if (!gameDir.exists() && !gameDir.mkdirs()) {
                return new BackupResult(false, null, 0, wholeProfile, "Could not create backup folder");
            }

            String prefix = automatic ? AUTO_PREFIX : KIND_BEFORE_RESTORE.equals(kind) ? BEFORE_RESTORE_PREFIX : "";
            File out = uniqueBackupFile(gameDir, prefix);
            File tmp = new File(gameDir, out.getName() + ".tmp");
            if (tmp.exists()) tmp.delete();

            int count = writeZip(profile, files, tmp);
            if (count == 0) {
                tmp.delete();
                return new BackupResult(false, null, 0, wholeProfile, "No save files to back up");
            }
            if (!tmp.renameTo(out)) {
                FileUtils.copy(tmp, out);
                tmp.delete();
            }
            if (!out.isFile()) {
                return new BackupResult(false, null, count, wholeProfile, "Could not finish backup");
            }

            saveFingerprint(gameDir, current);
            if (automatic) prune(gameDir, KIND_AUTO, AUTO_KEEP, protect);
            else if (KIND_BEFORE_RESTORE.equals(kind)) prune(gameDir, KIND_BEFORE_RESTORE, BEFORE_RESTORE_KEEP, protect);
            return new BackupResult(true, out.getAbsolutePath(), count, wholeProfile, null);
        } catch (Exception e) {
            Log.e(TAG, "Backup failed", e);
            return new BackupResult(false, null, 0, false,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static File uniqueBackupFile(File dir, String prefix) {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new java.util.Date());
        File out = new File(dir, prefix + stamp + ".zip");
        int n = 2;
        while (out.exists()) out = new File(dir, prefix + stamp + "-" + n++ + ".zip");
        return out;
    }

    /**
     * What a backup of this game covers: null for everything in the profile (a game's own save
     * profile, or nothing detected), else the detected save folders plus identity and registry.
     */
    private static List<String> scopeRoots(Shortcut shortcut, File profile) {
        if (SaveProfiles.isOwn(shortcut)) return null;
        List<String> roots = getSaveRoots(shortcut);
        if (roots.isEmpty()) return null;
        List<String> effective = new ArrayList<>(roots);
        for (String identityRoot : IDENTITY_ROOTS) {
            if (new File(profile, identityRoot).exists() && !effective.contains(identityRoot)) effective.add(identityRoot);
        }
        if (new File(profile, SaveRegistry.FILE).isFile()) effective.add(SaveRegistry.FILE);
        return effective;
    }

    /** rel path -> {size, mtime, crc32}; a file unchanged in size and time reuses its previous CRC. */
    private static Map<String, long[]> fingerprint(File profile, List<File> files, Map<String, long[]> previous) throws IOException {
        Map<String, long[]> result = new java.util.TreeMap<>();
        byte[] buffer = new byte[64 * 1024];
        for (File f : files) {
            String rel = relative(profile, f);
            long size = f.length();
            long mtime = f.lastModified();
            long[] old = previous.get(rel);
            long crc;
            if (old != null && old[0] == size && old[1] == mtime) {
                crc = old[2];
            } else {
                java.util.zip.CRC32 c = new java.util.zip.CRC32();
                try (FileInputStream in = new FileInputStream(f)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) c.update(buffer, 0, read);
                }
                crc = c.getValue();
            }
            result.put(rel, new long[]{size, mtime, crc});
        }
        return result;
    }

    /** Same files with the same size and CRC (times don't matter). */
    private static boolean sameContent(Map<String, long[]> a, Map<String, long[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (Map.Entry<String, long[]> entry : a.entrySet()) {
            long[] other = b.get(entry.getKey());
            if (other == null || other[0] != entry.getValue()[0] || other[2] != entry.getValue()[2]) return false;
        }
        return true;
    }

    private static Map<String, long[]> loadFingerprint(File gameDir) {
        Map<String, long[]> result = new java.util.TreeMap<>();
        File file = new File(gameDir, FINGERPRINT_FILE);
        if (!file.isFile()) return result;
        try {
            JSONObject files = new JSONObject(FileUtils.readString(file)).getJSONObject("files");
            java.util.Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String rel = keys.next();
                JSONArray v = files.getJSONArray(rel);
                result.put(rel, new long[]{v.getLong(0), v.getLong(1), v.getLong(2)});
            }
        }
        catch (Exception e) {
            result.clear();
        }
        return result;
    }

    private static void saveFingerprint(File gameDir, Map<String, long[]> fingerprint) {
        try {
            JSONObject files = new JSONObject();
            for (Map.Entry<String, long[]> entry : fingerprint.entrySet()) {
                long[] v = entry.getValue();
                files.put(entry.getKey(), new JSONArray().put(v[0]).put(v[1]).put(v[2]));
            }
            FileUtils.writeString(new File(gameDir, FINGERPRINT_FILE), new JSONObject().put("files", files).toString());
        }
        catch (Exception e) {
            Log.w(TAG, "Could not save backup fingerprint", e);
        }
    }

    /** After a restore the saves equal the restored backup: remember that, so it isn't backed up again. */
    private static void rememberState(Shortcut shortcut, File profile) {
        try {
            File gameDir = getGameDir(shortcut);
            List<File> files = collectFiles(profile, scopeRoots(shortcut, profile));
            saveFingerprint(gameDir, fingerprint(profile, files, loadFingerprint(gameDir)));
        }
        catch (Exception e) {
            Log.w(TAG, "Could not update backup fingerprint after restore", e);
        }
    }

    /** Deletes the oldest backups of one kind beyond keep (never "protect"). */
    private static void prune(File dir, String kind, int keep, File protect) {
        int kept = 0;
        for (File f : listBackups(dir)) {
            if (!kind.equals(kindOf(f))) continue;
            if (protect != null && f.equals(protect)) continue;
            if (++kept > keep && !f.delete()) Log.w(TAG, "Could not prune old backup " + f);
        }
    }

    /**
     * Restores one backup over the game's current saves (files in the backup replace the current
     * ones; others are left alone). The current saves are kept first as a before-restore backup.
     */
    public static RestoreResult restore(Shortcut shortcut, File archive) {
        if (archive == null || !archive.isFile()) return new RestoreResult(false, 0, "No backup found");
        BackupResult safety = backup(shortcut, KIND_BEFORE_RESTORE, archive);
        if (!safety.ok) Log.i(TAG, "No before-restore backup: " + safety.error);

        try {
            File profile = profileDir(shortcut, true);
            if (profile == null || (!profile.exists() && !profile.mkdirs())) {
                return new RestoreResult(false, 0, "Could not create Wine profile");
            }
            File canonicalProfile = profile.getCanonicalFile();
            String base = canonicalProfile.getPath() + File.separator;
            int written = 0;

            try (ZipInputStream zis = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String rel = remapArchiveEntry(entry.getName());
                    if (rel == null || rel.isEmpty() || rel.equals(SaveProfiles.META_FILE)) {
                        zis.closeEntry();
                        continue;
                    }

                    File out = new File(canonicalProfile, rel).getCanonicalFile();
                    if (!out.getPath().startsWith(base)) {
                        zis.closeEntry();
                        continue;
                    }

                    if (entry.isDirectory()) {
                        out.mkdirs();
                    } else {
                        if (isIdentityPath(rel) && out.isFile()) {
                            zis.closeEntry();
                            continue;
                        }
                        File parent = out.getParentFile();
                        if (parent != null) parent.mkdirs();
                        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(out))) {
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = zis.read(buffer)) != -1) bos.write(buffer, 0, read);
                        }
                        // Keep the saved time: games that pick the newest slot see the restored state.
                        if (entry.getTime() > 0) out.setLastModified(entry.getTime());
                        written++;
                    }
                    zis.closeEntry();
                }
            }
            rememberState(shortcut, profile);
            return new RestoreResult(true, written, null);
        } catch (Exception e) {
            Log.e(TAG, "Restore failed", e);
            return new RestoreResult(false, 0,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * Where the game's save roots are resolved: its save profile when it has one (null if not
     * created yet and create is false), else the container's Wine user folder.
     */
    private static File profileDir(Shortcut shortcut, boolean create) {
        if (SaveProfiles.isEnabled(shortcut)) {
            return create ? SaveProfiles.obtainProfileDir(shortcut) : SaveProfiles.findProfileDir(shortcut);
        }
        // Mode off: the saves are the container's own folders (set aside while a profile is linked).
        if (ProcessHelper.listRunningWineProcesses().isEmpty()) SaveProfiles.prepareLocal(shortcut.container);
        return new File(shortcut.container.getRootDir(), ".wine/drive_c/users/" + ImageFs.USER);
    }

    /** The files a backup of these roots (null: the whole profile) holds, caches excluded. */
    private static List<File> collectFiles(File profile, List<String> roots) throws IOException {
        List<File> starts = new ArrayList<>();
        if (roots == null) {
            starts.add(profile);
        } else {
            File profileCanon = profile.getCanonicalFile();
            String base = profileCanon.getPath() + File.separator;
            for (String rel : roots) {
                File f = new File(profileCanon, rel).getCanonicalFile();
                if ((f.getPath().equals(profileCanon.getPath()) || f.getPath().startsWith(base))
                        && f.exists()) starts.add(f);
            }
        }

        List<File> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<File> stack = new ArrayDeque<>(starts);
        while (!stack.isEmpty()) {
            File f = stack.removeLast();
            if (!f.exists() || Files.isSymbolicLink(f.toPath())) continue;
            String rel = relative(profile, f);
            if (f.isDirectory()) {
                if (isNoiseDir(rel)) continue;
                File[] children = f.listFiles();
                if (children != null) {
                    for (File child : children) stack.addLast(child);
                }
                continue;
            }
            if (isFrontendShortcut(rel) || rel.equals(SaveProfiles.META_FILE)) continue;
            if (rel.toLowerCase(Locale.ROOT).endsWith(".log")) continue;
            if (seen.add(rel)) files.add(f);
        }
        return files;
    }

    private static int writeZip(File profile, List<File> files, File out) throws IOException {
        int count = 0;
        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(out)))) {
            for (File f : files) {
                ZipEntry entry = new ZipEntry(relative(profile, f));
                entry.setTime(f.lastModified());
                zos.putNextEntry(entry);
                try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f))) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) zos.write(buffer, 0, read);
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }

    private static List<String> loadMap(Shortcut shortcut) {
        File mapFile = new File(getGameDir(shortcut), MAP_FILE);
        if (!mapFile.isFile()) return Collections.emptyList();
        try {
            JSONObject json = new JSONObject(FileUtils.readString(mapFile));
            JSONArray arr = json.optJSONArray("roots");
            if (arr == null) return Collections.emptyList();
            File profile = profileDir(shortcut, false);
            if (profile == null) return Collections.emptyList();
            List<String> valid = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String rel = arr.optString(i, "");
                if (!rel.isEmpty() && new File(profile, rel).exists()) valid.add(rel);
            }
            return valid;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static void saveMap(Shortcut shortcut, List<String> roots) {
        try {
            File dir = getGameDir(shortcut);
            dir.mkdirs();
            JSONObject json = new JSONObject();
            json.put("game", shortcut.name);
            json.put("roots", new JSONArray(roots));
            json.put("updatedAt", System.currentTimeMillis());
            FileUtils.writeString(new File(dir, MAP_FILE), json.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "Could not persist save map", e);
        }
    }

    private static List<String> discover(Shortcut shortcut) {
        File profile = profileDir(shortcut, false);
        if (profile == null || !profile.isDirectory()) return Collections.emptyList();

        List<String> identifiers = new ArrayList<>();
        addIdentifier(identifiers, shortcut.name);
        String exe = shortcut.path != null ? shortcut.path.replace('\\', '/') : "";
        int slash = exe.lastIndexOf('/');
        if (slash >= 0) exe = exe.substring(slash + 1);
        int dot = exe.lastIndexOf('.');
        if (dot > 0) exe = exe.substring(0, dot);
        addIdentifier(identifiers, exe);
        addIdentifier(identifiers, shortcut.wmClass);
        if (identifiers.isEmpty()) return Collections.emptyList();

        Map<String, Candidate> hits = new LinkedHashMap<>();
        for (String root : SAVE_ROOTS) {
            File rootDir = new File(profile, root);
            File[] first = rootDir.listFiles();
            if (first == null) continue;
            for (File d1 : first) {
                if (!d1.isDirectory()) continue;
                scoreCandidate(profile, d1, identifiers, hits);
                File[] second = d1.listFiles();
                if (second != null) {
                    for (File d2 : second) {
                        if (d2.isDirectory()) scoreCandidate(profile, d2, identifiers, hits);
                    }
                }
            }
        }

        List<Candidate> ranked = new ArrayList<>(hits.values());
        ranked.sort((a, b) -> Integer.compare(b.score, a.score));
        List<String> kept = new ArrayList<>();
        for (Candidate candidate : ranked) {
            boolean nested = false;
            for (String parent : kept) {
                if (candidate.relPath.equals(parent) || candidate.relPath.startsWith(parent + "/")) {
                    nested = true;
                    break;
                }
            }
            if (!nested) kept.add(candidate.relPath);
        }
        return kept;
    }

    private static void scoreCandidate(File profile, File dir, List<String> ids,
                                       Map<String, Candidate> out) {
        String candidateName = normalize(dir.getName());
        if (candidateName.isEmpty()) return;
        int best = 0;
        for (String id : ids) best = Math.max(best, score(id, candidateName));
        if (best < KEEP_THRESHOLD) return;

        String rel = relative(profile, dir);
        Candidate old = out.get(rel);
        if (old == null || best > old.score) out.put(rel, new Candidate(rel, best));
    }

    private static void addIdentifier(List<String> out, String raw) {
        String n = normalize(raw);
        if (!n.isEmpty() && !out.contains(n)) out.add(n);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String lower = value.toLowerCase(Locale.ROOT)
                .replace("®", "").replace("™", "").replace("©", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    private static int score(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        if (a.contains(b) || b.contains(a)) return 70;

        int max = Math.max(a.length(), b.length());
        if (max > 0) {
            double ratio = 1.0 - (double) levenshtein(a, b) / max;
            if (ratio >= 0.85) return 50;
        }
        return 0;
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }

    private static String relative(File profile, File f) {
        String base = profile.getAbsolutePath();
        String path = f.getAbsolutePath();
        if (path.equals(base)) return "";
        String rel = path.substring(Math.min(path.length(), base.length()));
        while (rel.startsWith(File.separator)) rel = rel.substring(1);
        return rel.replace(File.separatorChar, '/');
    }

    private static boolean isNoiseDir(String rel) {
        String p = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String noise : NOISE_PATHS) {
            if (p.equals(noise) || p.startsWith(noise + "/")) return true;
        }
        int slash = p.lastIndexOf('/');
        return NOISE_NAMES.contains(slash >= 0 ? p.substring(slash + 1) : p);
    }

    private static boolean isFrontendShortcut(String rel) {
        String p = rel.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!p.startsWith("desktop/")) return false;
        return p.endsWith(".lnk") || p.endsWith(".desktop") || p.endsWith(".url");
    }

    private static boolean isIdentityPath(String rel) {
        String p = rel.replace('\\', '/');
        for (String root : IDENTITY_ROOTS) {
            if (p.equalsIgnoreCase(root) || p.toLowerCase(Locale.ROOT)
                    .startsWith(root.toLowerCase(Locale.ROOT) + "/")) return true;
        }
        return false;
    }

    private static String remapArchiveEntry(String raw) {
        if (raw == null) return null;
        String p = raw.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return null;

        String lower = p.toLowerCase(Locale.ROOT);
        if (lower.startsWith("drive_c/users/")) {
            String[] parts = p.split("/", 4);
            if (parts.length < 4) return null;
            p = parts[3];
        }
        if (p.equals("..") || p.startsWith("../") || p.contains("/../")) return null;
        return p;
    }

    private static String sanitize(String name) {
        if (name == null) return "Game";
        String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "Game" : safe;
    }
}
