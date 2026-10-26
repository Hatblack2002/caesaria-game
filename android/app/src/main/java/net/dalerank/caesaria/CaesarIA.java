package net.dalerank.caesaria;

import org.libsdl.app.SDLActivity;

import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * CaesarIA main activity.
 *
 * - Loads the single native library `main` (SDL2 sdl2mini is statically linked into it).
 * - On first run, extracts the bundled engine resources (assets/resources,
 *   coming from the repo bin/resources directory) into the app external files
 *   directory, which is where the native code looks for them
 *   (SDL_AndroidGetExternalStoragePath()).
 * - Installs a crash reporter that captures logcat of the previous session and
 *   uploads crash reports to a dedicated GitHub logs repository (if configured
 *   through assets/telemetry.properties).
 */
public class CaesarIA extends SDLActivity {

    /** bump when bundled resources change to force re-extraction */
    private static final int ASSETS_REV = 1;

    @Override
    protected String[] getLibraries() {
        return new String[] {
            "main"
        };
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        CrashReporter.init(this);
        extractEngineResources();
        super.onCreate(savedInstanceState);
    }

    private void extractEngineResources() {
        File extDir = getExternalFilesDir(null);
        if (extDir == null) {
            return; // storage unavailable; native code will show its own error
        }

        File revMarker = new File(extDir, ".caesaria_assets_rev");
        if (revMarker.exists()) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(revMarker)) {
                int rev = Integer.parseInt(new String(readAll(fis)).trim());
                if (rev >= ASSETS_REV) {
                    return; // already extracted
                }
            } catch (Exception ignored) {
            }
        }

        try {
            File resDir = new File(extDir, "resources");
            copyAssetDir("resources", resDir);
            try (FileOutputStream fos = new FileOutputStream(revMarker)) {
                fos.write(String.valueOf(ASSETS_REV).getBytes("UTF-8"));
            }
        } catch (IOException e) {
            android.util.Log.e("CaesarIA", "asset extraction failed", e);
        }
    }

    private void copyAssetDir(String assetPath, File targetDir) throws IOException {
        String[] children = getAssets().list(assetPath);
        // is it a file? try to copy as file
        if (children == null || children.length == 0) {
            targetDir.getParentFile().mkdirs();
            InputStream in = null;
            OutputStream out = null;
            try {
                in = getAssets().open(assetPath);
                out = new FileOutputStream(targetDir);
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            } catch (IOException e) {
                // not a file: probably an empty directory, just create it
                targetDir.mkdirs();
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
            return;
        }

        targetDir.mkdirs();
        for (String child : children) {
            copyAssetDir(assetPath + "/" + child, new File(targetDir, child));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) {
            bos.write(buf, 0, r);
        }
        return bos.toByteArray();
    }
}
