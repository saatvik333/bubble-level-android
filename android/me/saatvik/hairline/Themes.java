package me.saatvik.hairline;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/** The themes an app offers: the built-ins, then Wallpaper (Material You colours) on Android 12+. */
public final class Themes {
    private Themes() {
    }

    public static Palette[] all(Context c) {
        Palette wall = wallpaper(c);
        int n = Palette.BUILT_IN.length;
        Palette[] all = new Palette[wall != null ? n + 1 : n];
        System.arraycopy(Palette.BUILT_IN, 0, all, 0, n);
        if (wall != null) all[n] = wall;
        return all;
    }

    /**
     * The system's wallpaper-derived palette for the current day/night mode, null before Android 12.
     * Read once per activity: a night-mode change recreates the activity.
     */
    public static Palette wallpaper(Context c) {
        if (Build.VERSION.SDK_INT < 31) return null;
        boolean night = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            return new Palette("Wallpaper", true,
                    c.getColor(android.R.color.system_neutral1_900), c.getColor(android.R.color.system_neutral1_100),
                    c.getColor(android.R.color.system_neutral2_400), c.getColor(android.R.color.system_neutral2_700),
                    c.getColor(android.R.color.system_accent1_200), c.getColor(android.R.color.system_accent1_800));
        }
        return new Palette("Wallpaper", false,
                c.getColor(android.R.color.system_neutral1_10), c.getColor(android.R.color.system_neutral1_800),
                c.getColor(android.R.color.system_neutral2_600), c.getColor(android.R.color.system_neutral2_100),
                c.getColor(android.R.color.system_accent1_600), c.getColor(android.R.color.system_accent1_0));
    }

    /** Index of the theme called {@code name}; 0 (the first) when there is none, e.g. Wallpaper after a downgrade. */
    public static int indexOf(Palette[] themes, String name) {
        for (int i = 0; i < themes.length; i++) if (themes[i].name.equals(name)) return i;
        return 0;
    }
}
