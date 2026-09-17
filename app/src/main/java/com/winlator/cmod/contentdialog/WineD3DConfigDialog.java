package com.winlator.cmod.contentdialog;

import android.content.Context;

import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class WineD3DConfigDialog {
    // The interactive dialog UI here was dead code — its only caller was the
    // legacy ShortcutSettingsDialog, which is itself never shown (the live
    // per-shortcut editor is ui/shortcut/ShortcutEditorV2.kt). Trimmed to the
    // static GPU-lookup + env-var helpers used live by XServerDisplayActivity.
    public static String getDeviceIdFromGPUName(Context context, String gpuName) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        String deviceId = "";
        try {
            JSONArray jsonArray = new JSONArray(gpuNameList);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                if (jobj.getString("name").contains(gpuName)) {
                    deviceId = jobj.getString("deviceID");
                }
            }
        }
        catch (JSONException e) {
        }

        return deviceId;
    }

    public static String getVendorIdFromGPUName(Context context, String gpuName) {
        String gpuNameList = FileUtils.readString(context, "gpu_cards.json");
        String vendorId = "";
        try {
            JSONArray jsonArray = new JSONArray(gpuNameList);
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject jobj = jsonArray.getJSONObject(i);
                if (jobj.getString("name").contains(gpuName)) {
                    vendorId = jobj.getString("vendorID");
                }
            }
        }
        catch (JSONException e) {
        }

        return vendorId;
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars vars) {
        String deviceID = getDeviceIdFromGPUName(context, config.get("gpuName"));
        String vendorID = getVendorIdFromGPUName(context, config.get("vendorID"));
        String wined3dConfig = "csmt=0x" + config.get("csmt") + ",strict_shader_math=0x" + config.get("strict_shader_math") + ",OffscreenRenderingMode=" + config.get("OffscreenRenderingMode") + ",VideoMemorySize=" + config.get("videoMemorySize") + ",VideoPciDeviceID=" + deviceID + ",VideoPciVendorID=" + vendorID + ",renderer=" + config.get("renderer");
        vars.put("WINE_D3D_CONFIG", wined3dConfig);
    }
}
