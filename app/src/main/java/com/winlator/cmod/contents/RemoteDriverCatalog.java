package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;

import com.winlator.cmod.contentdialog.DriverRepo;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.RemoteSources;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class RemoteDriverCatalog {
    private RemoteDriverCatalog() {}

    private static final int LISTING_MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DRIVERS_PER_REPO = 150;
    // GitHub's own maximum for one page of releases (its default is 30).
    private static final int PER_PAGE = 100;
    private static final int MAX_PAGES = 3;
    // A saved listing younger than this is used as is, without asking the server. The screens that
    // list drivers refresh on every open, and GitHub only allows 60 unauthenticated requests an hour.
    private static final long FRESH_MS = 2 * 60 * 1000L;

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
        /** When the release was published (epoch millis), 0 when the server did not say. */
        public final long publishedAt;

        Entry(String repository, String name, String url, String sha256, long publishedAt) {
            this.repository = repository;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.publishedAt = publishedAt;
        }
    }

    /** The drivers of one repository, and a note when they are not quite fresh. */
    public static final class RepoResult {
        public final List<Entry> entries;
        /** Null when the list is current; otherwise why the saved copy is shown instead. */
        public final String warning;

        RepoResult(List<Entry> entries, String warning) {
            this.entries = entries;
            this.warning = warning;
        }
    }

    /**
     * Lists the drivers of every configured repository. A repository that cannot be read does not
     * stop the others; one human-readable line per failure is added to {@code errors}.
     * (The driver source reads repository by repository with {@link #loadRepo} so it can keep
     * what it already has for a repository that fails; this is the all-in-one form.)
     */
    public static List<Entry> load(Context context, List<String> errors) {
        ArrayList<Entry> result = new ArrayList<>();
        for (DriverRepo repo : configuredRepos(context)) {
            if (repo.apiUrl == null || repo.apiUrl.trim().isEmpty()) continue;
            try {
                RepoResult repoResult = loadRepo(context, repo);
                result.addAll(repoResult.entries);
                if (repoResult.warning != null) errors.add(repo.name + ": " + repoResult.warning);
            } catch (Downloader.DownloadException e) {
                errors.add(repo.name + ": " + e.userMessage());
            }
        }
        return result;
    }

    /**
     * Reads the releases of one repository: up to {@link #MAX_PAGES} pages of {@link #PER_PAGE}
     * releases. Every page is saved with its ETag and asked for again conditionally (a 304 does
     * not count against GitHub's rate limit); when the server cannot be reached the saved copy is
     * used and {@link RepoResult#warning} says so. Throws only when there is nothing to show.
     */
    public static RepoResult loadRepo(Context context, DriverRepo repo) throws Downloader.DownloadException {
        HttpUrl base = repo.apiUrl == null ? null : HttpUrl.parse(repo.apiUrl.trim());
        if (base == null) throw new Downloader.DownloadException(Downloader.Reason.INVALID_URL, "Invalid address");

        File dir = new File(context.getCacheDir(), "driver-repos");
        ArrayList<Entry> out = new ArrayList<>();
        String warning = null;
        for (int page = 1; page <= MAX_PAGES; page++) {
            Page loaded;
            try {
                loaded = fetchPage(dir, pageUrl(base, page));
            } catch (Downloader.DownloadException e) {
                if (page == 1) throw e;
                // The first page is in; a later one failing only means the list is shorter.
                if (warning == null) warning = e.userMessage() + " (older releases are missing)";
                break;
            }
            if (loaded.warning != null && warning == null) warning = loaded.warning;
            parseReleases(repo, loaded.releases, out, MAX_DRIVERS_PER_REPO);
            if (out.size() >= MAX_DRIVERS_PER_REPO || loaded.releases.length() < PER_PAGE) break;
        }
        return new RepoResult(dedupeByUrl(out), warning);
    }

    private static HttpUrl pageUrl(HttpUrl base, int page) {
        HttpUrl.Builder builder = base.newBuilder();
        if (base.queryParameter("per_page") == null) builder.setQueryParameter("per_page", String.valueOf(PER_PAGE));
        if (page > 1) builder.setQueryParameter("page", String.valueOf(page));
        return builder.build();
    }

    // A server that ignores "page" would send the same releases again.
    private static List<Entry> dedupeByUrl(List<Entry> in) {
        ArrayList<Entry> out = new ArrayList<>(in.size());
        Set<String> seen = new HashSet<>();
        for (Entry entry : in) if (seen.add(entry.url)) out.add(entry);
        return out;
    }

    private static final class Page {
        final JSONArray releases;
        final String warning;

        Page(JSONArray releases, String warning) {
            this.releases = releases;
            this.warning = warning;
        }
    }

    private static Page fetchPage(File dir, HttpUrl url) throws Downloader.DownloadException {
        String key = sha256Hex(url.toString());
        File bodyFile = new File(dir, key + ".json");
        File etagFile = new File(dir, key + ".etag");

        JSONArray cached = readCachedArray(bodyFile);
        long age = System.currentTimeMillis() - bodyFile.lastModified();
        if (cached != null && age >= 0 && age < FRESH_MS) return new Page(cached, null);

        Request.Builder request = new Request.Builder()
                .url(url)
                .header("User-Agent", Downloader.USER_AGENT)
                .header("Accept", "application/vnd.github+json");
        String etag = cached != null ? readText(etagFile) : null;
        if (etag != null && !etag.isEmpty()) request.header("If-None-Match", etag);

        Downloader.DownloadException failure;
        try (Response response = Downloader.client().newCall(request.build()).execute()) {
            int code = response.code();
            if (code == 304 && cached != null) {
                bodyFile.setLastModified(System.currentTimeMillis());
                return new Page(cached, null);
            }
            if (!response.isSuccessful()) throw httpError(code);
            ResponseBody body = response.body();
            if (body == null) throw new Downloader.DownloadException(Downloader.Reason.BAD_RESPONSE, "The server sent an empty response");
            if (body.contentLength() > LISTING_MAX_BYTES) throw tooLarge();
            String text = new String(readLimited(body.byteStream()), StandardCharsets.UTF_8);
            JSONArray releases;
            try {
                releases = new JSONArray(text);
            } catch (JSONException e) {
                throw new Downloader.DownloadException(Downloader.Reason.BAD_RESPONSE, apiMessage(text));
            }
            writeCache(dir, bodyFile, etagFile, text, response.header("ETag"));
            return new Page(releases, null);
        } catch (Downloader.DownloadException e) {
            failure = e;
        } catch (IOException e) {
            String detail = e.getMessage();
            failure = new Downloader.DownloadException(Downloader.Reason.NETWORK,
                    "Network error" + (detail == null || detail.isEmpty() ? ""
                            : " (" + (detail.length() > 80 ? detail.substring(0, 80) : detail) + ")"), 0, e);
        }
        // Rate limit, no connection, a broken answer: the copy saved earlier is better than nothing.
        if (cached != null) return new Page(cached, failure.userMessage() + " (showing the saved list)");
        throw failure;
    }

    private static Downloader.DownloadException httpError(int code) {
        String hint = code == 404 ? " (not found)" : code == 403 || code == 429 ? " (access denied or rate limited)" : "";
        return new Downloader.DownloadException(Downloader.Reason.HTTP, "Server returned HTTP " + code + hint, code, null);
    }

    private static Downloader.DownloadException tooLarge() {
        return new Downloader.DownloadException(Downloader.Reason.TOO_LARGE, "The response is too large");
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > LISTING_MAX_BYTES) throw tooLarge();
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static JSONArray readCachedArray(File file) {
        String text = readText(file);
        if (text == null) return null;
        try {
            return new JSONArray(text);
        } catch (JSONException e) {
            file.delete();
            return null;
        }
    }

    private static String readText(File file) {
        if (!file.isFile()) return null;
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    // Written to a temporary file first so a half-written listing is never read back.
    private static void writeCache(File dir, File bodyFile, File etagFile, String text, String etag) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, bodyFile.getName() + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), bodyFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (etag != null && !etag.trim().isEmpty()) {
                Files.write(etagFile.toPath(), etag.trim().getBytes(StandardCharsets.UTF_8));
            } else {
                etagFile.delete();
            }
        } catch (IOException ignored) {
            // No cache is not an error: the list just is not saved.
        }
    }

    private static String sha256Hex(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
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

    static void parseReleases(DriverRepo repo, JSONArray releases, List<Entry> out, int max) {
        for (int i = 0; i < releases.length() && out.size() < max; i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;

            String releaseName = release.optString("name", release.optString("tag_name", "")).trim();
            long publishedAt = parseTime(release.optString("published_at", release.optString("created_at", "")));
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
                if (out.size() >= max) break;
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

                out.add(new Entry(repo.name, name, url, digestOf(asset), publishedAt));
            }
        }
    }

    // GitHub timestamps look like 2025-03-14T09:26:53Z. 0 when missing or unreadable.
    private static long parseTime(String text) {
        if (text == null || text.isEmpty()) return 0L;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(text).getTime();
        } catch (ParseException e) {
            return 0L;
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
