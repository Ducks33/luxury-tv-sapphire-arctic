package com.esamyo.luxurytv;

import android.service.dreams.DreamService;

/**
 * The almanac screensaver (photo slideshow with clock and weather). Pick it in Settings → Screensaver, or
 * `settings put secure screensaver_components com.esamyo.luxurytv/.AlmanacDream` over adb.
 */
public class AlmanacDream extends DreamService {
    private AlmanacView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV
        setFullscreen(true);
        setScreenBright(true);
        view = new AlmanacView(this);
        setContentView(view);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        view.start();
    }

    @Override
    public void onDreamingStopped() {
        view.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) view.stop();
        super.onDetachedFromWindow();
    }
}
