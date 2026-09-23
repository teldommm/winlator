package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import androidx.preference.PreferenceManager;

// App-wide default paths/values and small preference helpers. These used to be static members of
// SettingsFragment, which forced unrelated code (preset managers, ImageFsInstaller,
// XServerDisplayActivity, ...) to depend on a UI class. SettingsFragment no longer exists — the
// Settings tab is a composable (ui/settings/SettingsRoute.kt).
public final class AppDefaults {
    public static final String DEFAULT_WINE_DEBUG_CHANNELS = "warn,err,fixme";
    public static final String DEFAULT_WINLATOR_PATH = Environment.getExternalStorageDirectory().getPath() + "/Winlator";
    public static final String DEFAULT_SHORTCUT_EXPORT_PATH = DEFAULT_WINLATOR_PATH + "/Shortcuts";

    private AppDefaults() {
    }

    // Forget the recorded emulator versions so they're re-extracted on next launch (called after
    // an ImageFs (re)install).
    public static void resetEmulatorsVersion(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit()
                .remove("current_box64_version")
                .remove("current_wowbox64_version")
                .remove("current_fexcore_version")
                .apply();
    }
}
