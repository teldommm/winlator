package com.winlator.cmod.components;

import android.content.Context;

import java.util.List;

/** A place components come from: it can refresh its remote listing and report what it has. */
public interface ComponentSource {
    /** Short name used in error messages ("Component list", "Driver repositories", ...). */
    String title();

    /**
     * Blocking network refresh. May still have updated {@link #entries} and then throw to report a
     * problem (for example one of several repositories being unreachable).
     */
    void refresh(Context context) throws ComponentException;

    /** Current rows (installed and available), built from local state and the last refresh. No network. */
    List<ComponentEntry> entries(Context context);
}
