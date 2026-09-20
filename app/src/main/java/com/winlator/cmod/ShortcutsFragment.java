package com.winlator.cmod;

import static androidx.core.content.ContextCompat.getSystemService;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.bigpicture.steamgrid.SteamGridDBApi;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponse;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponseDeserializer;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridSearchResponse;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.ui.ThemedAlertHost;
import com.winlator.cmod.ui.shortcut.ShortcutSettingsComposeDialog;
import com.winlator.cmod.core.ExeIconExtractor;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.ui.library.LibraryCallbacks;
import com.winlator.cmod.ui.library.LibraryComposeBinding;
import com.winlator.cmod.ui.library.LibraryComposeController;
import com.winlator.cmod.ui.library.LibraryComposeHost;
import com.winlator.cmod.ui.library.LibraryItem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;

public class ShortcutsFragment extends Fragment {
    private static final String TAG = "ShortcutsFragment";
    private static final String STEAMGRID_BASE_URL = "https://www.steamgriddb.com/api/v2/";
    private static String STEAMGRID_API_KEY = "0324c52513634547a7b32d6d323635d0";

    private ContainerManager manager;
    private SharedPreferences preferences;
    private LibraryComposeController libraryController;
    
    private boolean isGridView = false;
    private final ArrayList<Shortcut> allShortcuts = new ArrayList<>();
    private final Set<String> artworkRequests = Collections.synchronizedSet(new HashSet<>());

    private Shortcut shortcutForIconUpdate;
    private ActivityResultLauncher<String> iconPickerLauncher;
    private ActivityResultLauncher<String> contentPickerLauncher;
    private com.winlator.cmod.core.Callback<Uri> pendingContentPickerCallback;

