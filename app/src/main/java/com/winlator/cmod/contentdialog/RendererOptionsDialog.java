package com.winlator.cmod.contentdialog;

public class RendererOptionsDialog {
    // The interactive dialog UI that used to live here (spinners for present mode,
    // driver, filter, swap-RB) was dead code: its only caller was the legacy
    // ShortcutSettingsDialog, which is itself never shown — the live per-shortcut
    // editor is ui/shortcut/ShortcutEditorV2.kt (Compose). Trimmed down to the one
    // static helper XServerDisplayActivity still calls live.
    public static int toVkPresentMode(String mode) {
        if (mode == null) return 2;
        switch (mode) {
            case "mailbox":       return 1;
            default:              return 2;
        }
    }
}
