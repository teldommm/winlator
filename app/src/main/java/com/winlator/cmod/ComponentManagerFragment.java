package com.winlator.cmod;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.compose.ui.platform.ViewCompositionStrategy;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineRuntimeGuard;
import com.winlator.cmod.ui.onboarding.OnboardingCallbacks;
import com.winlator.cmod.ui.onboarding.OnboardingComposeController;
import com.winlator.cmod.ui.onboarding.OnboardingComposeHost;
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

// Hosted the exact same way ShortcutEditorV2/GameDetailFragment are: replaced into
// MainActivity's own R.id.FLFragmentContainer with addToBackStack. Reuses no Activity
// of its own, so there is no new Window/ActivityRecord to resolve an orientation for -
// this is what previously showed a brief portrait flash as ContainersSettingsActivity /
// OnboardingActivity(component-manager mode), and doesn't as a fragment of MainActivity.
// Business logic below is intentionally a focused copy of OnboardingActivity's
// component-manager-mode subset (catalog install/remove, core install, bundled runtime,
// local/driver browse) - OnboardingActivity itself is left untouched since it still runs
// standalone for first-run onboarding.
public class ComponentManagerFragment extends Fragment {
    private static final String ARG_AUTO_INSTALL_TYPE = "auto_install_type";
    private static final String ARG_AUTO_INSTALL_VERSION = "auto_install_version";
    private static final String ARG_AUTO_INSTALL_VERSION_CODE = "auto_install_version_code";

    private final OkHttpClient http = new OkHttpClient();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<ComponentItem> catalog = new ArrayList<>();
    private final ArrayList<RemoteDriverCatalog.Entry> remoteDrivers = new ArrayList<>();

