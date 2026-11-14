package me.saatvik.hairline;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** The window around a single full-screen custom view: edge to edge, bars in the theme's colours, safe insets, back, fonts. */
public final class Chrome {
    private Chrome() {
    }

    /**
     * Draws under transparent system bars and the display cutout, without the scrims Android adds
     * for contrast (the app's own background is the bar background). Call once, after setContentView.
     */
    public static void edgeToEdge(Window w) {
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT < 35) {             // 35+ draws edge to edge and ignores bar colours
            w.setStatusBarColor(0);
            w.setNavigationBarColor(0);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * Window background and bar icon colours for a theme: dark icons on light themes. Call when
     * the theme changes, and again from Activity.onAttachedToWindow: Android 11 can drop bar
     * appearance set before the window is attached.
     */
    public static void theme(Window w, Palette p) {
        w.setBackgroundDrawable(new ColorDrawable(p.bg));
        boolean lightBars = !p.dark;
        if (Build.VERSION.SDK_INT < 26) {
            // No dark navigation icons before 8.0: give the white ones a scrim on light themes.
            w.setNavigationBarColor(lightBars ? 0x66000000 : 0);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ctl = w.getInsetsController();
            if (ctl != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctl.setSystemBarsAppearance(lightBars ? mask : 0, mask);
                if (Build.VERSION.SDK_INT > 30) return;
                // Android 11 still honours the legacy flags; keep them in agreement
            }
        }
        View decor = w.getDecorView();
        int f = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        decor.setSystemUiVisibility(lightBars ? (f | light) : (f & ~light));
    }

    /** Insets content must avoid: system bars and display cutout (the deprecated getters leave the cutout out). */
    public static void insets(WindowInsets in, Rect out) {
        if (Build.VERSION.SDK_INT >= 30) {
            Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            out.set(i.left, i.top, i.right, i.bottom);
            return;
        }
        out.set(in.getSystemWindowInsetLeft(), in.getSystemWindowInsetTop(),
                in.getSystemWindowInsetRight(), in.getSystemWindowInsetBottom());
        DisplayCutout cut = Build.VERSION.SDK_INT >= 28 ? in.getDisplayCutout() : null;
        if (cut != null) {
            out.left = Math.max(out.left, cut.getSafeInsetLeft());
            out.top = Math.max(out.top, cut.getSafeInsetTop());
            out.right = Math.max(out.right, cut.getSafeInsetRight());
            out.bottom = Math.max(out.bottom, cut.getSafeInsetBottom());
        }
    }

    /** Rubik at weight 300 or 400 from the library's assets (fonts/rubik_WEIGHT.ttf), the system sans-serif if it is missing. */
    public static Typeface font(AssetManager assets, int weight) {
        try {
            return Typeface.createFromAsset(assets, "fonts/rubik_" + weight + ".ttf");
        } catch (RuntimeException e) {
            return Typeface.create(weight < 400 ? "sans-serif-light" : "sans-serif", Typeface.NORMAL);
        }
    }

    /**
     * Predictive back. From Android 13 (with android:enableOnBackInvokedCallback="true" in the
     * manifest, which apps set) Activity.onBackPressed is no longer called; a callback is registered
     * only while something can close, so at the top level the system keeps its back-to-home
     * animation. Below 13 the activity forwards onBackPressed to the same {@code back()}.
     */
    public static final class Back {
        private final Activity activity;
        private final Access.Nodes target;
        private OnBackInvokedCallback callback;
        private boolean registered;

        public Back(Activity activity, Access.Nodes target) {
            this.activity = activity;
            this.target = target;
        }

        /** Whether back should reach the app; cheap to call every frame. */
        public void intercept(boolean on) {
            if (Build.VERSION.SDK_INT < 33 || on == registered) return;
            OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            if (callback == null) {
                callback = new OnBackInvokedCallback() {
                    @Override
                    public void onBackInvoked() {
                        if (!target.back()) intercept(false);
                    }
                };
            }
            if (on) dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            else dispatcher.unregisterOnBackInvokedCallback(callback);
            registered = on;
        }
    }
}
