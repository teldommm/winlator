package com.winlator.cmod.components;

import android.content.Context;

import com.winlator.cmod.contents.AdrenotoolsManager;
import com.winlator.cmod.contents.RemoteDriverCatalog;
import com.winlator.cmod.core.Downloader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** AdrenoTools GPU drivers: the ones already installed plus those listed by the driver repositories. */
public final class DriverSource implements ComponentSource {
    private static final String TYPE = "AdrenoTools";

    private final AdrenotoolsManager adrenotools;
    private volatile List<RemoteDriverCatalog.Entry> remote = new ArrayList<>();

    public DriverSource(AdrenotoolsManager adrenotools) {
        this.adrenotools = adrenotools;
    }

    @Override
    public String title() {
        return "Driver repositories";
    }

    @Override
    public void refresh(Context context) throws ComponentException {
        ArrayList<String> errors = new ArrayList<>();
        remote = RemoteDriverCatalog.load(context, errors);
        if (!errors.isEmpty()) {
            String more = errors.size() > 1 ? " (+" + (errors.size() - 1) + " more)" : "";
            throw new ComponentException(errors.get(0) + more);
        }
    }

    @Override
    public List<ComponentEntry> entries(Context context) {
        ArrayList<ComponentEntry> out = new ArrayList<>();
        Set<String> existing = new HashSet<>();
        for (String id : adrenotools.enumarateInstalledDrivers()) {
            String label = (adrenotools.getDriverName(id) + " " + adrenotools.getDriverVersion(id)).trim();
            out.add(new InstalledDriverEntry(id, label));
            existing.add(label.toLowerCase(Locale.ENGLISH));
        }
        for (RemoteDriverCatalog.Entry driver : remote) {
            if (existing.contains(driver.name.toLowerCase(Locale.ENGLISH))) continue;
            out.add(new RemoteDriverEntry(driver));
        }
        return out;
    }

    private final class InstalledDriverEntry extends ComponentEntry {
        private final String driverId;
        private final String label;

        InstalledDriverEntry(String driverId, String label) {
            super("adrenotools:" + driverId, TYPE, label, 0);
            this.driverId = driverId;
            this.label = label;
            this.installed = true;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public boolean isDriver() {
            return true;
        }

        @Override
        public void install(Context context, InstallSink sink) throws ComponentException {
            throw new ComponentException(label + " is already installed");
        }

        @Override
        public RemovePlan planRemoval(Context context) {
            return RemovePlan.confirm("Delete driver?", "The installed driver files will be removed.");
        }

        @Override
        public void remove(Context context) {
            adrenotools.removeDriver(driverId);
        }

        @Override
        public String removedMessage() {
            return "Driver removed";
        }
    }

    private static final class RemoteDriverEntry extends ComponentEntry {
        private final RemoteDriverCatalog.Entry driver;

        RemoteDriverEntry(RemoteDriverCatalog.Entry driver) {
            super("remote-driver:" + driver.name + ":" + Integer.toHexString(driver.url.hashCode()), TYPE, driver.name, 0);
            this.driver = driver;
        }

        @Override
        public String label() {
            return driver.name + " \u2022 " + driver.repository;
        }

        @Override
        public boolean removable() {
            return false;
        }

        @Override
        public boolean isDriver() {
            return true;
        }

        @Override
        public void install(Context context, InstallSink sink) throws ComponentException {
            sink.progress("Installing " + name, -1);
            String installedId;
            try {
                installedId = RemoteDriverCatalog.installOrThrow(context, driver.url, driver.sha256,
                        percent -> sink.progress("Downloading " + name, percent));
            } catch (Downloader.DownloadException e) {
                throw new ComponentException("Unable to install " + name + ": " + e.userMessage(), e);
            }
            if (installedId.isEmpty()) {
                throw new ComponentException("Unable to install " + name + ": not a valid AdrenoTools driver package");
            }
        }

        @Override
        public RemovePlan planRemoval(Context context) {
            return null;
        }

        @Override
        public void remove(Context context) {
        }
    }
}
