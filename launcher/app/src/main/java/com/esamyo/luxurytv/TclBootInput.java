package com.esamyo.luxurytv;

import android.media.tv.TvInputService;

/**
 * An empty TV input: it has no channels, never opens a session and never appears as a source.
 *
 * TCL's boot manager (TclAppBoot) refuses to start apps at boot unless they hold its AUTO_START app-op, and it
 * resets that op to "ignore" on every boot, so the Home helper, the live-art listener and BOOT_COMPLETED never
 * run after a restart. Apps that provide a TV input are always let through, so declaring one gets this launcher
 * started at boot. Confirmed on several TCL Google TVs by the couch-tv project (Seagey/couch-tv#94); written here
 * from the Android TvInputService docs.
 */
public class TclBootInput extends TvInputService {
    @Override
    public Session onCreateSession(String inputId) {
        return null;
    }
}
