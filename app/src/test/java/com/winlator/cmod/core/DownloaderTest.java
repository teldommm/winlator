package com.winlator.cmod.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs against a throw-away local HTTP server; no network access needed. */
public class DownloaderTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HttpServer server;
    private String base;
    private byte[] big;
    private byte[] part0;
    private byte[] part1;

    private interface Body {
        void write(HttpExchange exchange) throws IOException;
    }

    private void serve(String path, Body body) {
        server.createContext(path, exchange -> {
            try {
                body.write(exchange);
            } finally {
                exchange.close();
            }
        });
    }

    private static void reply(HttpExchange exchange, byte[] data, boolean announceLength) throws IOException {
        exchange.sendResponseHeaders(200, announceLength ? data.length : 0);
        exchange.getResponseBody().write(data);
    }

    @Before
    public void startServer() throws Exception {
        big = new byte[3_000_000];
        new Random(1).nextBytes(big);
        part0 = Arrays.copyOfRange(big, 0, 1_000_000);
        part1 = Arrays.copyOfRange(big, 1_000_000, big.length);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/big", e -> reply(e, big, true));
        serve("/p0", e -> reply(e, part0, true));
        serve("/p1", e -> reply(e, part1, true));
        serve("/chunked", e -> reply(e, big, false));
        serve("/text", e -> reply(e, "[1,2,3]".getBytes("UTF-8"), true));
        serve("/huge", e -> reply(e, new byte[8192 * 400], false));
        serve("/missing", e -> e.sendResponseHeaders(404, -1));
        serve("/limited", e -> e.sendResponseHeaders(403, -1));
        serve("/redirect", e -> {
            e.getResponseHeaders().add("Location", "/big");
            e.sendResponseHeaders(302, -1);
        });
        serve("/echo-ua", e -> {
            String ua = e.getRequestHeaders().getFirst("User-Agent");
            String custom = e.getRequestHeaders().getFirst("X-Test");
            reply(e, (ua + "|" + custom).getBytes("UTF-8"), true);
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    private interface Action {
        void run() throws Exception;
    }

    private static Downloader.DownloadException failure(Action action) throws Exception {
        try {
            action.run();
        } catch (Downloader.DownloadException e) {
            return e;
        }
        throw new AssertionError("expected a DownloadException");
    }

    @Test
    public void downloadsVerifiesAndReportsProgress() throws Exception {
        File out = new File(tmp.getRoot(), "a.bin");
        List<Integer> progress = new ArrayList<>();
        Downloader.downloadToFile(base + "/big", out, new Downloader.Options()
                .expectedSize(big.length).sha256(sha256(big).toUpperCase()).progress(progress::add));

        assertArrayEquals(big, Files.readAllBytes(out.toPath()));
        assertFalse(new File(out.getPath() + ".part").exists());
        assertEquals(Integer.valueOf(0), progress.get(0));
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) >= progress.get(i - 1));
    }

    @Test
    public void wrongHashIsRejectedAndOldFileKept() throws Exception {
        File out = tmp.newFile("b.bin");
        Files.write(out.toPath(), new byte[]{1});
        char[] zeros = new char[64];
        Arrays.fill(zeros, '0');

        Downloader.DownloadException e = failure(() ->
                Downloader.downloadToFile(base + "/big", out, new Downloader.Options().sha256(new String(zeros))));

        assertEquals(Downloader.Reason.HASH_MISMATCH, e.reason);
        assertEquals(1, Files.size(out.toPath()));
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    @Test
    public void wrongSizeIsRejected() throws Exception {
        File out = new File(tmp.getRoot(), "c.bin");
        Downloader.DownloadException e = failure(() ->
                Downloader.downloadToFile(base + "/big", out, new Downloader.Options().expectedSize(123)));
        assertEquals(Downloader.Reason.SIZE_MISMATCH, e.reason);
        assertFalse(out.exists());
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    @Test
    public void httpErrorsCarryTheStatus() throws Exception {
        Downloader.DownloadException notFound = failure(() ->
                Downloader.downloadToFile(base + "/missing", new File(tmp.getRoot(), "d.bin"), null));
        assertEquals(Downloader.Reason.HTTP, notFound.reason);
        assertEquals(404, notFound.httpCode);
        assertTrue(notFound.userMessage().contains("404"));

        Downloader.DownloadException limited = failure(() -> Downloader.fetchText(base + "/limited", 1000));
        assertEquals(Downloader.Reason.HTTP, limited.reason);
        assertEquals(403, limited.httpCode);
    }

    @Test
    public void badAddressesAreReportedNotThrownRaw() throws Exception {
        assertEquals(Downloader.Reason.INVALID_URL, failure(() -> Downloader.fetchText("not a url", 10)).reason);
        assertEquals(Downloader.Reason.INVALID_URL, failure(() -> Downloader.fetchText(null, 10)).reason);
        assertEquals(Downloader.Reason.NETWORK, failure(() -> Downloader.fetchText("http://127.0.0.1:1/x", 10)).reason);
    }

    @Test
    public void multiPartDownloadIsConcatenatedAndVerified() throws Exception {
        File out = new File(tmp.getRoot(), "e.bin");
        Downloader.downloadParts(Arrays.asList(
                new Downloader.Part(base + "/p0", part0.length),
                new Downloader.Part(base + "/p1", part1.length)), out, new Downloader.Options().sha256(sha256(big)));
        assertArrayEquals(big, Files.readAllBytes(out.toPath()));

        File bad = new File(tmp.getRoot(), "f.bin");
        Downloader.DownloadException e = failure(() -> Downloader.downloadParts(Arrays.asList(
                new Downloader.Part(base + "/p0", 999),
                new Downloader.Part(base + "/p1", part1.length)), bad, null));
        assertEquals(Downloader.Reason.SIZE_MISMATCH, e.reason);
        assertFalse(bad.exists());
    }

    @Test
    public void unknownLengthStillWorks() throws Exception {
        File out = new File(tmp.getRoot(), "g.bin");
        List<Integer> progress = new ArrayList<>();
        Downloader.downloadToFile(base + "/chunked", out, new Downloader.Options().progress(progress::add));
        assertEquals(big.length, Files.size(out.toPath()));
        assertTrue(progress.contains(-1));
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
    }

    @Test
    public void cancelLeavesNothingBehind() throws Exception {
        File out = new File(tmp.getRoot(), "h.bin");
        AtomicBoolean cancel = new AtomicBoolean(false);
        Downloader.DownloadException e = failure(() -> Downloader.downloadToFile(base + "/big", out,
                new Downloader.Options().cancel(cancel).progress(p -> {
                    if (p >= 10) cancel.set(true);
                })));
        assertEquals(Downloader.Reason.CANCELED, e.reason);
        assertFalse(out.exists());
        assertFalse(new File(out.getPath() + ".part").exists());
    }

    @Test
    public void textFetchHonoursTheSizeCap() throws Exception {
        assertEquals("[1,2,3]", Downloader.fetchText(base + "/text", 100));
        assertEquals(Downloader.Reason.TOO_LARGE, failure(() -> Downloader.fetchText(base + "/huge", 1000)).reason);
        assertEquals(Downloader.Reason.TOO_LARGE, failure(() -> Downloader.fetchText(base + "/big", 1000)).reason);
    }

    @Test
    public void fetchBytesSendsUserAgentAndCustomHeaders() throws Exception {
        byte[] data = Downloader.fetchBytes(base + "/echo-ua",
                Downloader.Fetch.limit(1000).header("X-Test", "yes").timeouts(2000, 2000, 5000));
        String answer = new String(data, "UTF-8");
        assertEquals(Downloader.USER_AGENT + "|yes", answer);
    }

    @Test
    public void redirectsAreFollowed() throws Exception {
        File out = new File(tmp.getRoot(), "r.bin");
        Downloader.downloadToFile(base + "/redirect", out, null);
        assertArrayEquals(big, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void unwritableDestinationIsAnIoError() throws Exception {
        Downloader.DownloadException e = failure(() ->
                Downloader.downloadToFile(base + "/text", new File("/proc/nonexistent/x.bin"), null));
        assertEquals(Downloader.Reason.IO, e.reason);
        assertNotNull(e.userMessage());
    }
}
