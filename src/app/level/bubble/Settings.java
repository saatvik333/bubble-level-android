package app.level.bubble;

import android.content.SharedPreferences;

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
    boolean calibrated;
    float bx, by, bz;

    void load(SharedPreferences p) {
        theme = p.getString("theme", theme);
        units = clampIdx(p.getInt("units", units), UNITS.length);
        tolIdx = clampIdx(p.getInt("tol", tolIdx), TOLERANCE.length);
        lock = clampIdx(p.getInt("lock", lock), LOCKS.length);
        haptics = p.getBoolean("haptics", haptics);
        keepOn = p.getBoolean("keepOn", keepOn);
        calibrated = p.getBoolean("calibrated", false);
        bx = p.getFloat("bx", 0f);
        by = p.getFloat("by", 0f);
        bz = p.getFloat("bz", 0f);
    }

    void save(SharedPreferences p) {
        p.edit()
                .putString("theme", theme)
                .putInt("units", units)
                .putInt("tol", tolIdx)
                .putInt("lock", lock)
                .putBoolean("haptics", haptics)
                .putBoolean("keepOn", keepOn)
                .putBoolean("calibrated", calibrated)
                .putFloat("bx", bx)
                .putFloat("by", by)
                .putFloat("bz", bz)
                .apply();
    }

    private static int clampIdx(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }
}