    private final ActivityResultLauncher<Intent> localComponentLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null && data.getData() != null) {
                    handleLocalComponentPicked(data.getData());
                }
            }
    );

    private final ActivityResultLauncher<Intent> localDriverLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                Intent data = result.getData();
                if (result.getResultCode() == Activity.RESULT_OK && data != null && data.getData() != null) {
                    handleLocalDriverPicked(data.getData());
                }
            }
    );

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

    public static ComponentManagerFragment newInstance() {
        return new ComponentManagerFragment();
    }

    public static ComponentManagerFragment newInstance(@NonNull String autoInstallType, String autoInstallVersion, int autoInstallVersionCode) {
        ComponentManagerFragment fragment = new ComponentManagerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_AUTO_INSTALL_TYPE, autoInstallType);
        args.putString(ARG_AUTO_INSTALL_VERSION, autoInstallVersion);
        args.putInt(ARG_AUTO_INSTALL_VERSION_CODE, autoInstallVersionCode);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        pendingInstallType = args != null ? args.getString(ARG_AUTO_INSTALL_TYPE) : null;
        pendingInstallVersion = args != null ? args.getString(ARG_AUTO_INSTALL_VERSION) : null;
        pendingInstallVersionCode = args != null ? args.getInt(ARG_AUTO_INSTALL_VERSION_CODE, Integer.MIN_VALUE) : Integer.MIN_VALUE;

        preferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        contentsManager = new ContentsManager(requireContext());
        contentsManager.syncContents();
        adrenotoolsManager = new AdrenotoolsManager(requireContext());

        ImageFs imageFs = ImageFs.find(requireContext());
        coreReady = imageFs.isValid() && imageFs.getVersion() >= ImageFsInstaller.LATEST_VERSION;
        coreProgress = coreReady ? 100 : 0;

        FrameLayout root = new FrameLayout(requireContext());
        ComposeView composeView = new ComposeView(requireContext());
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed);
        root.addView(composeView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        composeController = OnboardingComposeHost.attachToView(
                requireContext(),
                composeView,
                coreReady,
                coreProgress,
                WineRuntimeGuard.isBundledMainInstalled(requireContext()),
                WineRuntimeGuard.isInUse(requireContext(), WineInfo.MAIN_WINE_VERSION.identifier()),
                new OnboardingCallbacks() {
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
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        localComponentLauncher.launch(intent);
                    }

                    @Override
                    public void onBrowseDriver() {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        localDriverLauncher.launch(intent);
                    }

                    @Override
                    public void onRuntimeSelected(@NonNull String runtimeIdentifier) {
                        // Unreachable: component-manager mode never advances past the
                        // Components page, so the Runtime page is never shown here.
                    }

                    @Override
                    public void onRequestPermissions() {
                        // Unreachable for the same reason as onRuntimeSelected above.
                    }

                    @Override
                    public void onRetryCore() {
                        if (!coreReady) startCoreInstallation();
                    }

                    @Override
                    public void onCloseComponents() {
                        close();
                    }
                }
        );

        syncComposeCatalog();
        loadCatalog();
        loadRemoteDrivers();
        if (!coreReady) startCoreInstallation();

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        applyDetailChrome();
    }

    @Override
    public void onPause() {
        if (!isLandscape() && getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setDetailMode(false);
        }
        super.onPause();
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private void applyDetailChrome() {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        activity.setDetailMode(true);
        if (isLandscape()) {
            activity.setBottomNavigationVisible(false);
            activity.setMainToolbarVisible(false);
        }
    }

    private void close() {
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void runOnUi(Runnable action) {
        Activity activity = getActivity();
        if (activity != null) activity.runOnUiThread(action);
    }

    private void startCoreInstallation() {
        if (installBusy) return;
        ImageFsInstaller.installFromAssetsSilently(requireContext(), new ImageFsInstaller.InstallationProgressListener() {
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
                if (!success && isAdded()) {
                    Toast.makeText(requireContext(),
                            "WinZ core installation failed. Tap retry to try again.", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void refreshBundledRuntimeState() {
        if (composeController == null || !isAdded()) return;
        composeController.updateBundledRuntime(
                WineRuntimeGuard.isBundledMainInstalled(requireContext()),
                WineRuntimeGuard.isInUse(requireContext(), WineInfo.MAIN_WINE_VERSION.identifier())
        );
    }

    private void installBundledRuntime() {
        if (installBusy || !coreReady) return;
        installBusy = true;
        String bundledId = "bundled:" + com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER;
        String bundledName = com.winlator.cmod.core.ProtonPackageManager.getPackage(com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                ? com.winlator.cmod.core.ProtonPackageManager.getPackage(com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER).title
                : com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER;
        composeController.setInstallBusy(bundledId, true);
        composeController.updateInstallProgress("Installing " + bundledName, -1);
        io.execute(() -> {
            boolean success;
            try {
                ImageFsInstaller.installWineFromAssets(null, requireContext());
                success = WineRuntimeGuard.isBundledMainInstalled(requireContext());
            } catch (Exception error) {
                success = false;
            }
            final boolean installed = success;
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                refreshBundledRuntimeState();
                syncComposeCatalog();
                if (!installed && isAdded()) Toast.makeText(requireContext(), "Unable to install " + bundledName + ".", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void requestRemoveBundledRuntime() {
        String using = WineRuntimeGuard.getContainerUsing(requireContext(), WineInfo.MAIN_WINE_VERSION.identifier());
        String bundledName = com.winlator.cmod.core.ProtonPackageManager.getPackage(com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                ? com.winlator.cmod.core.ProtonPackageManager.getPackage(com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER).title
                : com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER;
        if (using != null) {
            com.winlator.cmod.ui.ThemedAlertHost.info(
                    requireContext(),
                    "Proton is in use",
                    bundledName + " cannot be deleted because it is used by " + using + "."
            );
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                requireContext(),
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
        String bundledId = "bundled:" + com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER;
        String bundledName = com.winlator.cmod.core.ProtonPackageManager.getPackage(com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER) != null
                ? com.winlator.cmod.core.ProtonPackageManager.getPackage(com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER).title
                : com.winlator.cmod.core.ProtonPackageManager.DEFAULT_IDENTIFIER;
        composeController.setInstallBusy(bundledId, true);
        io.execute(() -> {
            boolean removed = WineRuntimeGuard.removeBundledMain(requireContext());
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                refreshBundledRuntimeState();
                if (!removed && isAdded()) Toast.makeText(requireContext(), bundledName + " could not be deleted.", Toast.LENGTH_LONG).show();
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
            item.installed = ProtonPackageManager.isInstalled(requireContext(), packageInfo.identifier);
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

    private String displayType(ContentProfile.ContentType type) {
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
        if (composeController == null || !isAdded()) return;
        ArrayList<com.winlator.cmod.ui.onboarding.OnboardingComponent> ui = new ArrayList<>();
        synchronized (catalog) {
            for (ComponentItem item : catalog) {
                String runtime = (item.type.equals("Wine") || item.type.equals("Proton")) && item.installed
                        ? item.entryName : null;
                boolean inUse = runtime != null && !runtime.isEmpty() && WineRuntimeGuard.isInUse(requireContext(), runtime);
                ui.add(new com.winlator.cmod.ui.onboarding.OnboardingComponent(
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
            ui.add(new com.winlator.cmod.ui.onboarding.OnboardingComponent(
                    "adrenotools:" + id, "AdrenoTools", label.trim(), true, false, true,
                    null, false, false
            ));
            existingDrivers.add(label.trim().toLowerCase(Locale.ENGLISH));
        }
        synchronized (remoteDrivers) {
            for (RemoteDriverCatalog.Entry driver : remoteDrivers) {
                if (existingDrivers.contains(driver.name.toLowerCase(Locale.ENGLISH))) continue;
                ui.add(new com.winlator.cmod.ui.onboarding.OnboardingComponent(
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
            File archive = new File(requireContext().getCacheDir(), "winz-component-" + System.nanoTime());
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
            File archive = new File(requireContext().getCacheDir(), packageInfo.identifier + "-" + System.nanoTime());
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
                    installed = ProtonPackageManager.installPackage(requireContext(), packageInfo.identifier, archive);
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

    private interface FailureCallback { void call(String error); }

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
        try (Cursor cursor = requireContext().getContentResolver().query(
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
        if (error != null && isAdded()) Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
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
                    requireContext(),
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
        if (!WineRuntimeGuard.canRemove(requireContext(), profile)) {
            String using = WineRuntimeGuard.getContainerUsing(requireContext(), ContentsManager.getEntryName(profile));
            com.winlator.cmod.ui.ThemedAlertHost.info(
                    requireContext(),
                    "Runtime is in use",
                    profile.verName + " cannot be deleted because it is used by " + using + "."
            );
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                requireContext(),
                "Delete component?",
                "The installed files will be removed from WinZ.",
                "Delete",
                () -> removeContent(profile, componentId),
                true
        );
    }

    private void requestRemoveProtonPackage(ComponentItem item, String componentId) {
        String identifier = item.packageInfo.identifier;
        String using = WineRuntimeGuard.getContainerUsing(requireContext(), identifier);
        if (using != null) {
            com.winlator.cmod.ui.ThemedAlertHost.info(
                    requireContext(),
                    "Runtime is in use",
                    item.name + " cannot be deleted because it is used by " + using + "."
            );
            return;
        }
        com.winlator.cmod.ui.ThemedAlertHost.confirm(
                requireContext(),
                "Delete component?",
                "The installed files will be removed from WinZ.",
                "Delete",
                () -> {
                    if (installBusy) return;
                    installBusy = true;
                    composeController.setInstallBusy(componentId, true);
                    io.execute(() -> {
                        ProtonPackageManager.deletePackage(requireContext(), identifier);
                        rebuildCatalog();
                        runOnUi(() -> {
                            finishInstall(componentId, null);
                            if (isAdded()) Toast.makeText(requireContext(), "Component removed", Toast.LENGTH_SHORT).show();
                        });
                    });
                },
                true
        );
    }

    private void removeContent(ContentProfile profile, String id) {
        if (installBusy || !WineRuntimeGuard.canRemove(requireContext(), profile)) return;
        installBusy = true;
        composeController.setInstallBusy(id, true);
        io.execute(() -> {
            contentsManager.removeContent(profile);
            contentsManager.syncContents();
            rebuildCatalog();
            runOnUi(() -> {
                finishInstall(id, null);
                if (isAdded()) Toast.makeText(requireContext(), "Component removed", Toast.LENGTH_SHORT).show();
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
                if (isAdded()) Toast.makeText(requireContext(), "Driver removed", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void loadRemoteDrivers() {
        io.execute(() -> {
            List<RemoteDriverCatalog.Entry> loaded = RemoteDriverCatalog.load(requireContext());
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
            String installed = RemoteDriverCatalog.install(requireContext(), driver.url);
            runOnUi(() -> {
                installBusy = false;
                if (composeController != null) composeController.setInstallBusy(null, false);
                syncComposeCatalog();
                if ((installed == null || installed.isEmpty()) && isAdded()) {
                    Toast.makeText(requireContext(), "Unable to install " + driver.name + ".", Toast.LENGTH_LONG).show();
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

    private void handleLocalComponentPicked(Uri uri) {
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

    private void handleLocalDriverPicked(Uri uri) {
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
                if ((installed == null || installed.isEmpty()) && isAdded()) {
                    Toast.makeText(requireContext(), "Unable to install the driver.", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

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
}
