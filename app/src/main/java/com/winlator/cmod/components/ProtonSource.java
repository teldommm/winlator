package com.winlator.cmod.components;

import android.content.Context;

import com.winlator.cmod.R;
import com.winlator.cmod.core.Downloader;
import com.winlator.cmod.core.ProtonPackageManager;
import com.winlator.cmod.core.WineRuntimeGuard;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Proton runtimes: the built-in list plus whatever the optional manifest from Winlator Services adds. */
public final class ProtonSource implements ComponentSource {
    @Override
    public String title() {
        return "Proton packages";
    }

    @Override
    public void refresh(Context context) throws ComponentException {
        try {
            ProtonPackageManager.refreshRemote(context);
        } catch (Downloader.DownloadException e) {
            throw new ComponentException(e.userMessage(), e);
        }
    }

    @Override
    public List<ComponentEntry> entries(Context context) {
        ArrayList<ComponentEntry> out = new ArrayList<>();
        for (ProtonPackageManager.PackageInfo info : ProtonPackageManager.getPackages()) {
            // The default runtime ships with the app and has its own row (bundled runtime).
            if (ProtonPackageManager.DEFAULT_IDENTIFIER.equals(info.identifier)) continue;
            ProtonEntry entry = new ProtonEntry(info);
            entry.installed = ProtonPackageManager.isInstalled(context, info.identifier);
            out.add(entry);
        }
        return out;
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
