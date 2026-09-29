package com.winlator.cmod.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Only the parts of RemoteSources that need no Android Context. */
public class RemoteSourcesTest {
    private static final String API = "https://api.github.com/repos/foo/bar/releases";

    @Test
    public void repositoryInputIsTurnedIntoTheReleasesApiAddress() {
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("foo/bar"));
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("https://github.com/foo/bar"));
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("github.com/foo/bar/releases"));
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("https://github.com/foo/bar.git"));
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("https://github.com/foo/bar/"));
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("https://github.com/foo/bar?tab=readme"));
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl("  foo/bar  "));
    }

    @Test
    public void otherAddressesAreLeftAsTyped() {
        assertEquals(API, RemoteSources.normalizeDriverRepoUrl(API));
        assertEquals("https://example.com/x/y", RemoteSources.normalizeDriverRepoUrl("https://example.com/x/y"));
        assertEquals("", RemoteSources.normalizeDriverRepoUrl(null));
    }

    @Test
    public void repositoryNameIsSuggestedFromTheAddress() {
        assertEquals("bar", RemoteSources.suggestRepoName(API));
        assertEquals("Custom repository", RemoteSources.suggestRepoName("https://example.com/list.json"));
    }

    @Test
    public void httpsCheck() {
        assertTrue(RemoteSources.isHttps("https://a"));
        assertTrue(RemoteSources.isHttps("  HTTPS://a"));
        assertFalse(RemoteSources.isHttps("http://a"));
        assertFalse(RemoteSources.isHttps(null));
    }

    @Test
    public void knownBadCatalogEntriesAreIgnored() {
        assertTrue(RemoteSources.isIgnoredContentUrl(
                "https://github.com/StevenMXZ/Winlator-Contents/releases/download/1.0/Proton.9.0-x86_64.wcp"));
        assertFalse(RemoteSources.isIgnoredContentUrl("https://example.com/dxvk.wcp"));
        assertFalse(RemoteSources.isIgnoredContentUrl(null));
    }
}
