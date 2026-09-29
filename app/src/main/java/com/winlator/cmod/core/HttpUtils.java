package com.winlator.cmod.core;

import java.util.concurrent.Executors;

/**
 * Small text downloads for the UI (index files, profile lists). Kept as a thin wrapper so callers
 * don't change; all the HTTP work lives in {@link Downloader}.
 */
public abstract class HttpUtils {
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;

    /** Calls back on a worker thread with the text, or null when the download failed. */
    public static void download(final String url, final Callback<String> onDownloadComplete) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String text;
            try {
                text = Downloader.fetchText(url, MAX_TEXT_BYTES);
            } catch (Downloader.DownloadException e) {
                text = null;
            }
            onDownloadComplete.call(text);
        });
    }
}
