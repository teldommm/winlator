package com.winlator.cmod.ui.library;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.MainActivity;
import com.winlator.cmod.R;
import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.steamgrid.ArtworkRepository;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.winlator.cmod.core.AppDefaults;

// Library screen logic — formerly ShortcutsFragment. Now a plain object owned by LibraryRoute
// (LibraryRoute.kt), which MainShell hosts as its first tab. Behaviour is unchanged; the
// Fragment-specific parts were replaced:
//  - activity/activity/activity are the activity passed in;
//  - the icon picker launcher is provided by the route (rememberLauncherForActivityResult)
//    and its result comes back through onIconPicked();
//  - detail screens are opened through MainActivity (MainShell's detail stack);
//  - onResume/onViewCreated reloads are the route's LifecycleResumeEffect, plus MainShell's
//    re-show / refresh signal.
// The never-called content-archive picker (pickContentArchive) was dropped.
public class LibraryScreenController {
    private static final String TAG = "LibraryScreen";
    private static final int PICK_ICON = 0;
    private static final int PICK_COVER = 1;
    private static final int PICK_BACKGROUND = 2;
    private static boolean apiKeyWarningShown;

    private final AppCompatActivity activity;
    private ContainerManager manager;
    private SharedPreferences preferences;
    private LibraryComposeController libraryController;
    
    private boolean isGridView = false;
    private final ArrayList<Shortcut> allShortcuts = new ArrayList<>();

    // All disk work for the list (reading every container's desktop dir, parsing .desktop files,
    // decoding icons, probing cover/banner/icon files) runs here, one job at a time; results are
    // applied on the main thread. loadGeneration drops results superseded by a newer load.
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private int loadGeneration;
    private volatile Bitmap defaultIcon;
    // Runtime label per shortcut path, resolved on the loader with every full load; reused by the
    // lighter re-publishes (artwork downloaded, favorite toggled) so they don't redo the sync.
    // Loader-thread only.
    private final HashMap<String, String> environmentLabels = new HashMap<>();
    private final Set<String> artworkRequests = Collections.synchronizedSet(new HashSet<>());

    // Shortcuts whose exe icon / placeholder cover was already tried this session (no retry on
    // every scroll).
    private final Set<String> offlineArtTried = Collections.synchronizedSet(new HashSet<>());

    private Shortcut shortcutForIconUpdate;
    private int pendingPickTarget = PICK_ICON;
    private Runnable iconPickerLauncher;
    private final LibraryCallbacks callbacks;

    public static final int IMPORT_SHORTCUT = 1005;

    public LibraryScreenController(AppCompatActivity activity) {
        this.activity = activity;
        manager = new ContainerManager(activity);
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!preferences.getBoolean("enhanced_library_migrated", false)) {
            isGridView = false;
            preferences.edit()
                    .putBoolean("shortcuts_grid_view", false)
                    .putBoolean("enhanced_library_migrated", true)
                    .apply();
        } else {
            isGridView = preferences.getBoolean("shortcuts_grid_view", false);
        }

