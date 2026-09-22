package com.winlator.cmod;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.ui.onboarding.OnboardingCallbacks;
import com.winlator.cmod.ui.onboarding.OnboardingComponent;
import com.winlator.cmod.ui.onboarding.OnboardingComposeController;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

// Shared by OnboardingActivity (full first-run onboarding, still a standalone Activity)
// and ComponentManagerFragment (the "Components" settings screen, hosted as a Fragment
// of MainActivity). Both used to carry their own copy of this catalog/install/remove
// logic; this class is that logic once, driven through Host so it doesn't need to know
// whether it's living inside an Activity or a Fragment. onRuntimeSelected/onRequestPermissions
// only ever fire from the pages OnboardingActivity's full flow shows (Runtime/Access),
// which ComponentManagerFragment's component-manager mode never reaches - its ExtraCallbacks
// simply leaves those two as no-ops.
public class ComponentCatalogController {
    public interface Host {
        Context context();
        AppCompatActivity hostActivity();
        boolean isAlive();
        void runOnUi(Runnable action);
        void close();
    }

    public interface ExtraCallbacks {
        void onBrowseLocal();
        void onBrowseDriver();
        default void onRuntimeSelected(String runtimeIdentifier) {}
        default void onRequestPermissions() {}
    }

    private interface FailureCallback { void call(String error); }

    private static final class ComponentItem {
        ContentProfile profile;
        ProtonPackageManager.PackageInfo packageInfo;
        String type;
        String name;
        String url;
        String entryName;
        int versionCode;
        boolean installed;
        boolean recommended;
    }

    private final Host host;
    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<ComponentItem> catalog = new ArrayList<>();
    private final ArrayList<RemoteDriverCatalog.Entry> remoteDrivers = new ArrayList<>();

    private SharedPreferences preferences;
    private ContentsManager contentsManager;
    private AdrenotoolsManager adrenotoolsManager;
    private OnboardingComposeController composeController;
    private boolean coreReady;
    private int coreProgress;
    private boolean installBusy;
    private String pendingInstallType;
    private String pendingInstallVersion;
    private int pendingInstallVersionCode;
    private boolean autoInstallDispatched;

    public ComponentCatalogController(Host host) {
        this.host = host;
    }

    public void initialize(String autoInstallType, String autoInstallVersion, int autoInstallVersionCode) {
        pendingInstallType = autoInstallType;
        pendingInstallVersion = autoInstallVersion;
        pendingInstallVersionCode = autoInstallVersionCode;

        preferences = PreferenceManager.getDefaultSharedPreferences(host.context());
        contentsManager = new ContentsManager(host.context());
        contentsManager.syncContents();
        adrenotoolsManager = new AdrenotoolsManager(host.context());

        ImageFs imageFs = ImageFs.find(host.context());
        coreReady = imageFs.isValid() && imageFs.getVersion() >= ImageFsInstaller.LATEST_VERSION;
        coreProgress = coreReady ? 100 : 0;
    }

    public void attachComposeController(OnboardingComposeController composeController) {
        this.composeController = composeController;
    }

    public boolean isCoreReady() {
        return coreReady;
    }

    public int getCoreProgress() {
        return coreProgress;
    }

    public ContentsManager getContentsManager() {
        return contentsManager;
    }

    public SharedPreferences getPreferences() {
        return preferences;
    }

    // Call once the compose controller is attached: loads the catalog, remote drivers,
    // and kicks off the core install if it isn't already done.
    public void start() {
        syncComposeCatalog();
        loadCatalog();
        loadRemoteDrivers();
        if (!coreReady) startCoreInstallation();
    }

    public void shutdown() {
        io.shutdownNow();
    }

    public OnboardingCallbacks createCallbacks(ExtraCallbacks extra) {
        return new OnboardingCallbacks() {
            @Override
            public void onInstall(@NonNull String componentId) {
                ComponentItem item = findComponent(componentId);
                if (item != null) installComponent(item);
                else if (componentId.startsWith("remote-driver:")) installRemoteDriver(componentId);
            }

            @Override
            public void onRemove(@NonNull String componentId) {
                requestRemoveComponent(componentId);
            }

            @Override
            public void onInstallBundledRuntime() {
                installBundledRuntime();
            }

            @Override
            public void onRemoveBundledRuntime() {
                requestRemoveBundledRuntime();
            }

            @Override
            public void onBrowseLocal() {
                extra.onBrowseLocal();
            }

            @Override
            public void onBrowseDriver() {
                extra.onBrowseDriver();
            }

            @Override
            public void onRuntimeSelected(@NonNull String runtimeIdentifier) {
                extra.onRuntimeSelected(runtimeIdentifier);
            }

            @Override
            public void onRequestPermissions() {
                extra.onRequestPermissions();
            }

            @Override
            public void onRetryCore() {
                if (!coreReady) startCoreInstallation();
            }

            @Override
            public void onCloseComponents() {
                host.close();
            }
        };
    }

