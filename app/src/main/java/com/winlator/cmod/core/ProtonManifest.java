package com.winlator.cmod.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reads the optional Proton manifest from Winlator servers.
 *
 * <pre>
 * [ {"identifier": "proton-11.0-2-arm64ec", "title": "Proton 11.0-2 arm64ec",
 *    "url": "https://.../file.wcp.xz", "size": 127896076, "sha256": "&lt;hex&gt;"} ]
 * </pre>
 * (or {@code {"packages": [...]}}). No Android dependencies, so it is unit-testable on a plain JVM.
 */
public final class ProtonManifest {
    private ProtonManifest() {}

    // The identifier becomes a directory name under opt/, so a manifest must not be able to smuggle
    // in path separators or "..".
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /**
     * Entries with an unsafe identifier or a non-https url are skipped; size and sha256 are optional
     * (the download is only verified against the ones that are present). Returns null when the text
     * is not a manifest at all, so callers can keep the previous list.
     */
    public static List<ProtonPackageManager.PackageInfo> parse(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            String text = json.trim();
            JSONArray array = text.startsWith("{") ? new JSONObject(text).getJSONArray("packages") : new JSONArray(text);
            ArrayList<ProtonPackageManager.PackageInfo> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String identifier = item.optString("identifier", "").trim();
                String url = item.optString("url", "").trim();
                if (!SAFE_IDENTIFIER.matcher(identifier).matches() || !RemoteSources.isHttps(url)) continue;
                String title = item.optString("title", "").trim();
                if (title.isEmpty()) title = identifier;
                long size = Math.max(0L, item.optLong("size", 0L));
                String sha256 = item.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
                if (!SHA256_HEX.matcher(sha256).matches()) sha256 = null;
                String fileName = lastPathSegment(url);
                if (fileName.isEmpty()) fileName = identifier;
                result.add(new ProtonPackageManager.PackageInfo(identifier, title, fileName, new long[]{size}, url, sha256));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    // The part of the path after the last '/', without query or fragment.
    static String lastPathSegment(String url) {
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
