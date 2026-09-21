package com.winlator.cmod.contentdialog;

import android.app.Activity;
import android.view.View;

import androidx.annotation.NonNull;

import com.winlator.cmod.core.Callback;
import com.winlator.cmod.core.DebugLogFile;
import com.winlator.cmod.ui.DebugLogDialogHost;
import com.winlator.cmod.ui.DebugLogState;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

// Themed replacement for the old ContentDialog-based log/debug panel (debug_dialog.xml +
// debug_toolbar.xml, ui_enhanced_card background) — see DebugLogDialogHost.kt for the
// Compose content this now shows. Public API (constructor takes the Activity, call() feeds
// a line in, show() opens it) is unchanged, so ProcessHelper.addDebugCallback(...) in
// XServerDisplayActivity doesn't need to change.
//
// The old touch-drag-pauses-ingestion behavior (LogView.onTouchEvent calling
// DebugDialog.setPaused) doesn't have an equivalent here: a LazyColumn keeps the user's
// scroll position on its own while new lines are appended, so nothing needs to auto-pause
// just because the user is scrolling. The explicit Pause toolbar button (now in
// DebugLogContent) is the only way to stop ingestion, same intent as before.
public class DebugDialog implements Callback<String> {
    private final Activity activity;
    private final DebugLogState state = new DebugLogState();
    private View overlayView;
    private final BufferedWriter writer;

    public DebugDialog(@NonNull Activity activity) {
        this.activity = activity;
        try {
            writer = new BufferedWriter(new FileWriter(DebugLogFile.getLogFile(activity)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void show() {
        if (overlayView != null) return;
        overlayView = DebugLogDialogHost.show(activity, state, () -> overlayView = null);
    }

    @Override
    public void call(final String line) {
        state.append(line);
        try {
            writer.write(line + "\n");
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
