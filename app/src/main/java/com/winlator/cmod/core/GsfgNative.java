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
 * GSFG graph. Native code reads it by path, so it is copied into the app's files directory once
 * and refreshed whenever the bundled asset changes size.
 */
public final class GsfgNative {
    private static final String TAG = "GsfgNative";
    public static final String ASSET_NAME = "gsfg/gsfg_shaders.bin";

    private GsfgNative() {}

    public static File packFile(Context context) {
        return new File(new File(context.getFilesDir(), "gsfg"), "gsfg_shaders.bin");
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
            if (out.isFile() && out.length() > 0 && (assetSize < 0 || out.length() == assetSize))
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
            return out.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "cannot provide the GSFG shader pack", e);
            return null;
        }
    }
}
