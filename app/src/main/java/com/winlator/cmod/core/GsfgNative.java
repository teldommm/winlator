package com.winlator.cmod.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Native (compositor-side) GSFG frame generation: hands the bundled shader pack to the renderer.
 *
 * <p>The pack ({@code assets/gsfg/gsfg_shaders.bin}) holds the 35 SPIR-V compute shaders of the
 * GSFG graph. Native code reads it by path, so it is copied into the app's files directory and
 * refreshed whenever the app is updated (or the bundled asset changes size). The size alone is not
 * enough: a compressed asset has no length to compare, and a rebuilt pack may keep its size.
 */
public final class GsfgNative {
    private static final String TAG = "GsfgNative";
    public static final String ASSET_NAME = "gsfg/gsfg_shaders.bin";

    private GsfgNative() {}

    public static File packFile(Context context) {
        return new File(new File(context.getFilesDir(), "gsfg"), "gsfg_shaders.bin");
    }

    // Changes with every install/update of the APK, i.e. whenever the bundled pack may have changed.
    private static String appStamp(Context context) {
        try {
            android.content.pm.PackageInfo pi =
                    context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.lastUpdateTime + ":" + pi.versionCode;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String readStamp(File f) {
        if (!f.isFile()) return "";
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 256)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    /** Returns the absolute path of the extracted shader pack, or null if it cannot be provided. */
    public static synchronized String ensurePack(Context context) {
        File out = packFile(context);
        try {
            long assetSize = -1;
            try (android.content.res.AssetFileDescriptor fd = context.getAssets().openFd(ASSET_NAME)) {
                assetSize = fd.getLength();
            } catch (IOException compressed) {
                // The asset is stored compressed; fall through and size it while copying.
            }
            File stampFile = new File(out.getParentFile(), "gsfg_shaders.stamp");
            String stamp = appStamp(context);
            if (out.isFile() && out.length() > 0 && (assetSize < 0 || out.length() == assetSize)
                    && stamp.equals(readStamp(stampFile)))
                return out.getAbsolutePath();

            File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return null;
            File tmp = new File(dir, "gsfg_shaders.bin.tmp");
            try (InputStream in = context.getAssets().open(ASSET_NAME);
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) os.write(buffer, 0, n);
            }
            if (out.exists() && !out.delete()) return null;
            if (!tmp.renameTo(out)) return null;
            try (OutputStream os = new FileOutputStream(stampFile)) {
                os.write(stamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return out.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "cannot provide the GSFG shader pack", e);
            return null;
        }
    }
}
