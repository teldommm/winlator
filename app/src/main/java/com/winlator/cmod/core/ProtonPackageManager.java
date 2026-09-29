package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.winlator.cmod.xenvironment.ImageFs;
import com.winlator.cmod.xenvironment.ImageFsInstaller;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public abstract class ProtonPackageManager {
    private static final String TAG = "ProtonPackageManager";
    public static final String DEFAULT_IDENTIFIER = "proton-9.0-arm64ec";
    private static final String RELEASE_BASE_URL = "https://github.com/Other-backup/winlator-imagefs/releases/download/protons-zst-latest/";
    private static final String RELEASE_NEW_BASE_URL = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/c/";
    private static final String RELEASE_D_BASE_URL = "https://github.com/Other-backup/winlator-imagefs-v2/releases/download/d/";

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

    private static final List<PackageInfo> PACKAGES = Arrays.asList(
            new PackageInfo("proton-9.0-arm64ec", "Proton 9 arm64ec", "proton-9.0-arm64ec.tar.zst",
                    new long[]{73570637L}, RELEASE_NEW_BASE_URL + "proton-9.0-arm64ec.tar.zst",
                    "5f9375640479a5b2c4c3998fda689bb5085febc8fcdb7b6998c79d925392991a"),
            new PackageInfo("proton-10.0-5-arm64ec", "Proton 10.0-5 arm64ec", "proton-wine-10.0-5-arm64ec.tar.zst",
                    new long[]{132067976L}, RELEASE_D_BASE_URL + "proton-wine-10.0-5-arm64ec.tar.zst",
                    "8cf0db4bb5e7e266e9e22e45e76722fb62cfbbf4e5e0e167a90c05c508e310ee"),
            new PackageInfo("proton-9.0-x86_64", "Proton 9 x86_64", "proton-9.0-x86_64.tar.zst",
                    new long[]{54043009L}, RELEASE_NEW_BASE_URL + "proton-9.0-x86_64.tar.zst",
                    "b4688130cd15818c2c46e66f2f7f7146d459a2d95bee1b3daf970f50dc4f8331"),
            new PackageInfo("proton-10.0-5-x86_64", "Proton 10.0-5 x86_64", "proton-10.0-5-x86_64.wcp",
                    new long[]{72882772L}, RELEASE_D_BASE_URL + "proton-10.0-5-x86_64.wcp",
                    "2a5759e48b5f856d36eedac3e5159054629260a792fcf9c167140e02c06d8f0c"),
            new PackageInfo("proton-11.0-1-arm64ec", "Proton 11.0-1 arm64ec", "proton-wine-11.0-1-arm64ec.wcp.xz",
                    new long[]{127896076L}, RELEASE_D_BASE_URL + "proton-wine-11.0-1-arm64ec.wcp.xz",
                    "a360f849f0ce3a808dacec854f25a641735a62ecc0eca8e09b7b7f7ff44041ff"),
            new PackageInfo("proton-10-arm64ec", "Proton 10 arm64ec (Legacy)", "proton-10-arm64ec.tar.zst",
                    new long[]{52428800L, 52428800L, 52428800L, 52428800L, 7195940L})
    );

    // Extra packages from the optional Proton manifest (Winlator Services). An entry with the same
    // identifier as a built-in package replaces it, anything else is added after the built-ins.
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
     * Loads the Proton manifest configured in Winlator Services. Blocking network call: run it off
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
        return identifiers;
    }

    /**
     * Downloads a package (all of its parts) into {@code output}, verifying part sizes, the total
     * size and, when the package has one, its SHA-256. The reason for a failure is in the exception.
     */
    public static void downloadPackageOrThrow(PackageInfo packageInfo, File output, Callback<Integer> progressCallback)
            throws Downloader.DownloadException {
        if (packageInfo == null || output == null || packageInfo.partSizes == null || packageInfo.partSizes.length == 0) {
            throw new Downloader.DownloadException(Downloader.Reason.INVALID_URL, "Nothing to download");
        }
        ArrayList<Downloader.Part> parts = new ArrayList<>();
        for (int i = 0; i < packageInfo.partSizes.length; i++) {
            String address = packageInfo.directUrl != null
                    ? packageInfo.directUrl
                    : RELEASE_BASE_URL + packageInfo.fileName + "." + String.format(Locale.ROOT, "%02d", i);
            parts.add(new Downloader.Part(address, packageInfo.partSizes[i]));
        }
        Downloader.Options options = new Downloader.Options().sha256(packageInfo.sha256);
        if (progressCallback != null) {
            options.progress(percent -> {
                if (percent >= 0) progressCallback.call(percent);
            });
        }
        Downloader.downloadParts(parts, output, options);
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
