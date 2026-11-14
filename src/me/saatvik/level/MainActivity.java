package me.saatvik.level;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Display;

import java.io.File;

import me.saatvik.hairline.Chrome;

public final class MainActivity extends Activity implements SensorEventListener, LevelView.Host {

    private SensorManager sensors;
    private Sensor gravity, gyro;
    private boolean gyroRegistered;
    private SharedPreferences prefs;
    private final Settings settings = new Settings();
    private final LevelEngine engine = new LevelEngine();
    private LevelView view;
    private Display display;

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

        view = new LevelView(this, new Renderer(Chrome.font(getAssets(), 300), Chrome.font(getAssets(), 400)),
                engine, settings, this);
        setContentView(view);
        Chrome.edgeToEdge(getWindow());
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

    /** Before Android 13: legacy back dispatch. From 13 the view registers for back while it has something to close. */
    @Override
    public void onBackPressed() {
        if (!view.back()) super.onBackPressed();
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
}
