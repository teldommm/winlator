package com.winlator.cmod.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.format.DateFormat;

import androidx.preference.PreferenceManager;


import java.io.File;
import java.util.Date;

// Extracted from the old LogView (a custom-drawn View) when the debug log dialog moved to
// Compose (see DebugLogDialogHost.kt) — this half of LogView was never about drawing, just
// naming/locating the on-disk file DebugDialog writes the live log to, so it lives on as a
// small static-only holder under core/ rather than widget/ (nothing here is a View anymore).
public class DebugLogFile {
    private static String fileName;

    public static void setFilename(String file) {
        fileName = file.substring(0, file.lastIndexOf("."));
    }

    public static File getLogFile(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String winlatorPath = sp.getString("winlator_path_uri", null);
        File logsDir;

        if (winlatorPath != null) {
            Uri winlatorUri = Uri.parse(winlatorPath);
            logsDir = new File(FileUtils.getFilePathFromUri(context, winlatorUri), "logs");
        } else {
            logsDir = new File(AppDefaults.DEFAULT_WINLATOR_PATH, "logs");
        }

        if (!logsDir.exists()) logsDir.mkdirs();

        String logFile = fileName.replaceAll("\\s", "_").toLowerCase() + "_" + DateFormat.format("yyyy-MM-dd_HH-mm-ss", new Date()) + ".txt";
        return new File(logsDir, logFile);
    }
}
