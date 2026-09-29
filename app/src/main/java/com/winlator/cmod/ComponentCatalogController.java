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

import com.winlator.cmod.components.ComponentCatalog;
import com.winlator.cmod.components.ComponentEntry;
import com.winlator.cmod.components.ComponentException;
import com.winlator.cmod.components.ComponentSource;
import com.winlator.cmod.components.ContentsSource;
import com.winlator.cmod.components.RemovePlan;
import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.ui.onboarding.OnboardingCallbacks;
import com.winlator.cmod.ui.onboarding.OnboardingComponent;
import com.winlator.cmod.ui.onboarding.OnboardingComposeController;
import com.winlator.cmod.ui.settings.WinlatorServicesDialog;
import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Shared by OnboardingActivity (full first-run onboarding, still a standalone Activity)
// and ComponentManagerFragment (the "Components" settings screen, hosted as a Fragment
// of MainActivity). Both used to carry their own copy of this catalog/install/remove
// logic; this class is that logic once, driven through Host so it doesn't need to know
// whether it's living inside an Activity or a Fragment. Where components come from (catalog,
// Proton, drivers) is the job of the ComponentSource classes; what a row does when installed or
// removed is the job of its ComponentEntry - this class only sequences them and drives the UI. onRuntimeSelected/onRequestPermissions
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
        default void onRuntimeSelected(String runtimeIdentifier) {}
        default void onRequestPermissions() {}
    }

    private final Host host;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<ComponentEntry> catalog = new ArrayList<>();
    private ComponentCatalog componentCatalog;

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
        componentCatalog = new ComponentCatalog(host.context());
        contentsManager = componentCatalog.contentsManager();
        contentsManager.syncContents();
        adrenotoolsManager = componentCatalog.adrenotools();

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

    // Call once the compose controller is attached: shows what is installed right away, then
    // refreshes every source from the network and kicks off the core install if it isn't done.
    public void start() {
        rebuildCatalog();
        syncComposeCatalog();
        loadCatalog();
        if (!coreReady) startCoreInstallation();
    }

    public void shutdown() {
        io.shutdownNow();
    }

    public OnboardingCallbacks createCallbacks(ExtraCallbacks extra) {
        return new OnboardingCallbacks() {
            @Override
            public void onInstall(@NonNull String componentId) {
                ComponentEntry entry = findComponent(componentId);
                if (entry != null) installComponent(entry);
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
            public void onOpenServers() {
                // Addresses may have changed: pull the lists again from the new sources.
                WinlatorServicesDialog.show(host.hostActivity(), () -> {
                    if (host.isAlive()) loadCatalog();
                });
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
                            host.context().getString(R.string.app_name) + " core installation failed. Tap retry to try again.", Toast.LENGTH_LONG).show();
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

    // Refreshes the sources one after another, showing each as soon as it is in. A failing source
    // no longer just leaves its part of the list empty: the reason is reported to the user.
    private void loadCatalog() {
        io.execute(() -> {
            String firstError = null;
            int failures = 0;
            for (ComponentSource source : componentCatalog.sources()) {
                try {
                    source.refresh(host.context());
                } catch (ComponentException e) {
                    if (firstError == null) firstError = "Couldn't load " + source.title().toLowerCase() + ": " + e.getMessage();
                    failures++;
                } catch (Exception e) {
                    if (firstError == null) firstError = "Couldn't load " + source.title().toLowerCase();
                    failures++;
                }
                rebuildCatalog();
                runOnUi(() -> {
                    syncComposeCatalog();
                    maybeAutoInstall();
                });
            }
            if (firstError != null) {
                final String message = failures > 1 ? firstError + " (+" + (failures - 1) + " more)" : firstError;
                runOnUi(() -> {
                    if (host.isAlive()) Toast.makeText(host.context(), message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void rebuildCatalog() {
        ArrayList<ComponentEntry> rebuilt = new ArrayList<>(componentCatalog.entries());
        // Catalog rows grouped by type, newest first; driver rows follow in the order they were listed.
        rebuilt.sort(Comparator.comparing((ComponentEntry e) -> e.isDriver())
                .thenComparing((ComponentEntry e) -> e.type)
                .thenComparing((ComponentEntry e) -> e.versionCode, Comparator.reverseOrder()));
        synchronized (catalog) {
            catalog.clear();
            catalog.addAll(rebuilt);
        }
    }

    public static String displayType(ContentProfile.ContentType type) {
        return ContentsSource.displayType(type);
    }

    private ComponentEntry findComponent(String id) {
        synchronized (catalog) {
            for (ComponentEntry entry : catalog) if (entry.id.equals(id)) return entry;
        }
        return null;
    }

    private void syncComposeCatalog() {
        if (composeController == null || !host.isAlive()) return;
        ArrayList<OnboardingComponent> ui = new ArrayList<>();
        synchronized (catalog) {
            for (ComponentEntry entry : catalog) {
                String runtime = entry.runtimeName();
                boolean inUse = runtime != null && !runtime.isEmpty() && WineRuntimeGuard.isInUse(host.context(), runtime);
                ui.add(new OnboardingComponent(
                        entry.id,
                        entry.type,
                        entry.label(),
                        entry.installed,
                        entry.recommended,
                        entry.removable(),
                        runtime,
                        inUse,
                        false
                ));
            }
        }
        composeController.setComponents(ui);
        refreshBundledRuntimeState();
    }

    private void installComponent(ComponentEntry entry) {
        if (installBusy || !entry.canInstall()) return;
        installBusy = true;
        String id = entry.id;
        composeController.setInstallBusy(id, true);
        composeController.updateInstallProgress("Preparing " + entry.name, 0);
        io.execute(() -> {
            String error = null;
            try {
                entry.install(host.context(), this::postInstallProgress);
            } catch (ComponentException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "Unable to install " + entry.name + ".";
            }
            rebuildCatalog();
            final String message = error;
            runOnUi(() -> finishInstall(id, message));
        });
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

    private void requestRemoveComponent(String componentId) {
        ComponentEntry entry = findComponent(componentId);
        if (entry == null) return;
        RemovePlan plan = entry.planRemoval(host.context());
        if (plan == null) return;
        if (plan.blocked) {
            com.winlator.cmod.ui.ThemedAlertHost.info(host.hostActivity(), plan.title, plan.message);
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                host.hostActivity(),
                plan.title,
                plan.message,
                "Delete",
                () -> removeComponent(entry),
                true
        );
    }

    private void removeComponent(ComponentEntry entry) {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy(entry.id, true);
        io.execute(() -> {
            String error = null;
            try {
                entry.remove(host.context());
            } catch (ComponentException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "Unable to remove " + entry.name + ".";
            }
            rebuildCatalog();
            final String message = error;
            runOnUi(() -> {
                finishInstall(entry.id, message);
                if (message == null && host.isAlive()) Toast.makeText(host.context(), entry.removedMessage(), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void maybeAutoInstall() {
        if (autoInstallDispatched || installBusy || pendingInstallType == null || pendingInstallType.isEmpty()) return;
        synchronized (catalog) {
            for (ComponentEntry entry : catalog) {
                if (!entry.type.equalsIgnoreCase(pendingInstallType)) continue;
                // A driver that is already installed is not what an auto-install is looking for.
                if (entry.isDriver() && entry.installed) continue;
                if (pendingInstallVersion != null && !pendingInstallVersion.isEmpty()
                        && !entry.name.equalsIgnoreCase(pendingInstallVersion)) continue;
                if (!entry.isDriver() && pendingInstallVersionCode != Integer.MIN_VALUE
                        && entry.versionCode != pendingInstallVersionCode) continue;
                autoInstallDispatched = true;
                if (!entry.installed) installComponent(entry);
                return;
            }
        }
    }

    // One "Local package" entry for everything: the picked file's first bytes decide where it
    // goes, so there's no separate "Install local driver" button. Components (.wcp) are tar
    // archives compressed with xz or zstd; AdrenoTools drivers are plain zips with a meta.json.
    // The name/extension isn't used — the system picker often reports names without one.
    public void handleLocalPackagePicked(Uri uri) {
        if (installBusy) return;
        installBusy = true;
        composeController.setInstallBusy("local", true);
        String displayName = localDisplayName(uri);
        composeController.updateInstallProgress("Preparing " + displayName, 0);
        io.execute(() -> {
            switch (sniffLocalPackage(uri)) {
                case LOCAL_PACKAGE_DRIVER:
                    installLocalDriver(uri, displayName);
                    break;
                case LOCAL_PACKAGE_CONTENT: {
                    ContentProfile installed = null;
                    String error = null;
                    try {
                        installed = componentCatalog.contents().installArchive(uri, displayName, 5, this::postInstallProgress);
                    } catch (ComponentException e) {
                        error = e.getMessage();
                    } catch (Exception e) {
                        error = "Unable to install " + displayName + ".";
                    }
                    rebuildCatalog();
                    final ContentProfile done = installed;
                    final String message = error;
                    runOnUi(() -> {
                        finishInstall("local", message);
                        if (done != null) revealCategory(displayType(done.type));
                    });
                    break;
                }
                default:
                    runOnUi(() -> finishInstall("local",
                            "Unsupported package. Pick a component (.wcp) or a driver (.zip)."));
            }
        });
    }

    private static final int LOCAL_PACKAGE_UNKNOWN = 0;
    private static final int LOCAL_PACKAGE_CONTENT = 1;
    private static final int LOCAL_PACKAGE_DRIVER = 2;

    // Runs on the io thread (it opens the file).
    private int sniffLocalPackage(Uri uri) {
        byte[] head = new byte[6];
        int read = 0;
        try (InputStream in = host.context().getContentResolver().openInputStream(uri)) {
            if (in == null) return LOCAL_PACKAGE_UNKNOWN;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) break;
                read += n;
            }
        } catch (Exception e) {
            return LOCAL_PACKAGE_UNKNOWN;
        }
        // zip local file header: "PK\3\4"
        if (read >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return LOCAL_PACKAGE_DRIVER;
        }
        // xz: FD '7' 'z' 'X' 'Z' 00
        if (read >= 6 && (head[0] & 0xFF) == 0xFD && head[1] == '7' && head[2] == 'z'
                && head[3] == 'X' && head[4] == 'Z' && head[5] == 0) {
            return LOCAL_PACKAGE_CONTENT;
        }
        // zstd: 28 B5 2F FD
        if (read >= 4 && (head[0] & 0xFF) == 0x28 && (head[1] & 0xFF) == 0xB5
                && (head[2] & 0xFF) == 0x2F && (head[3] & 0xFF) == 0xFD) {
            return LOCAL_PACKAGE_CONTENT;
        }
        return LOCAL_PACKAGE_UNKNOWN;
    }

    // Runs on the io thread.
    private void installLocalDriver(Uri uri, String displayName) {
        postInstallProgress("Installing " + displayName, -1);
        String installed = adrenotoolsManager.installDriver(uri);
        boolean ok = installed != null && !installed.isEmpty();
        rebuildCatalog();
        runOnUi(() -> {
            finishInstall("local", ok ? null : "Unable to install the driver. Make sure it's an AdrenoTools driver package.");
            if (ok) revealCategory("AdrenoTools");
        });
    }

    // Switches the Components screen to the category the new package landed in.
    private void revealCategory(String type) {
        if (composeController != null && type != null && !type.isEmpty() && host.isAlive()) {
            composeController.revealCategory(type);
        }
    }
}