    private void runOnUi(Runnable action) {
        host.runOnUi(action);
    }

    private void startCoreInstallation() {
        if (installBusy) return;
        ImageFsInstaller.installFromAssetsSilently(host.hostActivity(), new ImageFsInstaller.InstallationProgressListener() {
            @Override
            public void onProgress(int progress) {
                coreProgress = Math.max(0, Math.min(100, progress));
                if (composeController != null) composeController.updateCore(coreReady, coreProgress);
            }

            @Override
            public void onFinished(boolean success) {
                coreReady = success;
                coreProgress = success ? 100 : 0;
                if (composeController != null) composeController.updateCore(coreReady, coreProgress);
                refreshBundledRuntimeState();
                syncComposeCatalog();
                if (!success && host.isAlive()) {
                    Toast.makeText(host.context(),
                            "WinZ core installation failed. Tap retry to try again.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void refreshBundledRuntimeState() {
        if (composeController == null || !host.isAlive()) return;
        composeController.updateBundledRuntime(
                WineRuntimeGuard.isBundledMainInstalled(host.context()),
                WineRuntimeGuard.isInUse(host.context(), WineInfo.MAIN_WINE_VERSION.identifier())
        );
    }

    private void installBundledRuntime() {
        if (installBusy || !coreReady) return;
        installBusy = true;
        String bundledId = "bundled:" + ProtonPackageManager.DEFAULT_IDENTIFIER;
        String bundledName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                ? ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER).title
                : ProtonPackageManager.DEFAULT_IDENTIFIER;
        composeController.setInstallBusy(bundledId, true);
        composeController.updateInstallProgress("Installing " + bundledName, -1);
        io.execute(() -> {
            boolean success;
            try {
                ImageFsInstaller.installWineFromAssets(null, host.hostActivity());
                success = WineRuntimeGuard.isBundledMainInstalled(host.context());
            } catch (Exception error) {
                success = false;
            }
            final boolean installed = success;
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                refreshBundledRuntimeState();
                syncComposeCatalog();
                if (!installed && host.isAlive()) Toast.makeText(host.context(), "Unable to install " + bundledName + ".", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void requestRemoveBundledRuntime() {
        String using = WineRuntimeGuard.getContainerUsing(host.context(), WineInfo.MAIN_WINE_VERSION.identifier());
        String bundledName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                ? ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER).title
                : ProtonPackageManager.DEFAULT_IDENTIFIER;
        if (using != null) {
            com.winlator.cmod.ui.ThemedAlertHost.info(
                    host.hostActivity(),
                    "Proton is in use",
                    bundledName + " cannot be deleted because it is used by " + using + "."
            );
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                host.hostActivity(),
                "Delete " + bundledName + "?",
                "The bundled Proton files will be removed. You can install them again later.",
                "Delete",
                this::removeBundledRuntime,
                true
        );
    }

    private void removeBundledRuntime() {
        if (installBusy) return;
        installBusy = true;
        String bundledId = "bundled:" + ProtonPackageManager.DEFAULT_IDENTIFIER;
        String bundledName = ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                ? ProtonPackageManager.getPackage(ProtonPackageManager.DEFAULT_IDENTIFIER).title
                : ProtonPackageManager.DEFAULT_IDENTIFIER;
        composeController.setInstallBusy(bundledId, true);
        io.execute(() -> {
            boolean removed = WineRuntimeGuard.removeBundledMain(host.context());
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                refreshBundledRuntimeState();
                if (!removed && host.isAlive()) Toast.makeText(host.context(), bundledName + " could not be deleted.", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void loadCatalog() {
        io.execute(() -> {
            try {
                String url = preferences.getString("downloadable_contents_url", ContentsManager.REMOTE_PROFILES);
                try (Response response = http.newCall(new Request.Builder().url(url).build()).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        contentsManager.setRemoteProfiles(response.body().string());
                    }
                }
            } catch (Exception ignored) {
            }
            contentsManager.syncContents();
            rebuildCatalog();
            runOnUi(() -> {
                syncComposeCatalog();
                maybeAutoInstall();
            });
        });
    }

    private void rebuildCatalog() {
        ArrayList<ComponentItem> rebuilt = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, ComponentItem> newest = new HashMap<>();

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            for (ContentProfile profile : contentsManager.getProfiles(type)) {
                String typeName = displayType(type);
                if (typeName.isEmpty() || profile.verName == null || profile.verName.isEmpty()) continue;
                ComponentItem item = new ComponentItem();
                item.profile = profile;
                item.type = typeName;
                item.name = profile.verName;
                item.versionCode = profile.verCode;
                item.url = profile.remoteUrl;
                item.installed = isInstalled(profile);
                item.entryName = item.installed ? installedEntryName(type, profile) : "";
                String key = typeName + ":" + profile.verCode + ":" + profile.verName;
                if (!seen.add(key)) continue;
                rebuilt.add(item);
                ComponentItem current = newest.get(typeName);
                if (current == null || item.versionCode > current.versionCode) newest.put(typeName, item);
            }
        }

        for (ContentProfile.ContentType type : ContentProfile.ContentType.values()) {
            for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
                String typeName = displayType(type);
                if (typeName.isEmpty()) continue;
                String key = typeName + ":" + profile.verCode + ":" + profile.verName;
                if (seen.add(key)) {
                    ComponentItem item = new ComponentItem();
                    item.profile = profile;
                    item.type = typeName;
                    item.name = profile.verName;
                    item.versionCode = profile.verCode;
                    item.installed = true;
                    item.entryName = ContentsManager.getEntryName(profile);
                    rebuilt.add(item);
                }
            }
        }

        for (ProtonPackageManager.PackageInfo packageInfo : ProtonPackageManager.getPackages()) {
            if (ProtonPackageManager.DEFAULT_IDENTIFIER.equals(packageInfo.identifier)) continue;
            ComponentItem item = new ComponentItem();
            item.packageInfo = packageInfo;
            item.type = "Proton";
            item.name = packageInfo.title;
            item.versionCode = 0;
            item.installed = ProtonPackageManager.isInstalled(host.context(), packageInfo.identifier);
            item.entryName = item.installed ? packageInfo.identifier : "";
            rebuilt.add(item);
        }

        for (ComponentItem item : newest.values()) item.recommended = true;
        rebuilt.sort(Comparator.comparing((ComponentItem i) -> i.type)
                .thenComparing((ComponentItem i) -> i.versionCode, Comparator.reverseOrder()));
        synchronized (catalog) {
            catalog.clear();
            catalog.addAll(rebuilt);
        }
    }

    private String installedEntryName(ContentProfile.ContentType type, ContentProfile candidate) {
        for (ContentProfile installed : contentsManager.getInstalledProfiles(type)) {
            if (installed.verCode == candidate.verCode && installed.verName.equals(candidate.verName)) {
                return ContentsManager.getEntryName(installed);
            }
        }
        return "";
    }

    private boolean isInstalled(ContentProfile candidate) {
        for (ContentProfile installed : contentsManager.getInstalledProfiles(candidate.type)) {
            if (installed.verCode == candidate.verCode && installed.verName.equals(candidate.verName)) return true;
        }
        return false;
    }

    public static String displayType(ContentProfile.ContentType type) {
        String raw = type.toString();
        String key = raw.toLowerCase(Locale.ENGLISH).replace("content_type_", "").replace("_", "");
        if (key.contains("wowbox64")) return "WOWBox64";
        if (key.contains("box64")) return "Box64";
        if (key.contains("fexcore")) return "FEXCore";
        if (key.contains("vkd3d")) return "VKD3D";
        if (key.contains("dxvk")) return "DXVK";
        if (key.contains("proton")) return "Proton";
        if (key.contains("wine")) return "Wine";
        return "";
    }

    private String componentId(ComponentItem item) {
        if (item.packageInfo != null) return "release-proton:" + item.packageInfo.identifier;
        return item.type + ":" + item.versionCode + ":" + item.name;
    }

    private ComponentItem findComponent(String id) {
        synchronized (catalog) {
            for (ComponentItem item : catalog) if (componentId(item).equals(id)) return item;
        }
        return null;
    }

    private void syncComposeCatalog() {
        if (composeController == null || !host.isAlive()) return;
        ArrayList<OnboardingComponent> ui = new ArrayList<>();
        synchronized (catalog) {
            for (ComponentItem item : catalog) {
                String runtime = (item.type.equals("Wine") || item.type.equals("Proton")) && item.installed
                        ? item.entryName : null;
                boolean inUse = runtime != null && !runtime.isEmpty() && WineRuntimeGuard.isInUse(host.context(), runtime);
                ui.add(new OnboardingComponent(
                        componentId(item),
                        item.type,
                        item.name,
                        item.installed,
                        item.recommended,
                        item.installed,
                        runtime,
                        inUse,
                        false
                ));
            }
        }

        Set<String> existingDrivers = new HashSet<>();
        for (String id : adrenotoolsManager.enumarateInstalledDrivers()) {
            String label = adrenotoolsManager.getDriverName(id) + " " + adrenotoolsManager.getDriverVersion(id);
            ui.add(new OnboardingComponent(
                    "adrenotools:" + id, "AdrenoTools", label.trim(), true, false, true,
                    null, false, false
            ));
            existingDrivers.add(label.trim().toLowerCase(Locale.ENGLISH));
        }
        synchronized (remoteDrivers) {
            for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                if (existingDrivers.contains(driver.name.toLowerCase(Locale.ENGLISH))) continue;
                ui.add(new OnboardingComponent(
                        remoteDriverId(driver), "AdrenoTools", driver.name + " • " + driver.repository,
                        false, false, false, null, false, false
                ));
            }
        }
        composeController.setComponents(ui);
        refreshBundledRuntimeState();
    }

    private void installComponent(ComponentItem item) {
        if (item.packageInfo != null) {
            installProtonPackage(item);
            return;
        }
        if (installBusy || item.url == null || item.url.isEmpty()) return;
        installBusy = true;
        String id = componentId(item);
        composeController.setInstallBusy(id, true);
        composeController.updateInstallProgress("Preparing " + item.name, 0);
        io.execute(() -> {
            File archive = new File(host.context().getCacheDir(), "winz-component-" + System.nanoTime());
            try {
                if (!download(item.url, archive, item.name)) throw new Exception("Download failed");
                installContentArchive(Uri.fromFile(archive), item.name, 72, () -> {
                    rebuildCatalog();
                    runOnUi(() -> finishInstall(id, null));
                }, error -> runOnUi(() -> finishInstall(id, error)));
            } catch (Exception error) {
                runOnUi(() -> finishInstall(id, "Unable to install " + item.name + "."));
            } finally {
                archive.delete();
            }
        });
    }

    private void installProtonPackage(ComponentItem item) {
        if (installBusy || item.packageInfo == null) return;
        installBusy = true;
        String id = componentId(item);
        ProtonPackageManager.PackageInfo packageInfo = item.packageInfo;
        composeController.setInstallBusy(id, true);
        composeController.updateInstallProgress("Preparing " + item.name, 0);
        io.execute(() -> {
            File archive = new File(host.context().getCacheDir(), packageInfo.identifier + "-" + System.nanoTime());
            boolean installed = false;
            try {
                boolean downloaded = ProtonPackageManager.downloadPackage(
                        packageInfo,
                        archive,
                        progress -> postInstallProgress(
                                "Downloading " + item.name,
                                Math.min(70, progress * 70 / 100)
                        )
                );
                if (downloaded) {
                    postInstallProgress("Installing " + item.name, 72);
                    installed = ProtonPackageManager.installPackage(host.context(), packageInfo.identifier, archive);
                }
            } finally {
                archive.delete();
            }
            if (installed) rebuildCatalog();
            final boolean success = installed;
            runOnUi(() -> finishInstall(
                    id,
                    success ? null : "Unable to install " + item.name + "."
            ));
        });
    }

    private void installContentArchive(Uri uri, String displayName, int startProgress,
                                       Runnable success, FailureCallback failure) {
        postInstallProgress("Installing " + displayName, startProgress);
        contentsManager.extraContentFile(uri, archiveProgress -> {
            int progress = archiveProgress < 0
                    ? -1
                    : startProgress + ((92 - startProgress) * archiveProgress / 100);
            postInstallProgress("Installing " + displayName, progress);
        }, new ContentsManager.OnInstallFinishedCallback() {
            @Override
            public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                failure.call("Package validation failed: " + reason);
            }

            @Override
            public void onSucceed(ContentProfile extracted) {
                String installedName = extracted.verName != null && !extracted.verName.isEmpty()
                        ? extracted.verName : displayName;
                postInstallProgress("Validating " + installedName, 92);
                contentsManager.finishInstallContent(extracted, new ContentsManager.OnInstallFinishedCallback() {
                    @Override
                    public void onFailed(ContentsManager.InstallFailedReason reason, Exception error) {
                        if (reason == ContentsManager.InstallFailedReason.ERROR_EXIST) {
                            postInstallProgress("Installed " + installedName, 100);
                            success.run();
                        }
                        else failure.call("Installation failed: " + reason);
                    }

                    @Override
                    public void onSucceed(ContentProfile installed) {
                        postInstallProgress("Installed " + installedName, 100);
                        success.run();
                    }
                });
            }
        });
    }

    private boolean download(String url, File out, String displayName) {
        try (Response response = http.newCall(new Request.Builder().url(url).build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            long total = response.body().contentLength();
            long copied = 0;
            int lastProgress = -1;
            postInstallProgress("Downloading " + displayName, total > 0 ? 0 : -1);
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(out)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    copied += read;
                    if (total > 0) {
                        int progress = Math.min(70, (int)((copied * 70L) / total));
                        if (progress != lastProgress) {
                            lastProgress = progress;
                            postInstallProgress("Downloading " + displayName, progress);
                        }
                    }
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void postInstallProgress(String label, int progress) {
        runOnUi(() -> {
            if (composeController != null && installBusy) {
                composeController.updateInstallProgress(label, progress);
            }
        });
    }

    private String localDisplayName(Uri uri) {
        String name = null;
        try (Cursor cursor = host.context().getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) name = cursor.getString(column);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        return name == null || name.trim().isEmpty() ? "local component" : name;
    }

    private void finishInstall(String id, String error) {
        contentsManager.syncContents();
        installBusy = false;
        if (composeController != null) composeController.setInstallBusy(null, false);
        syncComposeCatalog();
        if (error != null && host.isAlive()) Toast.makeText(host.context(), error, Toast.LENGTH_LONG).show();
    }

    private ContentProfile findInstalledProfile(String componentId) {
        ComponentItem item = findComponent(componentId);
        if (item == null) return null;
        for (ContentProfile profile : contentsManager.getInstalledProfiles(item.profile.type)) {
            if (profile.verCode == item.versionCode && profile.verName.equals(item.name)) return profile;
        }
        return null;
    }

    private void requestRemoveComponent(String componentId) {
        if (componentId.startsWith("adrenotools:")) {
            String driverId = componentId.substring("adrenotools:".length());
            com.winlator.cmod.ui.ThemedAlertHost.confirm(
                    host.hostActivity(),
                    "Delete driver?",
                    "The installed driver files will be removed.",
                    "Delete",
                    () -> removeDriver(driverId),
                    true
            );
            return;
        }
        ComponentItem item = findComponent(componentId);
        if (item != null && item.packageInfo != null) {
            requestRemoveProtonPackage(item, componentId);
            return;
        }
        ContentProfile profile = findInstalledProfile(componentId);
        if (profile == null) return;
        if (!WineRuntimeGuard.canRemove(host.context(), profile)) {
            String using = WineRuntimeGuard.getContainerUsing(host.context(), ContentsManager.getEntryName(profile));
            com.winlator.cmod.ui.ThemedAlertHost.info(
                    host.hostActivity(),
                    "Runtime is in use",
                    profile.verName + " cannot be deleted because it is used by " + using + "."
            );
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                host.hostActivity(),
                "Delete component?",
                "The installed files will be removed from WinZ.",
                "Delete",
                () -> removeContent(profile, componentId),
                true
        );
    }

    private void requestRemoveProtonPackage(ComponentItem item, String componentId) {
        String identifier = item.packageInfo.identifier;
        String using = WineRuntimeGuard.getContainerUsing(host.context(), identifier);
        if (using != null) {
            com.winlator.cmod.ui.ThemedAlertHost.info(
                    host.hostActivity(),
                    "Runtime is in use",
                    item.name + " cannot be deleted because it is used by " + using + "."
            );
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                host.hostActivity(),
                "Delete component?",
                "The installed files will be removed from WinZ.",
                "Delete",
                () -> {
                    if (installBusy) return;
                    installBusy = true;
                    composeController.setInstallBusy(componentId, true);
                    io.execute(() -> {
                        ProtonPackageManager.deletePackage(host.context(), identifier);
                        rebuildCatalog();
                        runOnUi(() -> {
                            finishInstall(componentId, null);
                            if (host.isAlive()) Toast.makeText(host.context(), "Component removed", Toast.LENGTH_SHORT).show();
                        });
                    });
                },
                true
        );
    }

    private void removeContent(ContentProfile profile, String id) {
        if (installBusy || !WineRuntimeGuard.canRemove(host.context(), profile)) return;
        installBusy = true;
        composeController.setInstallBusy(id, true);
        io.execute(() -> {
            contentsManager.removeContent(profile);
            contentsManager.syncContents();
            rebuildCatalog();
            runOnUi(() -> {
                finishInstall(id, null);
                if (host.isAlive()) Toast.makeText(host.context(), "Component removed", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void removeDriver(String id) {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy("adrenotools:" + id, true);
        io.execute(() -> {
            adrenotoolsManager.removeDriver(id);
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                syncComposeCatalog();
                if (host.isAlive()) Toast.makeText(host.context(), "Driver removed", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void loadRemoteDrivers() {
        io.execute(() -> {
            List<RemoteDriverCatalog.Entry> loaded = RemoteDriverCatalog.load(host.context());
            synchronized (remoteDrivers) {
                remoteDrivers.clear();
                remoteDrivers.addAll(loaded);
            }
            runOnUi(() -> {
                syncComposeCatalog();
                maybeAutoInstall();
            });
        });
    }

    private String remoteDriverId(RemoteDriverCatalog.Entry driver) {
        return "remote-driver:" + driver.name + ":" + Integer.toHexString(driver.url.hashCode());
    }

    private void installRemoteDriver(String componentId) {
        RemoteDriverCatalog.Entry found = null;
        synchronized (remoteDrivers) {
            for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                if (remoteDriverId(driver).equals(componentId)) {
                    found = driver;
                    break;
                }
            }
        }
        if (found == null || installBusy) return;
        final RemoteDriverCatalog.Entry driver = found;
        installBusy = true;
        composeController.setInstallBusy(componentId, true);
        composeController.updateInstallProgress("Installing " + driver.name, -1);
        io.execute(() -> {
            String installed = RemoteDriverCatalog.install(host.context(), driver.url);
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                syncComposeCatalog();
                if ((installed == null || installed.isEmpty()) && host.isAlive()) {
                    Toast.makeText(host.context(), "Unable to install " + driver.name + ".", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void maybeAutoInstall() {
        if (autoInstallDispatched || installBusy || pendingInstallType == null || pendingInstallType.isEmpty()) return;
        if (pendingInstallType.equalsIgnoreCase("AdrenoTools")) {
            synchronized (remoteDrivers) {
                for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                    if (pendingInstallVersion == null || driver.name.equalsIgnoreCase(pendingInstallVersion)) {
                        autoInstallDispatched = true;
                        installRemoteDriver(remoteDriverId(driver));
                        return;
                    }
                }
            }
            return;
        }
        synchronized (catalog) {
            for (ComponentItem item : catalog) {
                if (!item.type.equalsIgnoreCase(pendingInstallType)) continue;
                if (pendingInstallVersion != null && !pendingInstallVersion.isEmpty()
                        && !item.name.equalsIgnoreCase(pendingInstallVersion)) continue;
                if (pendingInstallVersionCode != Integer.MIN_VALUE && item.versionCode != pendingInstallVersionCode) continue;
                autoInstallDispatched = true;
                if (!item.installed) installComponent(item);
                return;
            }
        }
    }

    public void handleLocalComponentPicked(Uri uri) {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy("local", true);
        String displayName = localDisplayName(uri);
        composeController.updateInstallProgress("Preparing " + displayName, 0);
        io.execute(() -> installContentArchive(uri, displayName, 5, () -> {
            rebuildCatalog();
            runOnUi(() -> finishInstall("local", null));
        }, error -> runOnUi(() -> finishInstall("local", error))));
    }

    public void handleLocalDriverPicked(Uri uri) {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy("driver-local", true);
        composeController.updateInstallProgress("Installing " + localDisplayName(uri), -1);
        io.execute(() -> {
            String installed = adrenotoolsManager.installDriver(uri);
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                syncComposeCatalog();
                if ((installed == null || installed.isEmpty()) && host.isAlive()) {
                    Toast.makeText(host.context(), "Unable to install the driver.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
