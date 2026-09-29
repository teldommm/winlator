package com.winlator.cmod.components;

import android.content.Context;

/**
 * One row of the component manager. Each kind (catalog package, Proton package, driver) is its own
 * subclass and knows how to install and remove itself, so the controller never has to look at an
 * id prefix to decide what to do.
 *
 * {@link #id} is stable for the lifetime of the listing and opaque to the UI.
 */
public abstract class ComponentEntry {
    public final String id;
    /** Category shown in the UI ("Wine", "DXVK", "AdrenoTools", ...). */
    public final String type;
    public final String name;
    public final int versionCode;

    public boolean installed;
    public boolean recommended;

    protected ComponentEntry(String id, String type, String name, int versionCode) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.versionCode = versionCode;
    }

    /** Text for the row. */
    public String label() {
        return name;
    }

    public boolean removable() {
        return installed;
    }

    /** Installed runtime name for Wine/Proton rows (may be empty), null for everything else. */
    public String runtimeName() {
        return null;
    }

    public boolean isDriver() {
        return false;
    }

    /** True when there is something to download and install. */
    public boolean canInstall() {
        return !installed;
    }

    /** Blocking. Downloads and installs; throws with a user-facing message on failure. */
    public abstract void install(Context context, InstallSink sink) throws ComponentException;

    /** Null when there is nothing to remove. */
    public abstract RemovePlan planRemoval(Context context);

    /** Blocking. Re-checks that removal is still allowed. */
    public abstract void remove(Context context) throws ComponentException;

    public String removedMessage() {
        return "Component removed";
    }
}
