package com.winlator.cmod.contentdialog;

import java.util.HashMap;
import java.util.Map;

public class GraphicsDriverConfig {
    // Was GraphicsDriverConfigDialog. The interactive dialog UI here (GPU/
    // extension/present-mode pickers, etc.) was dead code — its only caller
    // was the legacy ShortcutSettingsDialog, which is itself never shown (the
    // live per-shortcut editor is ui/shortcut/ShortcutEditorV2.kt). Trimmed to
    // the static config codec used live by XServerDisplayActivity and
    // AdrenotoolsManager, and renamed since it's no longer a dialog.
    public static HashMap<String, String> parseGraphicsDriverConfig(String graphicsDriverConfig) {
        HashMap<String, String> mappedConfig = new HashMap<>();
        String[] configElements = graphicsDriverConfig.split(";");
        for (String element : configElements) {
            String key;
            String value;
            String[] splittedElement = element.split("=");
            key = splittedElement[0];
            if (splittedElement.length > 1)
                value = element.split("=")[1];
            else
                value = "";
            mappedConfig.put(key, value);
        }
        return mappedConfig;
    }

    public static String toGraphicsDriverConfig(HashMap<String, String> config) {
        String graphicsDriverConfig = "";
        for (Map.Entry<String, String> entry : config.entrySet()) {
            graphicsDriverConfig += entry.getKey() + "=" + entry.getValue() + ";";
        }
        return graphicsDriverConfig.substring(0, graphicsDriverConfig.length() - 1);
    }
}
