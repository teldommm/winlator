package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ProtonManifestTest {
    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        java.util.Arrays.fill(chars, c);
        return new String(chars);
    }

    @Test
    public void parsesAValidEntry() {
        String sha = repeat('a', 64);
        List<ProtonPackageManager.PackageInfo> list = ProtonManifest.parse(
                "[{\"identifier\":\"proton-11-x\",\"title\":\"P11\",\"url\":\"https://h.example/dl/p11.wcp.xz?token=1\","
                        + "\"size\":123,\"sha256\":\"" + sha.toUpperCase() + "\"}]");
        assertNotNull(list);
        assertEquals(1, list.size());
        ProtonPackageManager.PackageInfo info = list.get(0);
        assertEquals("proton-11-x", info.identifier);
        assertEquals("P11", info.title);
        assertEquals(123L, info.partSizes[0]);
        assertEquals("https://h.example/dl/p11.wcp.xz?token=1", info.directUrl);
        assertEquals("p11.wcp.xz", info.fileName);
        assertEquals(sha, info.sha256);
    }

    @Test
    public void rejectsIdentifiersThatCouldEscapeTheInstallDirectory() {
        List<ProtonPackageManager.PackageInfo> list = ProtonManifest.parse("["
                + "{\"identifier\":\"../../etc\",\"url\":\"https://h/x\"},"
                + "{\"identifier\":\"a/b\",\"url\":\"https://h/x\"},"
                + "{\"identifier\":\"\",\"url\":\"https://h/x\"},"
                + "{\"identifier\":\".hidden\",\"url\":\"https://h/x\"},"
                + "{\"identifier\":\"" + repeat('a', 65) + "\",\"url\":\"https://h/x\"},"
                + "{\"identifier\":\"ok-1\",\"url\":\"https://h/x\"}]");
        assertEquals(1, list.size());
        assertEquals("ok-1", list.get(0).identifier);
    }

    @Test
    public void onlyHttpsAddressesAreAccepted() {
        List<ProtonPackageManager.PackageInfo> list = ProtonManifest.parse("["
                + "{\"identifier\":\"a\",\"url\":\"http://h/x\"},"
                + "{\"identifier\":\"b\",\"url\":\"ftp://h/x\"},"
                + "{\"identifier\":\"c\",\"url\":\"file:///sdcard/x\"},"
                + "{\"identifier\":\"d\"}]");
        assertTrue(list.isEmpty());
    }

    @Test
    public void badOptionalFieldsDegradeInsteadOfDroppingTheEntry() {
        List<ProtonPackageManager.PackageInfo> list = ProtonManifest.parse(
                "[{\"identifier\":\"n\",\"url\":\"https://h/n\",\"sha256\":\"zz\",\"size\":-5}]");
        assertEquals(1, list.size());
        assertNull(list.get(0).sha256);
        assertEquals(0L, list.get(0).partSizes[0]);
        assertEquals("n", list.get(0).title);
    }

    @Test
    public void acceptsThePackagesWrapperForm() {
        assertEquals(1, ProtonManifest.parse("{\"packages\":[{\"identifier\":\"w\",\"url\":\"https://h/w\"}]}").size());
    }

    @Test
    public void garbageIsNotAManifest() {
        assertNull(ProtonManifest.parse("not json"));
        assertNull(ProtonManifest.parse(""));
        assertNull(ProtonManifest.parse(null));
        assertNull(ProtonManifest.parse("{\"x\":1}"));
    }

    @Test
    public void fileNameComesFromThePathOnly() {
        assertEquals("a.wcp", ProtonManifest.lastPathSegment("https://h/dir/a.wcp"));
        assertEquals("a.wcp", ProtonManifest.lastPathSegment("https://h/dir/a.wcp?x=1#frag"));
        assertEquals("dir", ProtonManifest.lastPathSegment("https://h/dir/"));
    }
}
