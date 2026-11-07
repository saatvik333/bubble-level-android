package app.level.bubble;

import android.content.SharedPreferences;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class Settings {
    static final String[] UNITS = {"Degrees", "Percent", "mm/m", "in/ft"};
    static final float[] TOLERANCE = {0.1f, 0.2f, 0.3f, 0.5f, 1.0f};
    static final String[] LOCKS = {"Auto", "Surface", "Edge"};

    String theme = "Aurum";
    int units = 0;
    int tolIdx = 1;
    int lock = LevelEngine.LOCK_AUTO;
    boolean haptics = true;
    boolean keepOn = true;
    // Calibration belongs to this phone's sensor: kept in a no-backup file, not in the preferences,
    // so cloud backup and device-to-device transfer never carry it to another phone.
    boolean calibrated;                     // calMask != 0
    int calMask;                            // see LevelEngine.calMask
    final float[] surf = new float[4];      // see LevelEngine.surf
    final float[] edge = new float[4];      // see LevelEngine.edge

    void load(SharedPreferences p) {
        theme = p.getString("theme", theme);
        units = clampIdx(p.getInt("units", units), UNITS.length);
        tolIdx = clampIdx(p.getInt("tol", tolIdx), TOLERANCE.length);
        lock = clampIdx(p.getInt("lock", lock), LOCKS.length);
        haptics = p.getBoolean("haptics", haptics);
        keepOn = p.getBoolean("keepOn", keepOn);
        if (p.contains("calibrated")) {           // 1.0 kept the offset here; drop it
            p.edit().remove("calibrated").remove("bx").remove("by").remove("bz").apply();
        }
    }

    void save(SharedPreferences p) {
        p.edit()
                .putString("theme", theme)
                .putInt("units", units)
                .putInt("tol", tolIdx)
                .putInt("lock", lock)
                .putBoolean("haptics", haptics)
                .putBoolean("keepOn", keepOn)
                .apply();
    }

    private static final int CAL_VERSION = 4;

    void clearCalibration() {
        calibrated = false;
        calMask = 0;
        for (int i = 0; i < 4; i++) surf[i] = edge[i] = 0f;
    }

    void loadCalibration(File f) {
        clearCalibration();
        if (!f.isFile()) return;
        float[] v = new float[8];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != CAL_VERSION) return;
            for (int i = 0; i < 8; i++) {
                v[i] = in.readFloat();
                if (!ok(v[i])) return;
            }
            int mask = in.readInt() & 0x3F;
            System.arraycopy(v, 0, surf, 0, 4);
            System.arraycopy(v, 4, edge, 0, 4);
            calMask = mask;
            calibrated = mask != 0;
        } catch (IOException e) {
            clearCalibration();
        }
    }

    void saveCalibration(File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(CAL_VERSION);
            for (float v : surf) out.writeFloat(v);
            for (float v : edge) out.writeFloat(v);
            out.writeInt(calMask);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static boolean ok(float v) {
        return !Float.isNaN(v) && Math.abs(v) <= 1.5f;
    }

    private static int clampIdx(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }
}
