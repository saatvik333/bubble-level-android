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
    boolean calibrated;
    float fbx, fby, ebx, eby;

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

    private static final int CAL_VERSION = 2;

    void loadCalibration(File f) {
        calibrated = false;
        fbx = fby = ebx = eby = 0f;
        if (!f.isFile()) return;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != CAL_VERSION) return;
            float a = in.readFloat(), b = in.readFloat(), c = in.readFloat(), d = in.readFloat();
            if (!(ok(a) && ok(b) && ok(c) && ok(d))) return;
            fbx = a; fby = b; ebx = c; eby = d;
            calibrated = in.readBoolean();
        } catch (IOException e) {
            calibrated = false;
            fbx = fby = ebx = eby = 0f;
        }
    }

    void saveCalibration(File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(CAL_VERSION);
            out.writeFloat(fbx);
            out.writeFloat(fby);
            out.writeFloat(ebx);
            out.writeFloat(eby);
            out.writeBoolean(calibrated);
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
