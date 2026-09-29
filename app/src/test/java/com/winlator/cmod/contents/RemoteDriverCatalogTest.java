package com.winlator.cmod.contents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.winlator.cmod.contentdialog.DriverRepo;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RemoteDriverCatalogTest {
    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    private static List<RemoteDriverCatalog.Entry> parse(String releasesJson) throws Exception {
        List<RemoteDriverCatalog.Entry> out = new ArrayList<>();
        RemoteDriverCatalog.parseReleases(new DriverRepo("Repo", "u"), new JSONArray(releasesJson), out);
        return out;
    }

    @Test
    public void onlyZipAssetsBecomeDrivers() throws Exception {
        List<RemoteDriverCatalog.Entry> list = parse("["
                + "{\"name\":\"Turnip v25\",\"assets\":[{\"name\":\"turnip.zip\",\"browser_download_url\":\"https://g/1.zip\"}]},"
                + "{\"name\":\"Multi\",\"assets\":[{\"name\":\"a.zip\",\"browser_download_url\":\"https://g/a.zip\"},"
                + "{\"name\":\"b.ZIP\",\"browser_download_url\":\"https://g/b.zip\"},"
                + "{\"name\":\"src.tar.gz\",\"browser_download_url\":\"https://g/s.tgz\"}]},"
                + "{\"name\":\"NoAssets\"}]");
        assertEquals(3, list.size());
        // one zip in a release: the release name; several: the asset names, without .zip in any case
        assertEquals("Turnip v25", list.get(0).name);
        assertEquals("a", list.get(1).name);
        assertEquals("b", list.get(2).name);
        assertEquals("Repo", list.get(0).repository);
    }

    @Test
    public void fallsBackToTheTagNameAndReadsSha256Digests() throws Exception {
        String digest = "sha256:" + repeat('b', 64);
        List<RemoteDriverCatalog.Entry> list = parse("["
                + "{\"tag_name\":\"v1\",\"assets\":[{\"name\":\"only.zip\",\"browser_download_url\":\"https://g/o.zip\",\"digest\":\"" + digest + "\"}]},"
                + "{\"name\":\"Other\",\"assets\":[{\"name\":\"x.zip\",\"browser_download_url\":\"https://g/x.zip\",\"digest\":\"md5:123\"}]}]");
        assertEquals("v1", list.get(0).name);
        assertEquals(repeat('b', 64), list.get(0).sha256);
        assertNull(list.get(1).sha256);
    }

    @Test
    public void aRepositoryContributesAtMostFortyDrivers() throws Exception {
        JSONArray many = new JSONArray();
        for (int i = 0; i < 60; i++) {
            many.put(new JSONObject("{\"name\":\"r" + i + "\",\"assets\":[{\"name\":\"x.zip\",\"browser_download_url\":\"https://g/" + i + ".zip\"}]}"));
        }
        List<RemoteDriverCatalog.Entry> out = new ArrayList<>();
        RemoteDriverCatalog.parseReleases(new DriverRepo("R", "u"), many, out);
        assertEquals(40, out.size());
    }

    @Test
    public void githubErrorMessagesAreSurfaced() {
        assertTrue(RemoteDriverCatalog.apiMessage("{\"message\":\"API rate limit exceeded for 1.2.3.4\"}")
                .startsWith("API rate limit exceeded"));
        assertEquals("unexpected response from the server", RemoteDriverCatalog.apiMessage("<html>"));
    }
}
