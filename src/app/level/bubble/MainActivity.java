package app.level.bubble;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

public final class MainActivity extends Activity implements SensorEventListener, LevelView.Host {

    private SensorManager sensors;
    private Sensor gravity, gyro;
    private boolean gyroRegistered;
    private SharedPreferences prefs;
    private final Settings settings = new Settings();
    private final LevelEngine engine = new LevelEngine();
    private LevelView view;
    private Display display;
    private Object backCallback;
    private boolean backRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        display = getWindowManager().getDefaultDisplay();
        prefs = getSharedPreferences("level", MODE_PRIVATE);
        settings.load(prefs);
        engine.lock = settings.lock;
        if (settings.calibrated) {
            engine.bx = settings.bx;
            engine.by = settings.by;
            engine.bz = settings.bz;
        }

        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) {
            gravity = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (gravity == null) gravity = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }

        Typeface light, regular;
        try {
            light = Typeface.createFromAsset(getAssets(), "fonts/rubik_300.ttf");
            regular = Typeface.createFromAsset(getAssets(), "fonts/rubik_400.ttf");
        } catch (RuntimeException e) {
            light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            regular = Typeface.create("sans-serif", Typeface.NORMAL);
        }

        view = new LevelView(this, new Renderer(light, regular), engine, settings, themes(), this);
        setContentView(view);
        setupWindow();
        onThemeApplied(view.currentTarget());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gravity != null) sensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME);
        view.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.pause();
        if (sensors != null) sensors.unregisterListener(this);
        gyroRegistered = false;
        settings.save(prefs);
    }

    /** Android 15 and below: legacy back dispatch. */
    @Override
    public void onBackPressed() {
        if (!view.handleBack()) super.onBackPressed();
    }

    /**
     * Android 16+ with targetSdk 36 no longer calls onBackPressed. A callback is registered only
     * while the view has something to close (settings page, calibration), so at the top level the
     * system keeps its predictive back-to-home animation. OnBackInvokedCallback (API 33) is not in
     * the API 23 stubs this compiles against, hence the dynamic proxy.
     */
    @Override
    public void setBackIntercept(boolean on) {
        if (Build.VERSION.SDK_INT < 36 || on == backRegistered) return;
        try {
            Class<?> cbType = Class.forName("android.window.OnBackInvokedCallback");
            Class<?> dispType = Class.forName("android.window.OnBackInvokedDispatcher");
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
            if (backCallback == null) {
                backCallback = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method m, Object[] args) {
                                switch (m.getName()) {
                                    case "onBackInvoked":
                                        if (!view.handleBack()) setBackIntercept(false);
                                        return null;
                                    case "equals":
                                        return proxy == args[0];
                                    case "hashCode":
                                        return System.identityHashCode(proxy);
                                    case "toString":
                                        return "LevelBackCallback";
                                    default:
                                        return null;
                                }
                            }
                        });
            }
            if (on) {
                dispType.getMethod("registerOnBackInvokedCallback", int.class, cbType)
                        .invoke(dispatcher, 0 /* PRIORITY_DEFAULT */, backCallback);
            } else {
                dispType.getMethod("unregisterOnBackInvokedCallback", cbType).invoke(dispatcher, backCallback);
            }
            backRegistered = on;
        } catch (Exception ignored) {
            // leaves the platform default: back closes the activity
        }
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        view.requestLayout();
    }

    // ---- sensors -----------------------------------------------------------------------------

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_GRAVITY || type == Sensor.TYPE_ACCELEROMETER) {
            // Cheap cached read; also catches 90 -> 270 flips, which raise no configuration change.
            engine.rotation = display.getRotation();
            engine.onGravity(e.values[0], e.values[1], e.values[2], e.timestamp);
            view.onSensor();
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            engine.onGyro(e.values[0], e.values[1], e.values[2], e.timestamp);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void setGyroActive(boolean on) {
        if (gyro == null || sensors == null) return;
        if (on && !gyroRegistered) {
            gyroRegistered = sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        } else if (!on && gyroRegistered) {
            sensors.unregisterListener(this, gyro);
            gyroRegistered = false;
        }
    }

    // ---- host callbacks ----------------------------------------------------------------------

    @Override
    public void onSettingsChanged() {
        settings.save(prefs);
    }

    @Override
    public void onThemeApplied(Palette p) {
        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(p.bg));
        boolean lightBars = !p.dark;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Object ctl = Window.class.getMethod("getInsetsController").invoke(w);
                if (ctl != null) {
                    int mask = 8 | 16;   // APPEARANCE_LIGHT_STATUS_BARS | APPEARANCE_LIGHT_NAVIGATION_BARS
                    Method m = ctl.getClass().getMethod("setSystemBarsAppearance", int.class, int.class);
                    m.invoke(ctl, lightBars ? mask : 0, mask);
                    return;
                }
            } catch (Exception ignored) {
                // fall through to legacy flags
            }
        }
        View decor = w.getDecorView();
        int f = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? 0x10 : 0);
        decor.setSystemUiVisibility(lightBars ? (f | light) : (f & ~light));
    }

    // ---- window ------------------------------------------------------------------------------

    private void setupWindow() {
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (Build.VERSION.SDK_INT < 35) {
            w.setStatusBarColor(0);
            w.setNavigationBarColor(0);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            invoke(w, "setNavigationBarContrastEnforced", boolean.class, false);
            invoke(w, "setStatusBarContrastEnforced", boolean.class, false);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WindowManager.LayoutParams lp = w.getAttributes();
                WindowManager.LayoutParams.class.getField("layoutInDisplayCutoutMode").setInt(lp, 1); // SHORT_EDGES
                w.setAttributes(lp);
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            invoke(w, "setDecorFitsSystemWindows", boolean.class, false);
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private static void invoke(Object target, String name, Class<?> type, Object arg) {
        try {
            target.getClass().getMethod(name, type).invoke(target, arg);
        } catch (Exception ignored) {
        }
    }

    // ---- themes ------------------------------------------------------------------------------

    private Palette[] themes() {
        ArrayList<Palette> list = new ArrayList<>();
        for (Palette p : Palette.BUILT_IN) list.add(p);
        Palette dyn = wallpaperPalette();
        if (dyn != null) list.add(dyn);
        return list.toArray(new Palette[0]);
    }

    /** Material You colours on Android 12+, read by name so the app compiles against API 23. */
    private Palette wallpaperPalette() {
        if (Build.VERSION.SDK_INT < 31) return null;
        try {
            boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            if (night) {
                return new Palette("Wallpaper", true,
                        sys("system_neutral1_900"), sys("system_neutral1_100"), sys("system_neutral2_400"),
                        sys("system_neutral2_700"), sys("system_accent1_200"), sys("system_accent1_800"));
            }
            return new Palette("Wallpaper", false,
                    sys("system_neutral1_10"), sys("system_neutral1_800"), sys("system_neutral2_500"),
                    sys("system_neutral2_100"), sys("system_accent1_600"), sys("system_accent1_0"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int sys(String name) {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier(name, "color", "android");
        if (id == 0) throw new Resources.NotFoundException(name);
        return getColor(id);
    }
}
