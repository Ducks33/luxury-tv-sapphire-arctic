package com.esamyo.luxurytv;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to other apps' media sessions (video and music apps) so the home screen can show what each
 * one last played as its live background. Android only lets an enabled notification listener see media sessions;
 * it is enabled once over adb (`cmd notification allow_listener`). Notifications themselves are ignored.
 */
public class LiveArtListener extends NotificationListenerService {
    private static final String TAG = "LuxuryTV.LiveArtListener";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaController, MediaController.Callback> watched = new HashMap<>();
    private MediaSessionManager msm;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::track;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        try {
            msm = getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(this, LiveArtListener.class);
            msm.addOnActiveSessionsChangedListener(sessionsChanged, me, handler);
            track(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "no media session access: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        for (Map.Entry<MediaController, MediaController.Callback> e : watched.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        watched.clear();
        super.onListenerDisconnected();
    }

    private void track(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController mc : controllers) {
            boolean known = false;
            for (MediaController w : watched.keySet()) {
                if (w.getSessionToken().equals(mc.getSessionToken())) {
                    known = true;
                    break;
                }
            }
            if (known) continue;
            String pkg = mc.getPackageName();
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    LiveArt.saveFromSession(LiveArtListener.this, pkg, metadata);
                }

                @Override
                public void onSessionDestroyed() {
                    mc.unregisterCallback(this);
                    watched.remove(mc);
                }
            };
            mc.registerCallback(cb, handler);
            watched.put(mc, cb);
            LiveArt.saveFromSession(this, pkg, mc.getMetadata());
        }
    }
}
