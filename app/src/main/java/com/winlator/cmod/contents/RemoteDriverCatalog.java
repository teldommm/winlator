package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.contentdialog.DriverRepo;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.RemoteSources;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RemoteDriverCatalog {
    private RemoteDriverCatalog() {}

    private static final int LISTING_MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DRIVERS_PER_REPO = 40;

    // The repository list (defaults and the user's own) lives in RemoteSources and is edited
    // from Winlator servers.
    private static List<DriverRepo> configuredRepos(Context context) {
        return RemoteSources.driverRepos(context);
    }

    public static final class Entry {
        public final String repository;
        public final String name;
        public final String url;
        /** SHA-256 (hex) from the release asset's digest, null when GitHub did not provide one. */
        public final String sha256;

        Entry(String repository, String name, String url, String sha256) {
            this.repository = repository;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
        }
    }

    /**
     * Lists the drivers of every configured repository. A repository that cannot be read does not
     * stop the others; one human-readable line per failure is added to {@code errors}.
     */
    public static List<Entry> load(Context context, List<String> errors) {
        ArrayList<Entry> result = new ArrayList<>();
        for (DriverRepo repo : configuredRepos(context)) {
            if (repo.apiUrl == null || repo.apiUrl.isEmpty()) continue;
            String text;
            try {
                text = Downloader.fetchText(repo.apiUrl, LISTING_MAX_BYTES);
            } catch (Downloader.DownloadException e) {
                errors.add(repo.name + ": " + e.userMessage());
                continue;
            }
            try {
                parseReleases(repo, new JSONArray(text), result);
            } catch (JSONException e) {
                errors.add(repo.name + ": " + apiMessage(text));
            }
        }
        return result;
    }

    // GitHub answers errors (rate limit, unknown repository) with an object that has a "message".
    static String apiMessage(String text) {
        try {
            String message = new JSONObject(text).optString("message", "").trim();
            if (!message.isEmpty()) return message.length() > 80 ? message.substring(0, 80) : message;
        } catch (JSONException ignored) {
        }
        return "unexpected response from the server";
    }

    static void parseReleases(DriverRepo repo, JSONArray releases, List<Entry> out) {
        int accepted = 0;
        for (int i = 0; i < releases.length() && accepted < MAX_DRIVERS_PER_REPO; i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            String releaseName = release.optString("name", release.optString("tag_name", "")).trim();
            ArrayList<JSONObject> zipAssets = new ArrayList<>();
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null) continue;
                String url = asset.optString("browser_download_url", "");
                String assetName = asset.optString("name", "");
                if (!url.isEmpty() && assetName.toLowerCase(Locale.ENGLISH).endsWith(".zip")) {
                    zipAssets.add(asset);
                }
            }

            for (JSONObject asset : zipAssets) {
                if (accepted >= MAX_DRIVERS_PER_REPO) break;
                String url = asset.optString("browser_download_url", "");
                String assetName = asset.optString("name", "");
                String assetLabel = assetName.replaceFirst("(?i)\\.zip$", "").trim();

                String name;
                if (zipAssets.size() > 1) {
                    name = assetLabel.isEmpty() ? releaseName : assetLabel;
                } else {
                    name = releaseName.isEmpty() ? assetLabel : releaseName;
                }
                if (name.isEmpty()) continue;

                out.add(new Entry(repo.name, name, url, digestOf(asset)));
                accepted++;
            }
        }
    }

    // Release assets carry "digest": "sha256:<hex>" on current GitHub API versions.
    static String digestOf(JSONObject asset) {
        String digest = asset.optString("digest", "").trim().toLowerCase(Locale.ROOT);
        return digest.startsWith("sha256:") && digest.length() == 7 + 64 ? digest.substring(7) : null;
    }

    /** Downloads and installs a driver archive; the returned id is empty when it is not a valid driver. */
    public static String installOrThrow(Context context, String url, String sha256) throws Downloader.DownloadException {
        return installOrThrow(context, url, sha256, null);
    }

    public static String installOrThrow(Context context, String url, String sha256, Downloader.Progress progress)
            throws Downloader.DownloadException {
        File archive = new File(context.getCacheDir(), "winlite-driver-" + System.nanoTime() + ".zip");
        try {
            Downloader.downloadToFile(url, archive, new Downloader.Options().sha256(sha256).progress(progress));
            String installed = new AdrenotoolsManager(context).installDriver(Uri.fromFile(archive));
            return installed == null ? "" : installed;
        } finally {
            archive.delete();
        }
    }
}