    public static final int IMPORT_SHORTCUT = 1005;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        iconPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null && shortcutForIconUpdate != null) {
                updateShortcutIcon(uri, shortcutForIconUpdate);
            }
        });
        contentPickerLauncher = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            com.winlator.cmod.core.Callback<Uri> cb = pendingContentPickerCallback;
            pendingContentPickerCallback = null;
            if (cb != null) cb.call(uri);
        });
    }

    public void pickContentArchive(com.winlator.cmod.core.Callback<Uri> callback) {
        pendingContentPickerCallback = callback;
        contentPickerLauncher.launch("*/*");
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadShortcutsList();
        if (getActivity() != null && ((AppCompatActivity) getActivity()).getSupportActionBar() != null) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.library);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (manager != null && libraryController != null) loadShortcutsList();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        preferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        if (!preferences.getBoolean("enhanced_library_migrated", false)) {
            isGridView = false;
            preferences.edit()
                    .putBoolean("shortcuts_grid_view", false)
                    .putBoolean("enhanced_library_migrated", true)
                    .apply();
        } else {
            isGridView = preferences.getBoolean("shortcuts_grid_view", false);
        }

        LibraryComposeBinding binding = LibraryComposeHost.create(
                requireContext(),
                isGridView,
                new LibraryCallbacks() {
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
                        getParentFragmentManager().beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new FileManagerFragment())
                                .commit();
                    }
                }
        );
        libraryController = binding.getController();
        return binding.getView();
    }

    private void setGridView(boolean gridView) {
        isGridView = gridView;
        preferences.edit().putBoolean("shortcuts_grid_view", isGridView).apply();
        if (libraryController != null) libraryController.setGridView(isGridView);
    }

    private void fetchCoverFromSteamGrid(Shortcut shortcut, File destFile,
                                          Runnable onSuccess, Runnable onFail) {
        fetchArtworkFromSteamGrid(shortcut, destFile, "600x900", onSuccess, onFail);
    }

    private void fetchBannerFromSteamGrid(Shortcut shortcut, File destFile,
                                           Runnable onSuccess, Runnable onFail) {
        fetchArtworkFromSteamGrid(shortcut, destFile, "460x215", onSuccess, onFail);
    }

    private void fetchArtworkFromSteamGrid(Shortcut shortcut, File destFile, String dimensions,
                                            Runnable onSuccess, Runnable onFail) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        if (prefs.getBoolean("enable_custom_api_key", false)) {
            String custom = prefs.getString("custom_api_key", "");
            if (custom != null && !custom.isEmpty()) STEAMGRID_API_KEY = custom;
        }
        if (STEAMGRID_API_KEY.isEmpty()) {
            if (onFail != null) onFail.run();
            return;
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(STEAMGRID_BASE_URL)
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        SteamGridDBApi api = retrofit.create(SteamGridDBApi.class);
        api.searchGame("Bearer " + STEAMGRID_API_KEY, shortcut.name)
                .enqueue(new Callback<SteamGridSearchResponse>() {
                    @Override
                    public void onResponse(Call<SteamGridSearchResponse> call,
                                           Response<SteamGridSearchResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().data != null
                                && !response.body().data.isEmpty()) {
                            fetchSteamGridArtwork(response.body().data.get(0).id, destFile,
                                    dimensions, onSuccess, onFail);
                        } else if (onFail != null) {
                            onFail.run();
                        }
                    }

                    @Override
                    public void onFailure(Call<SteamGridSearchResponse> call, Throwable error) {
                        Log.e(TAG, "SteamGridDB search failed: " + error.getMessage());
                        if (onFail != null) onFail.run();
                    }
                });
    }

    private void fetchSteamGridArtwork(int gameId, File destFile, String dimensions,
                                        Runnable onSuccess, Runnable onFail) {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SteamGridGridsResponse.class,
                        new SteamGridGridsResponseDeserializer())
                .create();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(STEAMGRID_BASE_URL)
                .client(new OkHttpClient())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        SteamGridDBApi api = retrofit.create(SteamGridDBApi.class);
        api.getGridsByGameId("Bearer " + STEAMGRID_API_KEY, gameId,
                "alternate", dimensions, "static")
                .enqueue(new Callback<SteamGridGridsResponse>() {
                    @Override
                    public void onResponse(Call<SteamGridGridsResponse> call,
                                           Response<SteamGridGridsResponse> response) {
                        if (response.isSuccessful() && response.body() != null
                                && response.body().data != null
                                && !response.body().data.isEmpty()) {
                            downloadAndSaveCover(response.body().data.get(0).url,
                                    destFile, onSuccess, onFail);
                        } else if (onFail != null) {
                            onFail.run();
                        }
                    }

                    @Override
                    public void onFailure(Call<SteamGridGridsResponse> call, Throwable error) {
                        Log.e(TAG, "SteamGridDB artwork failed: " + error.getMessage());
                        if (onFail != null) onFail.run();
                    }
                });
    }

    private void downloadAndSaveCover(String url, File destFile,
                                       Runnable onSuccess, Runnable onFail) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.connect();
                Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream());
                if (bmp == null) { if (onFail != null) onFail.run(); return; }

                if (destFile.getParentFile() != null) destFile.getParentFile().mkdirs();

                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
                bmp.recycle();
                Log.d(TAG, "SteamGridDB cover salvo: " + destFile.getAbsolutePath());
                if (onSuccess != null) onSuccess.run();
            } catch (Exception e) {
                Log.e(TAG, "Falha ao baixar cover do SteamGridDB: " + e.getMessage());
                if (onFail != null) onFail.run();
            }
        });
    }

    private File getImagesDir(boolean isCover) {
        File targetDir = new File(Environment.getExternalStorageDirectory(), isCover ? "Winlator/covers" : "Winlator/icons");
        if (!targetDir.exists()) targetDir.mkdirs();
        
        File nomedia = new File(targetDir, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (IOException e) {}
        }
        return targetDir;
    }

    private File getBannerDir() {
        File targetDir = new File(Environment.getExternalStorageDirectory(), "Winlator/banners");
        if (!targetDir.exists()) targetDir.mkdirs();
        File nomedia = new File(targetDir, ".nomedia");
        if (!nomedia.exists()) {
            try { nomedia.createNewFile(); } catch (IOException ignored) {}
        }
        return targetDir;
    }

    public void loadShortcutsList() {
        ArrayList<Shortcut> shortcuts = manager.loadShortcuts();
        allShortcuts.clear();
        if (shortcuts != null) {
            shortcuts.removeIf(shortcut -> shortcut == null || shortcut.file == null || shortcut.file.getName().isEmpty());
            Bitmap defaultIcon = BitmapFactory.decodeResource(getResources(), R.drawable.icon_wine);
            for (Shortcut shortcut : shortcuts) {
                if (shortcut.icon == null) shortcut.icon = defaultIcon;
            }
            allShortcuts.addAll(shortcuts);
        }
        publishLibraryItems();
    }

    private Shortcut findShortcut(String shortcutPath) {
        for (Shortcut shortcut : allShortcuts) {
            if (shortcut.file != null && shortcut.file.getPath().equals(shortcutPath)) return shortcut;
        }
        return null;
    }

    private void publishLibraryItems() {
        if (libraryController == null) return;
        ArrayList<LibraryItem> items = new ArrayList<>();
        for (Shortcut shortcut : allShortcuts) {
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File userIcon = new File(getImagesDir(false), baseName + ".user.png");
            File autoIcon = new File(getImagesDir(false), baseName + ".png");
            File cover = new File(getImagesDir(true), baseName + ".png");
            File banner = new File(getBannerDir(), baseName + ".png");
            String iconPath = userIcon.exists() ? userIcon.getPath() :
                    (autoIcon.exists() ? autoIcon.getPath() : null);

            items.add(new LibraryItem(
                    shortcut.file.getPath(),
                    shortcut.file.getPath(),
                    shortcut.name,
                    shortcut.container != null ? shortcut.container.getName() : "",
                    cover.exists() ? cover.getPath() : null,
                    banner.exists() ? banner.getPath() : null,
                    iconPath,
                    shortcut.icon,
                    "1".equals(shortcut.getExtra("favorite", "0")),
                    parseLastRunAt(shortcut)
            ));
        }
        libraryController.setItems(items);
    }

    private long parseLastRunAt(Shortcut shortcut) {
        try {
            return Long.parseLong(shortcut.getExtra("lastRunAt", "0"));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void requestArtwork(Shortcut shortcut, String kind) {
        String baseName = FileUtils.getBasename(shortcut.file.getPath());
        File autoIcon = new File(getImagesDir(false), baseName + ".png");
        File cover = new File(getImagesDir(true), baseName + ".png");
        File banner = new File(getBannerDir(), baseName + ".png");

        if ("cover".equals(kind)) {
            requestCover(shortcut, cover, autoIcon);
        } else if ("banner".equals(kind)) {
            requestBanner(shortcut, banner);
        }
    }

    private void requestCover(Shortcut shortcut, File cover, File autoIcon) {
        final String coverKey = "cover:" + shortcut.file.getPath();
        if (!cover.exists() && artworkRequests.add(coverKey)) {
            fetchCoverFromSteamGrid(shortcut, cover, () -> {
                artworkRequests.remove(coverKey);
                if (getActivity() != null) getActivity().runOnUiThread(this::publishLibraryItems);
            }, () -> {
                artworkRequests.remove(coverKey);
                File exeFile = resolveExeFile(shortcut);
                if (exeFile != null && !autoIcon.exists()) {
                    ExeIconExtractor.extractAsync(exeFile, autoIcon, false, () -> {
                        if (getActivity() != null) getActivity().runOnUiThread(this::publishLibraryItems);
                    });
                }
            });
        }
    }

    private void requestBanner(Shortcut shortcut, File banner) {
        final String bannerKey = "banner:" + shortcut.file.getPath();
        if (!banner.exists() && artworkRequests.add(bannerKey)) {
            fetchBannerFromSteamGrid(shortcut, banner, () -> {
                artworkRequests.remove(bannerKey);
                if (getActivity() != null) getActivity().runOnUiThread(this::publishLibraryItems);
            }, () -> artworkRequests.remove(bannerKey));
        }
    }

    private void openGameDetails(Shortcut shortcut) {
        getParentFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                .addToBackStack(null)
                .replace(R.id.FLFragmentContainer, new GameDetailFragment(shortcut.file.getPath()))
                .commit();
    }

    private void updateShortcutIcon(Uri sourceUri, Shortcut shortcut) {
        try {
            File targetDir = getImagesDir(false);
            String baseName = FileUtils.getBasename(shortcut.file.getPath());
            File destFile = new File(targetDir, baseName + ".user.png");

            try (InputStream is = getContext().getContentResolver().openInputStream(sourceUri);
                 OutputStream os = new FileOutputStream(destFile)) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = is.read(buffer)) > 0) os.write(buffer, 0, length);
            }

            Toast.makeText(getContext(), "Icon updated!", Toast.LENGTH_SHORT).show();
            loadShortcutsList();

        } catch (Exception e) {
            Toast.makeText(getContext(), "Error saving icon", Toast.LENGTH_SHORT).show();
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
        Activity activity = getActivity();
        if (activity == null) return;
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
        intent.putExtra("disableXinput", shortcut.getExtra("disableXinput", "0"));
        activity.startActivity(intent);
    }

    private void handleShortcutAction(Shortcut shortcut, String action) {
        Context context = getContext();
        if (context == null) return;

        if (LibraryComposeHost.ACTION_FAVORITE.equals(action)) {
            boolean favorite = "1".equals(shortcut.getExtra("favorite", "0"));
            shortcut.putExtra("favorite", favorite ? "0" : "1");
            shortcut.saveData();
            loadShortcutsList();
        }
        else if (LibraryComposeHost.ACTION_SETTINGS.equals(action)) {
            ShortcutSettingsComposeDialog.show(this, shortcut);
        }
        else if (LibraryComposeHost.ACTION_ICON.equals(action)) {
            shortcutForIconUpdate = shortcut;
            iconPickerLauncher.launch("image/*");
        }
        else if (LibraryComposeHost.ACTION_REMOVE.equals(action)) {
            ThemedAlertHost.confirm(
                    (AppCompatActivity) requireActivity(),
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
                            disableShortcutOnScreen(requireContext(), shortcut);
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
            ThemedAlertHost.actions((AppCompatActivity) requireActivity(), "Select Container", items);
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
                PreferenceManager.getDefaultSharedPreferences(getContext());
        String uriString = sharedPreferences.getString("shortcuts_export_path_uri", null);
        File shortcutsDir;

        if (uriString != null) {
            Uri folderUri = Uri.parse(uriString);
            DocumentFile pickedDir = DocumentFile.fromTreeUri(requireContext(), folderUri);
            if (pickedDir == null || !pickedDir.canWrite()) return;
            shortcutsDir = new File(FileUtils.getFilePathFromUri(requireContext(), folderUri));
        } else {
            shortcutsDir = new File(SettingsFragment.DEFAULT_SHORTCUT_EXPORT_PATH);
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
            Toast.makeText(getContext(), exportFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException ignored) {}
    }

    private ShortcutInfo buildScreenShortCut(String shortLabel, String longLabel, int containerId, String shortcutPath, Icon icon, String uuid) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", containerId);
        intent.putExtra("shortcut_path", shortcutPath);
        return new ShortcutInfo.Builder(getActivity(), uuid)
                .setShortLabel(shortLabel)
                .setLongLabel(longLabel)
                .setIcon(icon)
                .setIntent(intent)
                .build();
    }

    private void addShortcutToScreen(Shortcut shortcut) {
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
        if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
            File iconDir = getImagesDir(false);
            File imgFile = new File(iconDir, FileUtils.getBasename(shortcut.file.getPath()) + ".png");
            Bitmap bmp = imgFile.exists() ? BitmapFactory.decodeFile(imgFile.getPath()) : shortcut.icon;
            if (bmp == null) bmp = BitmapFactory.decodeResource(getResources(), R.drawable.icon_wine);
            
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
        ShortcutManager shortcutManager = getSystemService(requireContext(), ShortcutManager.class);
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
