package com.winlator.cmod.components;

import android.content.Context;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.ContentProfile;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.Downloader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Everything that can be installed, behind one object: the component catalog, Proton packages and
 * GPU drivers. The component manager screen drives it entry by entry; the container / shortcut
 * editors use the {@code install*} methods when they only need "install this version".
 * Both go through the same {@link ComponentEntry} code, so a download is verified and reported the
 * same way wherever it was started.
 *
 * Create one per operation or screen; it holds no global state. All methods that touch the network
 * or the disk block, so call them off the main thread.
 */
public final class ComponentCatalog {
    private static final InstallSink NO_PROGRESS = (label, percent) -> { };

    private final Context context;
    private final ContentsManager contentsManager;
    private final AdrenotoolsManager adrenotools;
    private final ContentsSource contents;
    private final ProtonSource proton;
    private final DriverSource drivers;
    private final List<ComponentSource> sources;

    public ComponentCatalog(Context context) {
        this(context, new ContentsManager(context), new AdrenotoolsManager(context));
    }

    public ComponentCatalog(Context context, ContentsManager contentsManager, AdrenotoolsManager adrenotools) {
        this.context = context;
        this.contentsManager = contentsManager;
        this.adrenotools = adrenotools;
        this.contents = new ContentsSource(contentsManager);
        this.proton = new ProtonSource();
        this.drivers = new DriverSource(adrenotools);
        this.sources = Collections.unmodifiableList(Arrays.asList(contents, proton, drivers));
    }

    public ContentsManager contentsManager() {
        return contentsManager;
    }

    public AdrenotoolsManager adrenotools() {
        return adrenotools;
    }

    public ContentsSource contents() {
        return contents;
    }

    public List<ComponentSource> sources() {
        return sources;
    }

    /** All rows from all sources, from local state and the last refresh (no network). */
    public List<ComponentEntry> entries() {
        ArrayList<ComponentEntry> all = new ArrayList<>();
        for (ComponentSource source : sources) {
            try {
                all.addAll(source.entries(context));
            } catch (RuntimeException ignored) {
                // One broken source must not take the others' rows with it.
            }
        }
        return all;
    }

    /**
     * Refreshes the sources from the network, ignoring failures: whatever was loaded before (or is
     * cached) stays in {@link #entries()}. For screens that only need the lists, not the reasons -
     * the component manager itself refreshes source by source and reports errors.
     *
     * @param includeDrivers the driver repositories cost one request each, so pickers that don't
     *                       show remote drivers can skip them
     */
    public void refreshQuietly(boolean includeDrivers) {
        for (ComponentSource source : sources) {
            if (!includeDrivers && source == drivers) continue;
            try {
                source.refresh(context);
            } catch (Exception ignored) {
                // Keep going: one unreachable source must not hide the others.
            }
        }
    }

    /** Like {@link #entries()} but without the drivers: catalog rows (Wine, Proton, DXVK, ...) and Proton packages. */
    public List<ComponentEntry> componentEntries() {
        ArrayList<ComponentEntry> all = new ArrayList<>();
        for (ComponentSource source : sources) {
            if (source == drivers) continue;
            try {
                all.addAll(source.entries(context));
            } catch (RuntimeException ignored) {
                // See entries().
            }
        }
        return all;
    }

    // ------------------------------------------------------------------ one-shot installs

    /**
     * Installs the catalog entry {@code typeName} / {@code version} (DXVK 2.4, FEXCore 2506, ...).
     * The id of a successful outcome is the installed version name.
     */
    public InstallOutcome installContent(String typeName, String version) {
        ContentProfile.ContentType type = ContentProfile.ContentType.getTypeByName(typeName);
        if (type == null) return InstallOutcome.failed("Unknown component type " + typeName);

        String refreshError = null;
        try {
            contents.refresh(context);
        } catch (ComponentException e) {
            refreshError = e.getMessage();
        }
        ContentProfile profile = contents.findRemote(type, version);
        if (profile == null) {
            return InstallOutcome.failed(version + " is not in the component list"
                    + (refreshError != null ? " (" + refreshError + ")" : ""));
        }
        try {
            ContentProfile installed = contents.installRemote(context, profile, NO_PROGRESS);
            return InstallOutcome.ok(installed.verName != null ? installed.verName : version);
        } catch (ComponentException e) {
            return InstallOutcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            return InstallOutcome.failed("Unable to install " + version);
        } finally {
            ContentsManager.cleanTmpDir(context);
            contentsManager.syncContents();
        }
    }

    /**
     * Installs the bundled Proton package again, or does nothing when it is already there. (Every
     * other Proton is a catalog component, see {@link #installContent}.)
     */
    public InstallOutcome installProton(String identifier) {
        ComponentEntry entry = proton.entryFor(context, identifier);
        if (entry == null) return InstallOutcome.failed("Unknown Proton package " + identifier);
        if (entry.installed) return InstallOutcome.ok(identifier);
        try {
            entry.install(context, NO_PROGRESS);
            return InstallOutcome.ok(identifier);
        } catch (ComponentException e) {
            return InstallOutcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            return InstallOutcome.failed("Unable to install " + entry.name);
        }
    }

    /**
     * Installs a Wine / Proton runtime chosen in the container editor: a Proton package when the
     * identifier is one, otherwise a catalog entry. The id of a successful outcome is what the
     * container stores as its runtime.
     */
    public InstallOutcome installRuntime(String identifier, String typeName, String version) {
        if (proton.entryFor(context, identifier) != null) return installProton(identifier);

        InstallOutcome outcome = installContent(typeName, version);
        if (!outcome.succeeded()) return outcome;

        ContentProfile.ContentType type = ContentProfile.ContentType.getTypeByName(typeName);
        ContentProfile newest = null;
        for (ContentProfile profile : contentsManager.getInstalledProfiles(type)) {
            if (!outcome.id.equals(profile.verName)) continue;
            if (newest == null || profile.verCode > newest.verCode) newest = profile;
        }
        return newest != null
                ? InstallOutcome.ok(ContentsManager.getEntryName(newest))
                : InstallOutcome.failed("Unable to install " + version + ": it was not found after installing");
    }

    /** Downloads and installs an AdrenoTools driver archive. The id of a successful outcome is the driver id. */
    public InstallOutcome installDriver(String url, String sha256) {
        try {
            String id = RemoteDriverCatalog.installOrThrow(context, url, sha256);
            return id.isEmpty()
                    ? InstallOutcome.failed("Not a valid AdrenoTools driver package")
                    : InstallOutcome.ok(id);
        } catch (Downloader.DownloadException e) {
            return InstallOutcome.failed(e.userMessage());
        } catch (RuntimeException e) {
            return InstallOutcome.failed("Unable to install the driver");
        }
    }
}
