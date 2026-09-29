package com.winlator.cmod.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The one place the app downloads things: a shared HTTP client, streaming to a temp file with the
 * SHA-256 computed on the fly, size / hash verification, progress, cancellation and errors that
 * say what went wrong (so callers can show "HTTP 404" instead of an empty list).
 *
 * No Android dependencies, so it can be unit-tested on a plain JVM.
 */
public final class Downloader {
    private Downloader() {}

    public enum Reason {
        INVALID_URL,
        NETWORK,
        HTTP,
        BAD_RESPONSE,
        TOO_LARGE,
        SIZE_MISMATCH,
        HASH_MISMATCH,
        CANCELED,
        IO
    }

    public static final class DownloadException extends IOException {
        public final Reason reason;
        /** HTTP status for {@link Reason#HTTP}, otherwise 0. */
        public final int httpCode;

        public DownloadException(Reason reason, String message, int httpCode, Throwable cause) {
            super(message, cause);
            this.reason = reason;
            this.httpCode = httpCode;
        }

        public DownloadException(Reason reason, String message) {
            this(reason, message, 0, null);
        }

        /** Short text meant for a toast / status line. */
        public String userMessage() {
            return getMessage();
        }
    }

    public interface Progress {
        /** 0..100, or -1 when the total size is unknown. Called from the downloading thread. */
        void onProgress(int percent);
    }

    /** One piece of a download that is stored as several files (legacy split archives). */
    public static final class Part {
        public final String url;
        /** Expected size in bytes, 0 when unknown. */
        public final long size;

        public Part(String url, long size) {
            this.url = url;
            this.size = size;
        }
    }

    public static final class Options {
        long expectedSize;
        String sha256;
        Progress progress;
        AtomicBoolean cancel;

        /** Total size of the finished file; 0 = don't check. */
        public Options expectedSize(long size) {
            this.expectedSize = Math.max(0L, size);
            return this;
        }

        /** Expected SHA-256 (hex, any case); null or blank = don't check. */
        public Options sha256(String hex) {
            this.sha256 = hex == null || hex.trim().isEmpty() ? null : hex.trim().toLowerCase(Locale.ROOT);
            return this;
        }

        public Options progress(Progress progress) {
            this.progress = progress;
            return this;
        }

        public Options cancel(AtomicBoolean cancel) {
            this.cancel = cancel;
            return this;
        }
    }

    private static final int BUFFER_SIZE = 64 * 1024;

    // Long read timeout for slow mirrors, no overall call timeout so big archives can finish.
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    /** Sent by every request made through this class. */
    public static final String USER_AGENT = "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 WinLite/1.0";

    /**
     * The shared client. Code that needs its own timeouts (or Retrofit) should derive from it with
     * {@code client().newBuilder()} so connections and threads are still shared.
     */
    public static OkHttpClient client() {
        return CLIENT;
    }

    // ------------------------------------------------------------------ small in-memory fetches

    /**
     * Settings for {@link #fetchText(String, Fetch)} / {@link #fetchBytes(String, Fetch)}: a size
     * cap (required, so a wrong address can't fill memory), optional headers and timeouts.
     */
    public static final class Fetch {
        final int maxBytes;
        final Map<String, String> headers = new LinkedHashMap<>();
        int connectMs;
        int readMs;
        int callMs;

        private Fetch(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        public static Fetch limit(int maxBytes) {
            return new Fetch(maxBytes);
        }

        public Fetch header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /** 0 keeps the shared client's value for that timeout. */
        public Fetch timeouts(int connectMs, int readMs, int callMs) {
            this.connectMs = Math.max(0, connectMs);
            this.readMs = Math.max(0, readMs);
            this.callMs = Math.max(0, callMs);
            return this;
        }
    }

    // ------------------------------------------------------------------ text

    /** Downloads a small text resource (JSON listings, index files). */
    public static String fetchText(String url, int maxBytes) throws DownloadException {
        return fetchText(url, Fetch.limit(maxBytes));
    }

    public static String fetchText(String url, Fetch fetch) throws DownloadException {
        return new String(fetchBytes(url, fetch), StandardCharsets.UTF_8);
    }

    /** Downloads a small resource into memory (images, listings). Throws TOO_LARGE past the cap. */
    public static byte[] fetchBytes(String url, Fetch fetch) throws DownloadException {
        Request request = buildRequest(url, fetch.headers);
        OkHttpClient http = CLIENT;
        if (fetch.connectMs > 0 || fetch.readMs > 0 || fetch.callMs > 0) {
            OkHttpClient.Builder builder = CLIENT.newBuilder();
            if (fetch.connectMs > 0) builder.connectTimeout(fetch.connectMs, TimeUnit.MILLISECONDS);
            if (fetch.readMs > 0) builder.readTimeout(fetch.readMs, TimeUnit.MILLISECONDS);
            if (fetch.callMs > 0) builder.callTimeout(fetch.callMs, TimeUnit.MILLISECONDS);
            http = builder.build();
        }
        try (Response response = http.newCall(request).execute()) {
            checkStatus(response);
            ResponseBody body = response.body();
            if (body == null) throw new DownloadException(Reason.BAD_RESPONSE, "The server sent an empty response");
            long declared = body.contentLength();
            if (declared > fetch.maxBytes) throw new DownloadException(Reason.TOO_LARGE, "The response is too large");
            return readLimited(body.byteStream(), fetch.maxBytes);
        } catch (DownloadException e) {
            throw e;
        } catch (IOException e) {
            throw network(e);
        }
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > maxBytes) throw new DownloadException(Reason.TOO_LARGE, "The response is too large");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ files

