package com.winlator.cmod.components;

import android.content.Context;

import com.winlator.cmod.R;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineRuntimeGuard;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Proton runtimes that are not part of the component catalog: the bundled default (so it can be
 * downloaded again) and installed folders no list mentions any more. Downloadable Protons come from
 * the catalog like every other component, see {@link ContentsSource}.
 */
public final class ProtonSource implements ComponentSource {
    @Override
    public String title() {
        return "Proton packages";
    }

    @Override
    public void refresh(Context context) {
        // Nothing to fetch: the listing is local (the bundled package and what is installed).
    }

    @Override
    public List<ComponentEntry> entries(Context context) {
        // The bundled default has its own card in the component manager, so it is not a row here.
        // What is listed: installed earlier from a package no list offers any more - nothing to
        // download, but it can still be removed here (and is still selectable as a runtime).
        ArrayList<ComponentEntry> out = new ArrayList<>();
        for (String identifier : ProtonPackageManager.getInstalledUnlisted(context)) {
            out.add(unlistedEntry(identifier));
        }
        return out;
    }

    /**
     * The entry for one package identifier: a listed package (including the bundled default, which
     * the component manager shows as its own card and so leaves out of {@link #entries}), or an
     * installed folder no list mentions. Null when the identifier is not a Proton package.
     */
    public ComponentEntry entryFor(Context context, String identifier) {
        ProtonPackageManager.PackageInfo info = ProtonPackageManager.getPackage(identifier);
        if (info != null) {
            ProtonEntry entry = new ProtonEntry(info);
            entry.installed = ProtonPackageManager.isInstalled(context, identifier);
            return entry;
        }
        if (ProtonPackageManager.getInstalledUnlisted(context).contains(identifier)) {
            return unlistedEntry(identifier);
        }
        return null;
    }

    // Nothing to download (no address), but it can be removed and selected as a runtime.
    private static ProtonEntry unlistedEntry(String identifier) {
        ProtonEntry entry = new ProtonEntry(new ProtonPackageManager.PackageInfo(identifier, identifier, identifier, new long[0]));
        entry.installed = true;
        return entry;
    }

    private static final class ProtonEntry extends ComponentEntry {
        final ProtonPackageManager.PackageInfo info;

        ProtonEntry(ProtonPackageManager.PackageInfo info) {
            super("release-proton:" + info.identifier, "Proton", info.title, 0);
            this.info = info;
        }

        @Override
        public String runtimeName() {
            return installed ? info.identifier : null;
        }

        @Override
        public String runtimeId() {
            return info.identifier;
        }

        @Override
        public String downloadUrl() {
            return info.directUrl;
        }

        @Override
        public String sha256() {
            return info.sha256;
        }

        @Override
        public void install(Context context, InstallSink sink) throws ComponentException {
            sink.progress("Preparing " + name, 0);
            File archive = new File(context.getCacheDir(), info.identifier + "-" + System.nanoTime());
            try {
                try {
                    ProtonPackageManager.downloadPackageOrThrow(info, archive,
                            percent -> sink.progress("Downloading " + name, Math.min(70, percent * 70 / 100)));
                } catch (Downloader.DownloadException e) {
                    throw new ComponentException("Unable to install " + name + ": " + e.userMessage(), e);
                }
                sink.progress("Installing " + name, 72);
                if (!ProtonPackageManager.installPackage(context, info.identifier, archive)) {
                    throw new ComponentException("Unable to install " + name + ": the package could not be unpacked");
                }
            } finally {
                archive.delete();
            }
        }

        @Override
        public RemovePlan planRemoval(Context context) {
            String using = WineRuntimeGuard.getContainerUsing(context, info.identifier);
            if (using != null) {
                return RemovePlan.blocked("Runtime is in use", name + " cannot be deleted because it is used by " + using + ".");
            }
            return RemovePlan.confirm("Delete component?",
                    "The installed files will be removed from " + context.getString(R.string.app_name) + ".");
        }

        @Override
        public void remove(Context context) throws ComponentException {
            if (WineRuntimeGuard.getContainerUsing(context, info.identifier) != null) {
                throw new ComponentException(name + " is in use and cannot be deleted");
            }
            ProtonPackageManager.deletePackage(context, info.identifier);
        }
    }
}
