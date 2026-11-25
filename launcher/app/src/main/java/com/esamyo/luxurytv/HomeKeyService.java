package com.esamyo.luxurytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Remote buttons for TCL Google TV, where Home always goes to Google TV's own launcher (set-home-activity is
 * ignored, and disabling the Google TV launcher sends setupwraith's RecoveryActivity into a restart loop).
 *
 * It does not filter keys: asking Android to filter them stopped the TV repeating held buttons (volume could
 * only move one step per press). Instead it watches which app comes to the front (package and screen class
 * only, never screen content):
 *   - Google TV's home in front (the Home button, Back out of an app, wake-up) → this launcher;
 *   - YouTube in front, unless this launcher opened it from its tile a moment ago (so: the remote's YouTube
 *     button, which Google TV turns into "open YouTube") → the theme's youtube_button_app, if set;
 *   - a Settings screen in front → keep the screen awake, so the 15 s screensaver does not cover it.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.esamyo.luxurytv/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "LuxuryTV.Keys";
    private static final String STOCK = "com.google.android.apps.tv.launcherx";
    static final String YOUTUBE = "com.google.android.youtube.tv";

    /** True while Android has this service bound (BootRepair and the home screen check it). */
    static volatile boolean running;

    /** The app last brought to the front (package name only; nothing about what is on screen). */
    static volatile String frontPkg = "";

    /** Set by the home screen when its YouTube tile opens YouTube, so that one is not redirected. */
    static volatile long youtubeFromTile;

    private long lastHome, lastTube;

    /**
     * The service starts after boot and after each launcher update, when Google TV's home is usually already on
     * screen (so no window change will arrive). Open this launcher then.
     */
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        openHome();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    private android.os.PowerManager.WakeLock settingsLock;

    /**
     * Keeps the screen on while a Settings screen is in front, so the 15-second screensaver does not cover
     * (and close) things like the wireless debugging pairing code. Released as soon as another app is in front.
     */
    @SuppressWarnings("deprecation")
    private void keepAwakeInSettings(boolean on) {
        if (settingsLock == null) {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            settingsLock = pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "LuxuryTV:settings");
            settingsLock.setReferenceCounted(false);
        }
        if (on && !settingsLock.isHeld()) settingsLock.acquire(30 * 60 * 1000L);   // at most 30 min
        else if (!on && settingsLock.isHeld()) settingsLock.release();
    }

    @Override
    public void onDestroy() {
        if (settingsLock != null && settingsLock.isHeld()) settingsLock.release();
        running = false;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        String k = event.getClassName() == null ? "" : event.getClassName().toString();
        // Screens only: panels and popups (plain android.widget/android.view windows) are not an app in front.
        boolean screen = !k.startsWith("android.widget.") && !k.startsWith("android.view.") && !k.startsWith("android.app.Dialog");
        if (!screen || p.equals("com.android.systemui") || p.equals("android")) return;
        String before = frontPkg;
        frontPkg = p;
        keepAwakeInSettings(p.contains("settings"));

        if (p.equals(STOCK)) {
            if (k.endsWith(".HomeActivity") || k.endsWith(".MainActivity")) openHome();
        } else if (p.equals(YOUTUBE) && !before.equals(YOUTUBE)) {
            if (System.currentTimeMillis() - youtubeFromTile > 8000) openYoutubeButtonApp();
        }
    }

    private void openHome() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHome < 400) return;
        lastHome = now;
        startActivity(new Intent(this, CoverFlowActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    private void openYoutubeButtonApp() {
        String app = Prefs.load(this).youtubeButtonApp;
        if (app.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastTube < 1500) return;
        lastTube = now;
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(app);
        if (i == null) i = pm.getLaunchIntentForPackage(app);
        if (i == null) {
            Log.w(TAG, app + " not installed");
            return;
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException e) {
            Log.w(TAG, app + ": " + e);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