        libraryController = LibraryComposeHost.createController(activity, isGridView);
        callbacks = new LibraryCallbacks() {
                    @Override
                    public void onOpen(@NonNull String shortcutPath) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) openGameDetails(shortcut);
                    }

                    @Override
                    public void onRun(@NonNull String shortcutPath) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) runFromShortcut(shortcut);
                    }

                    @Override
                    public void onGridViewChanged(boolean gridView) {
                        setGridView(gridView);
                    }

                    @Override
                    public void onAction(@NonNull String shortcutPath, @NonNull String action) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) handleShortcutAction(shortcut, action);
                    }

                    @Override
                    public void onArtworkNeeded(@NonNull String shortcutPath, @NonNull String kind) {
                        Shortcut shortcut = findShortcut(shortcutPath);
                        if (shortcut != null) requestArtwork(shortcut, kind);
                    }

                    @Override
                    public void onSearchQueryChanged(@NonNull String query) {
                        if (libraryController != null) libraryController.setSearchQuery(query);
                    }

                    @Override
                    public void onOpenFileManager() {
                        // File Manager is a MainShell tab now (in both orientations).
                        if (activity instanceof MainActivity) {
                            ((MainActivity) activity).navigateToMainDestination(R.id.main_menu_file_manager);
                        }
                    }
                };
    }

    public LibraryComposeController getLibraryController() {
        return libraryController;
    }

    public LibraryCallbacks getCallbacks() {
        return callbacks;
    }

    // Set by LibraryRoute: launches the image picker (GetContent "image/*").
    public void setIconPickerLauncher(Runnable launcher) {
        iconPickerLauncher = launcher;
    }

    // The same image picker serves "Choose icon", "Choose cover" and "Choose background" (see
    // showArtworkMenu).
    public void onIconPicked(Uri uri) {
        if (uri == null || shortcutForIconUpdate == null) return;
        if (pendingPickTarget == PICK_COVER) updateShortcutCover(uri, shortcutForIconUpdate);
        else if (pendingPickTarget == PICK_BACKGROUND) updateShortcutBackground(uri, shortcutForIconUpdate);
        else updateShortcutIcon(uri, shortcutForIconUpdate);
    }

    private void setGridView(boolean gridView) {
        isGridView = gridView;
        preferences.edit().putBoolean("shortcuts_grid_view", isGridView).apply();
        if (libraryController != null) libraryController.setGridView(isGridView);
    }

    private File getImagesDir(boolean isCover) {
        return ArtworkRepository.dir(isCover ? ArtworkRepository.KIND_COVER : ArtworkRepository.KIND_ICON);
    }

    private File getBannerDir() {
        return ArtworkRepository.dir(ArtworkRepository.KIND_BANNER);
    }

    // Reloads containers and shortcuts off the main thread. A fresh ContainerManager is built
    // for every load: the Library tab now lives for the whole session, so reusing the one from
    // construction would miss containers created or removed since (the old fragment got a new
    // manager each time it was recreated).
    public void loadShortcutsList() {
        if (loader.isShutdown()) return;
        final int generation = ++loadGeneration;
        loader.execute(() -> {
            ContainerManager freshManager = new ContainerManager(activity);
            ArrayList<Shortcut> shortcuts = freshManager.loadShortcuts();
            ArrayList<Shortcut> loaded = new ArrayList<>();
            if (shortcuts != null) {
                shortcuts.removeIf(shortcut -> shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty());
                Bitmap fallback = defaultIcon();
                for (Shortcut shortcut : shortcuts) {
                    if (shortcut.icon == null) shortcut.icon = fallback;
                }
                loaded.addAll(shortcuts);
            }
            environmentLabels.clear();
            environmentLabels.putAll(LibraryEnvironmentLabels.resolve(activity, loaded));
            ArrayList<LibraryItem> items = buildLibraryItems(loaded);
            postToUi(() -> {
                if (generation != loadGeneration) return;
                manager = freshManager;
                allShortcuts.clear();
                allShortcuts.addAll(loaded);
                if (libraryController != null) libraryController.setItems(items);
            });
        });
    }

    // Stops background work; called when the Library route leaves composition.
    public void dispose() {
        loader.shutdownNow();
    }

    private Bitmap defaultIcon() {
        Bitmap icon = defaultIcon;
        if (icon == null) {
            icon = BitmapFactory.decodeResource(activity.getResources(), R.drawable.icon_wine);
            defaultIcon = icon;
        }
        return icon;
    }

    private void postToUi(Runnable action) {
        activity.runOnUiThread(() -> {
            if (!activity.isDestroyed()) action.run();
        });
    }

    private Shortcut findShortcut(String shortcutPath) {
        for (Shortcut shortcut : allShortcuts) {
            if (shortcut.file != null && shortcut.file.getPath().equals(shortcutPath)) return shortcut;
        }
        return null;
    }

    // Re-publishes the current list (after artwork downloads, favorite/last-run changes). The
    // file probing moves to the loader; a full reload started in the meantime wins.
    private void publishLibraryItems() {
        if (libraryController == null || loader.isShutdown()) return;
        final ArrayList<Shortcut> snapshot = new ArrayList<>(allShortcuts);
        final int generation = loadGeneration;
        loader.execute(() -> {
            ArrayList<LibraryItem> items = buildLibraryItems(snapshot);
            postToUi(() -> {
                if (generation == loadGeneration && libraryController != null) libraryController.setItems(items);
            });
        });
    }

    private ArrayList<LibraryItem> buildLibraryItems(ArrayList<Shortcut> shortcuts) {
        ArrayList<LibraryItem> items = new ArrayList<>();
        for (Shortcut shortcut : shortcuts) {
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File userIcon = new File(getImagesDir(false), baseName + ".user.png");
            File autoIcon = new File(getImagesDir(false), baseName + ".png");
            // The user's own cover / background win over downloaded ones (NAME.user.png).
            File userCover = ArtworkRepository.userCoverFile(baseName);
            boolean hasUserCover = ArtworkRepository.isUsable(userCover);
            File cover = hasUserCover ? userCover : new File(getImagesDir(true), baseName + ".png");
            File generated = ArtworkRepository.generatedCoverFile(baseName);
            // A real cover replaced the placeholder: drop it.
            if (cover.exists() && generated.exists()) generated.delete();
            File userBanner = ArtworkRepository.userBannerFile(baseName);
            File banner = ArtworkRepository.isUsable(userBanner) ? userBanner : new File(getBannerDir(), baseName + ".png");
            String iconPath = userIcon.exists() ? userIcon.getPath() :
                    (autoIcon.exists() ? autoIcon.getPath() : null);
            // Decode the (small) game icon here, off the main thread, so the tile's first frame
            // already shows it instead of the exe icon (see LibraryImageCache).
            Bitmap icon = LibraryImageCache.load(iconPath);
            String containerLabel = environmentLabels.get(shortcut.file.getPath());
            if (containerLabel == null) containerLabel = shortcut.container != null ? shortcut.container.getName() : "";

            items.add(new LibraryItem(
                    shortcut.file.getPath(),
                    shortcut.file.getPath(),
                    shortcut.name,
                    containerLabel,
                    cover.exists() ? cover.getPath() : null,
                    hasUserCover,
                    !cover.exists() && generated.isFile() && generated.length() > 0 ? generated.getPath() : null,
                    banner.exists() ? banner.getPath() : null,
                    iconPath,
                    icon != null ? icon : shortcut.icon,
                    "1".equals(shortcut.getExtra("favorite", "0")),
                    parseLastRunAt(shortcut)
            ));
        }
        return items;
    }

    private long parseLastRunAt(Shortcut shortcut) {
        try {
            return Long.parseLong(shortcut.getExtra("lastRunAt", "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    // ---- Artwork ---------------------------------------------------------------------------

    private ArtworkRepository.Job newArtworkJob(Shortcut shortcut, String source, boolean force,
                                                boolean replaceStale, File cover, File banner) {
        ArtworkRepository.Job job = new ArtworkRepository.Job();
        job.context = activity;
        job.name = shortcut.name;
        job.windowsPath = shortcut.path;
        job.baseName = FileUtils.getBasename(shortcut.file.getPath());
        job.source = source;
        job.coverFile = cover;
        job.bannerFile = banner;
        job.force = force;
        job.replaceStale = replaceStale;
        return job;
    }

    private void toast(String message) {
        if (activity != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    // Called by every tile that has no cover/banner yet. One job fetches whatever is missing, and
    // only when it makes sense: automatic download is on, the game was not set to "no artwork", and
    // it did not just fail/miss (ArtworkRepository remembers that, so scrolling does not retry).
    private void requestArtwork(Shortcut shortcut, String kind) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File cover = ArtworkRepository.coverFile(baseName);
        File banner = ArtworkRepository.bannerFile(baseName);
        // cover / banner above are the downloader's own slots (its write targets); whether an
        // image is missing also counts the user's own one.
        final boolean coverMissing = ArtworkRepository.effectiveCover(baseName) == null;
        boolean bannerMissing = ArtworkRepository.effectiveBanner(baseName) == null;
        if (!coverMissing && !bannerMissing) return;

        String source = shortcut.getExtra(ArtworkRepository.EXTRA_SOURCE, "");
        boolean allowed = ArtworkRepository.isAutoDownloadEnabled(activity)
                && !ArtworkRepository.SOURCE_NONE.equals(source);
        File coverTarget = coverMissing
                && !ArtworkRepository.shouldSkip(activity, baseName, ArtworkRepository.KIND_COVER) ? cover : null;
        File bannerTarget = bannerMissing
                && !ArtworkRepository.shouldSkip(activity, baseName, ArtworkRepository.KIND_BANNER) ? banner : null;

        if (!allowed || (coverTarget == null && bannerTarget == null)) {
            if (coverMissing) ensureOfflineArt(shortcut);
            return;
        }

        final String requestKey = "artwork:" + shortcut.file.getPath();
        if (!artworkRequests.add(requestKey)) return;
        ArtworkRepository.Job job = newArtworkJob(shortcut, source.isEmpty() ? null : source,
                false, false, coverTarget, bannerTarget);
        ArtworkRepository.enqueue(job, result -> postToUi(() -> {
            artworkRequests.remove(requestKey);
            if (result.anySaved()) publishLibraryItems();
            if (coverMissing && !result.coverSaved) ensureOfflineArt(shortcut);
            if (result.authProblem) warnApiKeyOnce();
        }));
    }

    private void warnApiKeyOnce() {
        if (apiKeyWarningShown) return;
        apiKeyWarningShown = true;
        Toast.makeText(activity, "SteamGridDB rejected the API key - check it in Settings", Toast.LENGTH_LONG).show();
    }

    // What a tile shows when there is no downloaded cover, and needs no internet: the icon inside
    // the exe (extracted once) and a placeholder cover built from that icon. Tried once per
    // session and game; the placeholder never counts as "the cover", so a real one still downloads.
    private void ensureOfflineArt(Shortcut shortcut) {
        if (!offlineArtTried.add(shortcut.file.getPath())) return;
        final String baseName = FileUtils.getBasename(shortcut.file.getPath());
        final File autoIcon = ArtworkRepository.autoIconFile(baseName);
        final File userIcon = ArtworkRepository.userIconFile(baseName);
        final File containerIcon = shortcut.iconFile;
        ArtworkRepository.runLocal(() -> {
            boolean changed = false;
            if (!userIcon.exists() && !autoIcon.exists()) {
                File exeFile = resolveExeFile(shortcut);
                if (exeFile != null) changed = ExeIconExtractor.extractIcon(exeFile, autoIcon);
            }
            boolean generated = ArtworkRepository.generateCover(baseName, userIcon, autoIcon, containerIcon);
            if (changed || generated) postToUi(this::publishLibraryItems);
        });
    }

    // The placeholder is drawn from the icon, so a new/reset icon needs a new placeholder.
    private void regenerateOfflineCover(Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        ArtworkRepository.generatedCoverFile(baseName).delete();
        offlineArtTried.remove(shortcut.file.getPath());
        if (ArtworkRepository.effectiveCover(baseName) == null) ensureOfflineArt(shortcut);
    }

    // "Artwork" in the game menu: one window with everything that can be done to a game's images.
    private void showArtworkMenu(Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        boolean hasArtwork = ArtworkRepository.coverFile(baseName).exists()
                || ArtworkRepository.bannerFile(baseName).exists();
        boolean hasUserIcon = ArtworkRepository.userIconFile(baseName).exists();
        boolean hasUserImages = ArtworkRepository.isUsable(ArtworkRepository.userCoverFile(baseName))
                || ArtworkRepository.isUsable(ArtworkRepository.userBannerFile(baseName));

        List<ThemedAlertHost.ActionItem> items = new ArrayList<>();
        items.add(new ThemedAlertHost.ActionItem("Search SteamGridDB...", () -> promptArtworkSource(shortcut)));
        items.add(new ThemedAlertHost.ActionItem("Re-download automatically", () -> redownloadArtwork(shortcut)));
        items.add(new ThemedAlertHost.ActionItem("Choose cover from gallery", () -> pickImage(shortcut, PICK_COVER)));
        items.add(new ThemedAlertHost.ActionItem("Choose background from gallery", () -> pickImage(shortcut, PICK_BACKGROUND)));
        items.add(new ThemedAlertHost.ActionItem("Choose icon from gallery", () -> pickImage(shortcut, PICK_ICON)));
        if (hasUserIcon) {
            items.add(new ThemedAlertHost.ActionItem("Reset icon", () -> resetIcon(shortcut)));
        }
        if (hasUserImages) {
            items.add(new ThemedAlertHost.ActionItem("Reset my cover & background", () -> resetCustomImages(shortcut)));
        }
        if (hasArtwork) {
            items.add(new ThemedAlertHost.ActionItem("Remove cover & banner", () -> confirmRemoveArtwork(shortcut), 0, true));
        }
        ThemedAlertHost.actions(activity, "Artwork", items);
    }

    private void pickImage(Shortcut shortcut, int target) {
        shortcutForIconUpdate = shortcut;
        pendingPickTarget = target;
        if (iconPickerLauncher != null) iconPickerLauncher.run();
    }

    private void promptArtworkSource(Shortcut shortcut) {
        String current = shortcut.getExtra(ArtworkRepository.EXTRA_SOURCE, "");
        ThemedAlertHost.prompt(
                activity,
                "SteamGridDB source",
                "Game name, steamgriddb.com game link or ID, steam:APPID, or a direct image URL (used as your cover).",
                ArtworkRepository.sourceToInput(current, shortcut.name),
                "Search",
                value -> applyArtworkSource(shortcut, value)
        );
    }

    private void applyArtworkSource(Shortcut shortcut, String raw) {
        String parsed = ArtworkRepository.parseUserInput(raw);
        if (parsed == null) {
            redownloadArtwork(shortcut);
            return;
        }
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File cover = ArtworkRepository.coverFile(baseName);
        File banner = ArtworkRepository.bannerFile(baseName);

        if (parsed.startsWith("url:")) {
            toast("Downloading cover...");
            // A link the user typed is their own choice: stored like a picked image.
            ArtworkRepository.enqueueDirect(parsed.substring(4), ArtworkRepository.userCoverFile(baseName),
                    ArtworkRepository.coverMaxLongSide(),
                    result -> postToUi(() -> {
                        if (result.coverSaved) {
                            publishLibraryItems();
                            toast("Cover updated");
                        } else {
                            toast(result.message);
                        }
                    }));
            return;
        }

        toast("Searching SteamGridDB...");
        ArtworkRepository.Job job = newArtworkJob(shortcut, parsed, true, true, cover, banner);
        ArtworkRepository.enqueue(job, result -> postToUi(() -> {
            if (result.anySaved()) {
                // An explicit search replaces the user's own image of each kind it just found.
                if (result.coverSaved) ArtworkRepository.userCoverFile(baseName).delete();
                if (result.bannerSaved) ArtworkRepository.userBannerFile(baseName).delete();
                // Remember the source only once it produced something, so a typo does not stick.
                shortcut.putExtra(ArtworkRepository.EXTRA_SOURCE, parsed);
                shortcut.saveData();
                loadShortcutsList();
                toast("Artwork updated");
            } else {
                if (result.authProblem) warnApiKeyOnce();
                toast(result.message);
            }
        }));
    }

    private void redownloadArtwork(Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        toast("Downloading artwork...");
        ArtworkRepository.forget(activity, baseName);
        ArtworkRepository.Job job = newArtworkJob(shortcut, null, true, true,
                ArtworkRepository.coverFile(baseName), ArtworkRepository.bannerFile(baseName));
        ArtworkRepository.enqueue(job, result -> postToUi(() -> {
            // Back to automatic mode, whatever the previous source or "no artwork" choice was.
            shortcut.putExtra(ArtworkRepository.EXTRA_SOURCE, null);
            shortcut.saveData();
            if (result.anySaved()) {
                if (result.coverSaved) ArtworkRepository.userCoverFile(baseName).delete();
                if (result.bannerSaved) ArtworkRepository.userBannerFile(baseName).delete();
                loadShortcutsList();
                toast("Artwork updated");
            } else {
                if (result.authProblem) warnApiKeyOnce();
                toast(result.message);
            }
        }));
    }

    private void confirmRemoveArtwork(Shortcut shortcut) {
        ThemedAlertHost.confirm(
                activity,
                "Remove artwork?",
                "The downloaded cover and banner will be deleted and won't be downloaded again for this game. "
                        + "Images you chose yourself stay. \"Re-download automatically\" brings the downloaded ones back.",
                "Remove",
                () -> removeArtwork(shortcut),
                true
        );
    }

    private void removeArtwork(Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        ArtworkRepository.coverFile(baseName).delete();
        ArtworkRepository.bannerFile(baseName).delete();
        shortcut.putExtra(ArtworkRepository.EXTRA_SOURCE, ArtworkRepository.SOURCE_NONE);
        shortcut.saveData();
        loadShortcutsList();
        toast("Artwork removed");
    }

    private void resetIcon(Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        ArtworkRepository.userIconFile(baseName).delete();
        loadShortcutsList();
        regenerateOfflineCover(shortcut);
        toast("Icon reset");
    }

    // The user's own cover (NAME.user.png): wins over the downloaded one and is left alone by the
    // downloader; shown as picked, not reduced to a blurred backdrop.
    private void updateShortcutCover(Uri sourceUri, Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File cover = ArtworkRepository.userCoverFile(baseName);
        ArtworkRepository.enqueueFromUri(activity, sourceUri, cover, ArtworkRepository.coverMaxLongSide(),
                result -> postToUi(() -> {
                    if (result.coverSaved) {
                        publishLibraryItems();
                        toast("Cover updated");
                    } else {
                        toast(result.message);
                    }
                }));
    }

    // The user's own background (NAME.user.png in banners/): the wide picture behind the game's
    // page and the Library pager, shown sharp and full-bleed like a banner.
    private void updateShortcutBackground(Uri sourceUri, Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File background = ArtworkRepository.userBannerFile(baseName);
        ArtworkRepository.enqueueFromUri(activity, sourceUri, background, ArtworkRepository.bannerMaxLongSide(),
                result -> postToUi(() -> {
                    if (result.coverSaved) {
                        publishLibraryItems();
                        toast("Background updated");
                    } else {
                        toast(result.message);
                    }
                }));
    }

    private void resetCustomImages(Shortcut shortcut) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        ArtworkRepository.userCoverFile(baseName).delete();
        ArtworkRepository.userBannerFile(baseName).delete();
        loadShortcutsList();
        regenerateOfflineCover(shortcut);
        toast("Your cover & background reset");
    }

    private void openGameDetails(Shortcut shortcut) {
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).openGameDetail(shortcut.file.getPath());
        }
    }

    private void updateShortcutIcon(Uri sourceUri, Shortcut shortcut) {
        try {
            File targetDir = getImagesDir(false);
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File destFile = new File(targetDir, baseName + ".user.png");

            try (InputStream is = activity.getContentResolver().openInputStream(sourceUri);
                 OutputStream os = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
            }

            Toast.makeText(activity, "Icon updated!", Toast.LENGTH_SHORT).show();
            loadShortcutsList();
            regenerateOfflineCover(shortcut);

        } catch (Exception e) {
            Toast.makeText(activity, "Error saving icon", Toast.LENGTH_SHORT).show();
        }
    }

    
    private File resolveExeFile(Shortcut item) {
        if (item.path == null || item.path.isEmpty()) return null;

        String path = item.path.replace("\\", "/").trim();

        if (path.startsWith("\"") && path.endsWith("\""))
            path = path.substring(1, path.length() - 1);

        if (path.startsWith("/")) {
            File f = new File(path);
            if (f.exists()) return f;
        }

        if (path.length() >= 3 && path.charAt(1) == ':' && path.charAt(2) == '/') {
            String drive    = path.substring(0, 1).toLowerCase();
            String relative = path.substring(3);

            if (item.container != null) {
                for (String[] entry : item.container.drivesIterator()) {
                    if (entry == null || entry.length < 2 || entry[0] == null || entry[1] == null) continue;
                    if (entry[0].replace(":", "").trim().equalsIgnoreCase(drive)) {
                        File f = new File(entry[1], relative);
                        if (f.exists()) return f;
                    }
                }
            }

            switch (drive) {
                case "c": {
                    File root = item.container != null ? item.container.getRootDir() : null;
                    if (root != null) {
                        File f = new File(root, ".wine/drive_c/" + relative);
                        if (f.exists()) return f;
                    }
                    break;
                }
                case "d": {
                    File f = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), relative);
                    if (f.exists()) return f;
                    f = new File(Environment.getExternalStorageDirectory(), relative);
                    if (f.exists()) return f;
                    break;
                }
                case "z": {
                    File f = new File("/" + relative);
                    if (f.exists()) return f;
                    break;
                }
            }
        }

        return null;
    }

    private void runFromShortcut(Shortcut shortcut) {
        shortcut.putExtra("lastRunAt", String.valueOf(System.currentTimeMillis()));
        shortcut.saveData();
        if (libraryController != null) {
            libraryController.setSelectedShortcutPath(shortcut.file.getPath());
            publishLibraryItems();
        }
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.putExtra("container_id", shortcut.container.id);
        intent.putExtra("shortcut_path", shortcut.file.getPath());
        intent.putExtra("shortcut_name", shortcut.name);
        activity.startActivity(intent);
    }

    private void handleShortcutAction(Shortcut shortcut, String action) {
        Context context = activity;
        if (context == null) return;

        if (LibraryComposeHost.ACTION_FAVORITE.equals(action)) {
            boolean favorite = "1".equals(shortcut.getExtra("favorite", "0"));
            shortcut.putExtra("favorite", favorite ? "0" : "1");
            shortcut.saveData();
            loadShortcutsList();
        }
        else if (LibraryComposeHost.ACTION_SETTINGS.equals(action)) {
            ShortcutSettingsComposeDialog.show(activity, shortcut, this::loadShortcutsList);
        }
        else if (LibraryComposeHost.ACTION_ICON.equals(action)) {
            showArtworkMenu(shortcut);
        }
        else if (LibraryComposeHost.ACTION_REMOVE.equals(action)) {
            ThemedAlertHost.confirm(
                    activity,
                    "Remove shortcut?",
                    "Do you want to remove this shortcut?",
                    "Remove",
                    () -> {
                        boolean fileDeleted = shortcut.file.delete();
                        try {
                            String basePath = shortcut.file.getPath().substring(0, shortcut.file.getPath().lastIndexOf("."));
                            new File(basePath + ".lnk").delete();
                            new File(basePath + ".bat").delete();
                        } catch (Exception ignored) {}

                        if (fileDeleted) {
                            ArtworkRepository.deleteArtworkIfUnused(activity, shortcut.file);
                            disableShortcutOnScreen(activity, shortcut);
                            loadShortcutsList();
                            Toast.makeText(context, "Shortcut removed.", Toast.LENGTH_SHORT).show();
                        }
                    },
                    true
            );
        }
        else if (LibraryComposeHost.ACTION_CLONE.equals(action)) {
            ContainerManager containerManager = new ContainerManager(context);
            ArrayList<Container> containers = containerManager.getContainers();
            List<ThemedAlertHost.ActionItem> items = new ArrayList<>();
            for (Container container : containers) {
                items.add(new ThemedAlertHost.ActionItem(container.getName(), () -> {
                    if (shortcut.cloneToContainer(container)) {
                        Toast.makeText(context, "Cloned successfully.", Toast.LENGTH_SHORT).show();
                        loadShortcutsList();
                    }
                }));
            }
            ThemedAlertHost.actions(activity, "Select Container", items);
        }
        else if (LibraryComposeHost.ACTION_HOME.equals(action)) {
            if (shortcut.getExtra("uuid").equals("")) shortcut.genUUID();
            addShortcutToScreen(shortcut);
        }
        else if (LibraryComposeHost.ACTION_EXPORT.equals(action)) {
            exportShortcut(shortcut);
        }
    }

    private void exportShortcut(Shortcut shortcut) {
        SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(activity);
        String uriString = sharedPreferences.getString("shortcuts_export_path_uri", null);
        File shortcutsDir;

        if (uriString != null) {
            Uri folderUri = Uri.parse(uriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(activity, folderUri);
            if (pickedDir == null || !pickedDir.canWrite()) return;
            shortcutsDir = new File(FileUtils.getFilePathFromUri(activity, folderUri));
        } else {
            shortcutsDir = new File(AppDefaults.DEFAULT_SHORTCUT_EXPORT_PATH);
        }

        if (!shortcutsDir.exists() && !shortcutsDir.mkdirs()) return;
        File exportFile = new File(shortcutsDir, shortcut.file.getName());
        boolean containerIdFound = false;

        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(shortcut.file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("container_id:")) {
                        lines.add("container_id:" + shortcut.container.id);
                        containerIdFound = true;
                    } else {
                        lines.add(line);
                    }
                }
            }
            if (!containerIdFound) lines.add("container_id:" + shortcut.container.id);
            try (FileWriter writer = new FileWriter(exportFile, false)) {
                for (String line : lines) writer.write(line + "\n");
                writer.flush();
            }
            Toast.makeText(activity, exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException ignored) {}
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(activity, XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);
        return new ShortcutInfo.Builder(activity, uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(activity, ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            File iconDir = getImagesDir(false);
            File imgFile = new File(iconDir, FileUtils.getBasename(shortcut.file.getPath()) + ".png");
            Bitmap bmp = imgFile.exists() ? BitmapFactory.decodeFile(imgFile.getPath()) : shortcut.icon;
            if (bmp == null) bmp = BitmapFactory.decodeResource(activity.getResources(), R.drawable.icon_wine);
            
            shortcutManager.requestPinShortcut(buildScreenShortCut(shortcut.name, shortcut.name, shortcut.container.id,
                    shortcut.file.getPath(), Icon.createWithBitmap(bmp), shortcut.getExtra("uuid")), null);
        }
    }

    public static void disableShortcutOnScreen(Context context, Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(context, ShortcutManager.class);
        try {
            shortcutManager.disableShortcuts(Collections.singletonList(shortcut.getExtra("uuid")), context.getString(R.string.shortcut_not_available));
        } catch (Exception e) {}
    }

    public void updateShortcutOnScreen(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        ShortcutManager shortcutManager = getSystemService(activity, ShortcutManager.class);
        try {
            for (ShortcutInfo shortcutInfo : shortcutManager.getPinnedShortcuts()) {
                if (shortcutInfo.getId().equals(uuid)) {
                    shortcutManager.updateShortcuts(Collections.singletonList(
                            buildScreenShortCut(shortLabel, longLabel, containerId, shortcutPath, icon, uuid)));
                    break;
                }
            }
        } catch (Exception e) {}
    }
}