    public static void downloadToFile(String url, File dest, Options options) throws DownloadException {
        long size = options == null ? 0L : options.expectedSize;
        downloadParts(Collections.singletonList(new Part(url, size)), dest, options);
    }

    /**
     * Downloads the parts one after another into a single file. The file appears at {@code dest}
     * only after every check passed; on any failure nothing is left behind.
     */
    public static void downloadParts(List<Part> parts, File dest, Options options) throws DownloadException {
        if (parts == null || parts.isEmpty()) throw new DownloadException(Reason.INVALID_URL, "Nothing to download");
        Options opts = options != null ? options : new Options();

        long total = opts.expectedSize;
        if (total <= 0) {
            long sum = 0;
            boolean known = true;
            for (Part part : parts) {
                if (part.size <= 0) known = false;
                else sum += part.size;
            }
            total = known ? sum : 0;
        }

        MessageDigest digest = null;
        if (opts.sha256 != null) {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new DownloadException(Reason.IO, "SHA-256 is not available on this device", 0, e);
            }
        }

        File tmp = new File(dest.getPath() + ".part");
        File parent = tmp.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new DownloadException(Reason.IO, "Unable to write to storage");
        }

        boolean ok = false;
        try {
            long written = 0;
            int lastPercent = -2;
            report(opts.progress, total > 0 ? 0 : -1);
            OutputStream fileOut;
            try {
                fileOut = new FileOutputStream(tmp);
            } catch (IOException e) {
                throw new DownloadException(Reason.IO, "Unable to write to storage", 0, e);
            }
            try (OutputStream out = fileOut) {
                for (Part part : parts) {
                    Request request = buildRequest(part.url);
                    long partWritten = 0;
                    try (Response response = CLIENT.newCall(request).execute()) {
                        checkStatus(response);
                        ResponseBody body = response.body();
                        if (body == null) throw new DownloadException(Reason.BAD_RESPONSE, "The server sent an empty response");
                        long knownTotal = total > 0 ? total : 0;
                        long partDeclared = body.contentLength();
                        if (knownTotal == 0 && parts.size() == 1 && partDeclared > 0) knownTotal = partDeclared;

                        byte[] buffer = new byte[BUFFER_SIZE];
                        try (InputStream in = body.byteStream()) {
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                if (opts.cancel != null && opts.cancel.get()) {
                                    throw new DownloadException(Reason.CANCELED, "Canceled");
                                }
                                try {
                                    out.write(buffer, 0, read);
                                } catch (IOException e) {
                                    throw new DownloadException(Reason.IO, "Unable to write to storage", 0, e);
                                }
                                if (digest != null) digest.update(buffer, 0, read);
                                written += read;
                                partWritten += read;
                                if (opts.progress != null) {
                                    int percent = knownTotal > 0 ? (int) Math.min(100, written * 100 / knownTotal) : -1;
                                    if (percent != lastPercent) {
                                        lastPercent = percent;
                                        opts.progress.onProgress(percent);
                                    }
                                }
                            }
                        }
                    }
                    if (part.size > 0 && partWritten != part.size) {
                        throw new DownloadException(Reason.SIZE_MISMATCH,
                                "The downloaded file has the wrong size (expected " + part.size + " bytes, got " + partWritten + ")");
                    }
                }
            } catch (DownloadException e) {
                throw e;
            } catch (IOException e) {
                throw network(e);
            }

            if (opts.expectedSize > 0 && written != opts.expectedSize) {
                throw new DownloadException(Reason.SIZE_MISMATCH,
                        "The downloaded file has the wrong size (expected " + opts.expectedSize + " bytes, got " + written + ")");
            }
            if (digest != null && !toHex(digest.digest()).equals(opts.sha256)) {
                throw new DownloadException(Reason.HASH_MISMATCH, "The file failed the SHA-256 integrity check");
            }

            if (dest.exists() && !dest.delete()) throw new DownloadException(Reason.IO, "Unable to replace the existing file");
            if (!tmp.renameTo(dest)) throw new DownloadException(Reason.IO, "Unable to save the downloaded file");
            ok = true;
            report(opts.progress, 100);
        } finally {
            if (!ok) tmp.delete();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void report(Progress progress, int percent) {
        if (progress != null) progress.onProgress(percent);
    }

    private static Request buildRequest(String url) throws DownloadException {
        return buildRequest(url, Collections.<String, String>emptyMap());
    }

    private static Request buildRequest(String url, Map<String, String> headers) throws DownloadException {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url.trim());
        if (parsed == null) throw new DownloadException(Reason.INVALID_URL, "Invalid address");
        Request.Builder builder = new Request.Builder().url(parsed).header("User-Agent", USER_AGENT);
        for (Map.Entry<String, String> header : headers.entrySet()) builder.header(header.getKey(), header.getValue());
        return builder.build();
    }

    private static void checkStatus(Response response) throws DownloadException {
        if (response.isSuccessful()) return;
        int code = response.code();
        String hint = code == 404 ? " (not found)" : code == 403 || code == 429 ? " (access denied or rate limited)" : "";
        throw new DownloadException(Reason.HTTP, "Server returned HTTP " + code + hint, code, null);
    }

    private static DownloadException network(IOException e) {
        String detail = e.getMessage();
        String text = "Network error";
        if (detail != null && !detail.isEmpty()) text += " (" + (detail.length() > 80 ? detail.substring(0, 80) : detail) + ")";
        return new DownloadException(Reason.NETWORK, text, 0, e);
    }

    private static String toHex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte value : hash) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return hex.toString();
    }
}
