package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public abstract class ProtonPackageManager {
    public static final String DEFAULT_IDENTIFIER = "proton-9.0-arm64ec";
    // Where the bundled runtime comes from at build time (see downloadProton in app/build.gradle).
    private static final String BUNDLED_URL = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/c/proton-9.0-arm64ec.tar.zst";

    public static class PackageInfo {
        public final String identifier;
        public final String title;
        public final String fileName;
        public final long[] partSizes;
        public final String directUrl;
        public final String sha256;

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes) {
            this(identifier, title, fileName, partSizes, null, null);
        }

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes, String directUrl) {
            this(identifier, title, fileName, partSizes, directUrl, null);
        }

        public PackageInfo(String identifier, String title, String fileName, long[] partSizes,
                           String directUrl, String sha256) {
            this.identifier = identifier;
            this.title = title;
            this.fileName = fileName;
            this.partSizes = partSizes;
            this.directUrl = directUrl;
            this.sha256 = sha256;
        }
    }

    // The only package the app itself knows about: the Proton that ships inside the APK. Every other
    // Proton (custom builds) comes from the component catalog (contents.json) or from the Proton
    // manifest set in Winlator servers - nothing else is hard-coded here.
    private static final List<PackageInfo> PACKAGES = Arrays.asList(
            new PackageInfo(DEFAULT_IDENTIFIER, "Proton 9 arm64ec", "proton-9.0-arm64ec.tar.zst",
                    new long[]{73570637L}, BUNDLED_URL,
                    "5f9375640479a5b2c4c3998fda689bb5085febc8fcdb7b6998c79d925392991a")
    );

    // Extra packages from the optional Proton manifest (Winlator servers). An entry with the same
    // identifier as the bundled package replaces it, anything else is added after it.
    private static volatile List<PackageInfo> remotePackages = new ArrayList<>();
    private static final String KEY_MANIFEST_CACHE_URL = "svc_proton_manifest_cache_url";
    private static final String KEY_MANIFEST_CACHE_BODY = "svc_proton_manifest_cache_body";
    private static final int MANIFEST_MAX_BYTES = 1024 * 1024;

    public static List<PackageInfo> getPackages() {
        ArrayList<PackageInfo> merged = new ArrayList<>(PACKAGES);
        for (PackageInfo remote : remotePackages) {
            int index = indexOfIdentifier(merged, remote.identifier);
            if (index >= 0) merged.set(index, remote);
            else merged.add(remote);
        }
        return merged;
    }

    public static PackageInfo getPackage(String identifier) {
        for (PackageInfo packageInfo : getPackages())
            if (packageInfo.identifier.equals(identifier)) return packageInfo;
        return null;
    }

    private static int indexOfIdentifier(List<PackageInfo> list, String identifier) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).identifier.equals(identifier)) return i;
        return -1;
    }

    /**
     * Loads the Proton manifest configured in Winlator servers. Blocking network call: run it off
     * the main thread. The last manifest that downloaded and parsed cleanly is kept, so a failed
     * fetch (offline, host down) does not make the extra packages disappear - the failure is still
     * thrown so the caller can tell the user why the list did not update.
     */
    public static void refreshRemote(Context context) throws Downloader.DownloadException {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        String manifestUrl = RemoteSources.protonManifestUrl(app);
        if (manifestUrl.isEmpty()) {
            remotePackages = new ArrayList<>();
            prefs.edit().remove(KEY_MANIFEST_CACHE_URL).remove(KEY_MANIFEST_CACHE_BODY).apply();
            return;
        }

        if (manifestUrl.equals(prefs.getString(KEY_MANIFEST_CACHE_URL, null))) {
            List<PackageInfo> cached = ProtonManifest.parse(prefs.getString(KEY_MANIFEST_CACHE_BODY, ""));
            remotePackages = cached != null ? cached : new ArrayList<>();
        } else {
            remotePackages = new ArrayList<>();
        }

        if (!RemoteSources.isHttps(manifestUrl)) {
            throw new Downloader.DownloadException(Downloader.Reason.INVALID_URL,
                    "The Proton manifest address must start with https://");
        }
        String text = Downloader.fetchText(manifestUrl, MANIFEST_MAX_BYTES);
        List<PackageInfo> parsed = ProtonManifest.parse(text);
        if (parsed == null) {
            throw new Downloader.DownloadException(Downloader.Reason.BAD_RESPONSE, "The Proton manifest is not valid");
        }
        remotePackages = parsed;
        prefs.edit().putString(KEY_MANIFEST_CACHE_URL, manifestUrl).putString(KEY_MANIFEST_CACHE_BODY, text).apply();
    }

    public static boolean isKnownPackage(String identifier) {
        return getPackage(identifier) != null;
    }

    public static File getInstallDir(Context context, String identifier) {
        return new File(ImageFs.find(context).getRootDir(), "opt/" + identifier);
    }

    public static boolean isInstalled(Context context, String identifier) {
        File installDir = getInstallDir(context, identifier);
        File[] files = installDir.listFiles();
        return installDir.isDirectory() && files != null && files.length > 0;
    }

    public static List<String> getInstalledIdentifiers(Context context) {
        ArrayList<String> identifiers = new ArrayList<>();
        for (PackageInfo packageInfo : getPackages())
            if (isInstalled(context, packageInfo.identifier)) identifiers.add(packageInfo.identifier);
        identifiers.addAll(getInstalledUnlisted(context));
        return identifiers;
    }

    /**
     * Proton runtimes that are installed (a non-empty {@code opt/proton-*} folder) but that no list
     * mentions: installed from a package that used to be built into the app, or from a manifest that
     * has since changed. They stay usable and removable instead of silently disappearing.
     */
    public static List<String> getInstalledUnlisted(Context context) {
        ArrayList<String> found = new ArrayList<>();
        File[] folders = new File(ImageFs.find(context).getRootDir(), "opt").listFiles();
        if (folders == null) return found;
        for (File folder : folders) {
            String name = folder.getName();
            if (!folder.isDirectory() || !name.startsWith("proton-") || isKnownPackage(name)) continue;
            File[] files = folder.listFiles();
            if (files != null && files.length > 0) found.add(name);
        }
        Collections.sort(found);
        return found;
    }

    /**
     * Downloads a package into {@code output}, verifying its size (when known) and, when the
     * package has one, its SHA-256. The reason for a failure is in the exception.
     */
    public static void downloadPackageOrThrow(PackageInfo packageInfo, File output, Callback<Integer> progressCallback)
            throws Downloader.DownloadException {
        if (packageInfo == null || output == null || packageInfo.directUrl == null) {
            throw new Downloader.DownloadException(Downloader.Reason.INVALID_URL, "This package has no download address");
        }
        long size = packageInfo.partSizes != null && packageInfo.partSizes.length > 0 ? packageInfo.partSizes[0] : 0L;
        Downloader.Options options = new Downloader.Options().expectedSize(size).sha256(packageInfo.sha256);
        if (progressCallback != null) {
            options.progress(percent -> {
                if (percent >= 0) progressCallback.call(percent);
            });
        }
        Downloader.downloadToFile(packageInfo.directUrl, output, options);
    }

    public static boolean installPackage(Context context, String identifier, File archiveFile) {
        boolean installed = ImageFsInstaller.installWineArchive(context, identifier, archiveFile);
        if (!installed || !isInstalled(context, identifier)) {
            deletePackage(context, identifier);
            return false;
        }
        return true;
    }

    public static void deletePackage(Context context, String identifier) {
        FileUtils.delete(getInstallDir(context, identifier));
    }
}
