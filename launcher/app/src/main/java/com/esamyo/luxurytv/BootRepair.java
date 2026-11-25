package com.esamyo.luxurytv;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Runs at boot (TclBootInput is what gets TCL to let it) and puts back what a TCL restart takes away:
 *   - the Home helper (HomeKeyService), which TCL leaves unbound: written off, then on, which is what makes
 *     Android bind it again;
 *   - Tailscale, which TCL also blocks at boot: asked to connect through its documented CONNECT_VPN receiver;
 *   - Wireless debugging, which Android switches off at every restart, so the TV stays reachable for upkeep
 *     from the paired PC. It still needs that PC's pairing key; nothing new is opened to the network.
 * The settings writes need WRITE_SECURE_SETTINGS, granted once over adb; without it they are skipped.
 */
public class BootRepair extends BroadcastReceiver {
    private static final String TAG = "LuxuryTV.Boot";
    static final String HELPER = "com.esamyo.luxurytv/.HomeKeyService";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        h.post(() -> {
            repairHelper(app);
            connectTailscale(app);
            enableWirelessDebugging(app);
        });
        // Give the helper a moment to bind; it opens the home screen itself in onServiceConnected.
        h.postDelayed(() -> {
            if (!HomeKeyService.running) repairHelper(app);
            pending.finish();
        }, 6000);
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Re-enables the Home helper unless it is running or was switched off on purpose (not listed at all). */
    static void repairHelper(Context c) {
        if (HomeKeyService.running || !canWrite(c)) return;
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (list == null || !list.contains("HomeKeyService")) return;
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "");
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list);
                    Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                    Log.w(TAG, "Home helper switched back on");
                } catch (RuntimeException e) {
                    Log.w(TAG, "helper on: " + e);
                }
            }, 2000);
        } catch (RuntimeException e) {
            Log.w(TAG, "helper: " + e);
        }
    }

    private static void connectTailscale(Context c) {
        try {
            c.sendBroadcast(new Intent("com.tailscale.ipn.CONNECT_VPN")
                    .setComponent(new ComponentName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")));
            Log.w(TAG, "asked Tailscale to connect");
        } catch (RuntimeException e) {
            Log.w(TAG, "tailscale: " + e);
        }
    }

    private static void enableWirelessDebugging(Context c) {
        if (!canWrite(c)) return;
        try {
            Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
            Log.w(TAG, "wireless debugging on");
        } catch (RuntimeException e) {
            Log.w(TAG, "adb wifi: " + e);
        }
    }
}
