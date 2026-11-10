package me.saatvik.level;

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
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.File;
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
    private OnBackInvokedCallback backCallback;
    private boolean backRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        display = getWindowManager().getDefaultDisplay();
        prefs = getSharedPreferences("level", MODE_PRIVATE);
        settings.load(prefs);
        settings.loadCalibration(calibrationFile());
        engine.lock = settings.lock;
        System.arraycopy(settings.surf, 0, engine.surf, 0, 4);
        System.arraycopy(settings.edge, 0, engine.edge, 0, 4);
        engine.calMask = settings.calMask;
        engine.rotation = display.getRotation();

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

    /** Before Android 13: legacy back dispatch. */
    @Override
    public void onBackPressed() {
        if (!view.handleBack()) super.onBackPressed();
    }

    /**
     * Android 13+ (enableOnBackInvokedCallback): onBackPressed is no longer called. A callback is
     * registered only while the view has something to close (settings page, calibration), so at
     * the top level the system keeps its predictive back-to-home animation.
     */
    @Override
    public void setBackIntercept(boolean on) {
        if (Build.VERSION.SDK_INT < 33 || on == backRegistered) return;
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (backCallback == null) {
            backCallback = () -> {
                if (!view.handleBack()) setBackIntercept(false);
            };
        }
        if (on) {
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        } else {
            dispatcher.unregisterOnBackInvokedCallback(backCallback);
        }
        backRegistered = on;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        engine.rotation = display.getRotation();
        engine.refresh();
        view.requestLayout();
        view.invalidate();
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
    public boolean onCalibrationChanged() {
        return settings.saveCalibration(calibrationFile());
    }

    /** Excluded from cloud backup and device-to-device transfer: an offset is specific to one sensor. */
    private File calibrationFile() {
        return new File(getNoBackupFilesDir(), "calibration");
    }

    @Override
    public void onThemeApplied(Palette p) {
        Window w = getWindow();
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
                return;
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

    // ---- themes ------------------------------------------------------------------------------

    private Palette[] themes() {
        ArrayList<Palette> list = new ArrayList<>();
        for (Palette p : Palette.BUILT_IN) list.add(p);
        Palette dyn = wallpaperPalette();
        if (dyn != null) list.add(dyn);
        return list.toArray(new Palette[0]);
    }

    /** Material You colours on Android 12+. */
    private Palette wallpaperPalette() {
        if (Build.VERSION.SDK_INT < 31) return null;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            return new Palette("Wallpaper", true,
                    getColor(android.R.color.system_neutral1_900), getColor(android.R.color.system_neutral1_100),
                    getColor(android.R.color.system_neutral2_400), getColor(android.R.color.system_neutral2_700),
                    getColor(android.R.color.system_accent1_200), getColor(android.R.color.system_accent1_800));
        }
        return new Palette("Wallpaper", false,
                getColor(android.R.color.system_neutral1_10), getColor(android.R.color.system_neutral1_800),
                getColor(android.R.color.system_neutral2_600), getColor(android.R.color.system_neutral2_100),
                getColor(android.R.color.system_accent1_600), getColor(android.R.color.system_accent1_0));
    }
}
