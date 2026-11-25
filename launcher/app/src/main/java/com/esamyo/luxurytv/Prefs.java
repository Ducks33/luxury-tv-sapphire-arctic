package com.esamyo.luxurytv;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Settings for the Sapphire Arctic home and the almanac screensaver, read from the theme's config.json on top of
 * Config (config.xml). Every key is optional; see themes/sapphire-arctic/config.json for the full set.
 */
final class Prefs {
    final Config cfg;
    /** Labels and highlights (accent_color), the focus ring (ring_color) and the clock's gradient. */
    final int accent, ring;
    final int[] clockGradient;
    final boolean fahrenheit;
    /** "12" or "24" from clock_format; "system" follows the TV. */
    final String clockFormat;
    /** Package focused when Home opens (default_focus); null = the first app. */
    final String defaultFocus;
    final boolean liveBackground;
    final long liveDelayMs;
    /** Screensaver photo feeds in rotation order: spotlight, bing, wikimedia, gnome, kde. */
    final List<String> feeds;
    final long photoMs, sourceMs;
    final String latitude, longitude;
    /** App the remote's YouTube button opens instead of YouTube (youtube_button_app); empty = leave YouTube. */
    final String youtubeButtonApp;

    private Prefs(Context ctx) {
        cfg = Config.load(ctx);
        JSONObject j = Config.overlay(ctx);
        if (j == null) j = new JSONObject();
        accent = color(j, "accent_color", 0xFF8FD3FF);
        ring = color(j, "ring_color", 0xFFBDE6FF);
        clockGradient = new int[]{0xFFFFFFFF, 0xFFE3F4FF, 0xFF9FD6F6};
        fahrenheit = !"C".equalsIgnoreCase(j.optString("temperature_unit", "F"));
        clockFormat = cfg.clockFormat;
        h24 = "24".equals(clockFormat) || (!"12".equals(clockFormat) && android.text.format.DateFormat.is24HourFormat(ctx));
        String df = j.optString("default_focus", "").trim();
        defaultFocus = df.isEmpty() ? null : df;
        liveBackground = j.optBoolean("live_background", true);
        liveDelayMs = Math.max(300, j.optLong("live_background_delay_ms", 1500));
        List<String> f = new ArrayList<>();
        JSONArray a = j.optJSONArray("screensaver_feeds");
        if (a != null) for (int i = 0; i < a.length(); i++) f.add(a.optString(i).trim().toLowerCase());
        if (f.isEmpty()) f.addAll(Arrays.asList("spotlight", "bing", "wikimedia", "gnome", "kde"));
        feeds = f;
        // *_seconds (for testing) wins over *_minutes when present.
        photoMs = j.has("screensaver_photo_seconds") ? Math.max(5, j.optInt("screensaver_photo_seconds")) * 1000L
                : Math.max(1, j.optInt("screensaver_photo_minutes", 1)) * 60_000L;
        sourceMs = j.has("screensaver_source_seconds") ? Math.max(10, j.optInt("screensaver_source_seconds")) * 1000L
                : Math.max(1, j.optInt("screensaver_source_minutes", 30)) * 60_000L;
        String[] place = Config.place(ctx);
        latitude = place[0];
        longitude = place[1];
        youtubeButtonApp = j.optString("youtube_button_app", "").trim();
    }

    static Prefs load(Context ctx) {
        return new Prefs(ctx);
    }

    boolean hasPlace() {
        return !latitude.isEmpty() && !longitude.isEmpty();
    }

    /** 24-hour time: clock_format "24", or "system" on a TV set to 24-hour. Resolved once at load. */
    private boolean h24;

    boolean use24h(Context ignored) {
        return h24;
    }

    private static int color(JSONObject o, String key, int fallback) {
        String s = o.optString(key, "").trim();
        if (s.isEmpty()) return fallback;
        try {
            return Color.parseColor(s) | 0xFF000000;
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
