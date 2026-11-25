package com.esamyo.luxurytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Weather for the home bar and the almanac screensaver, from Open-Meteo (free, no account, no key) for the city in
 * the theme (latitude/longitude). The raw answers are cached in files/weather.json and refreshed every 30 minutes.
 */
final class Weather {
    private Weather() {}

    private static final String TAG = "LuxuryTV.Weather";
    static final long MAX_AGE_MS = 30 * 60_000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Day {
        String name;
        int code, hi, lo;
    }

    static final class Hour {
        String label;
        int code, temp, pop;
        boolean day;
    }

    static final class Data {
        long fetchedAt;
        String unit;          // "°F" or "°C"
        String speedUnit;     // "mph" or "km/h"
        int temp, feels, humidity, code, wind, rainPct, hi, lo, aqi = -1;
        boolean isDay;
        double uv, pressureIn;
        String windDir = "", sunrise = "", sunset = "", moon = "";
        final List<Day> days = new ArrayList<>();
        final List<Hour> hours = new ArrayList<>();

        String condition() {
            return Weather.condition(code, isDay);
        }
    }

    interface Listener {
        void onWeather(Data d);
    }

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "weather.json");
    }

    /** Answers at once with the cached weather (if any), then again after a refresh when the cache is old. */
    static void get(Context ctx, Prefs p, Listener l) {
        if (!p.hasPlace()) return;
        Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            JSONObject cached = readCache(app);
            Data d = cached == null ? null : parse(cached, p);
            if (d != null) post(l, d);
            boolean fresh = d != null && System.currentTimeMillis() - d.fetchedAt < MAX_AGE_MS
                    && cached.optString("place").equals(p.latitude + "," + p.longitude);
            if (fresh) return;
            try {
                String base = "latitude=" + p.latitude + "&longitude=" + p.longitude + "&timezone=auto";
                String units = p.fahrenheit ? "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch"
                        : "&wind_speed_unit=kmh";
                String fc = fetch("https://api.open-meteo.com/v1/forecast?" + base + units
                        + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,"
                        + "wind_speed_10m,wind_direction_10m,pressure_msl"
                        + "&hourly=temperature_2m,precipitation_probability,weather_code,is_day,uv_index"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset"
                        + "&forecast_days=6&forecast_hours=8");
                String aq = null;
                try {
                    aq = LiveArt.Net.text("https://air-quality-api.open-meteo.com/v1/air-quality?" + base + "&current=us_aqi");
                } catch (Exception e) {
                    Log.w(TAG, "air quality: " + e.getMessage());
                }
                JSONObject all = new JSONObject();
                all.put("fetchedAt", System.currentTimeMillis());
                all.put("place", p.latitude + "," + p.longitude);
                all.put("forecast", new JSONObject(fc));
                if (aq != null) all.put("air", new JSONObject(aq));
                try (FileOutputStream out = new FileOutputStream(cacheFile(app))) {
                    out.write(all.toString().getBytes(StandardCharsets.UTF_8));
                }
                Data nd = parse(all, p);
                if (nd != null) post(l, nd);
            } catch (Exception e) {
                Log.w(TAG, "refresh: " + e.getMessage());
            }
        });
    }

    /** Weather has its own thread so a slow Open-Meteo never holds up the live background. */
    private static final java.util.concurrent.ExecutorService NET = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Open-Meteo sometimes stalls for 30 s or more; try a few times before giving up until the next refresh. */
    private static String fetch(String url) throws Exception {
        Exception last = null;
        for (int i = 0; i < 3; i++) {
            try {
                return LiveArt.Net.text(url);
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "forecast attempt " + (i + 1) + ": " + e.getMessage());
                Thread.sleep(3000L * (i + 1));
            }
        }
        throw last;
    }

    private static void post(Listener l, Data d) {
        MAIN.post(() -> l.onWeather(d));
    }

    private static JSONObject readCache(Context c) {
        File f = cacheFile(c);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private static Data parse(JSONObject all, Prefs p) {
        try {
            JSONObject f = all.getJSONObject("forecast");
            Data d = new Data();
            d.fetchedAt = all.optLong("fetchedAt");
            d.unit = p.fahrenheit ? "°F" : "°C";
            d.speedUnit = p.fahrenheit ? "mph" : "km/h";
            JSONObject cur = f.getJSONObject("current");
            d.temp = (int) Math.round(cur.optDouble("temperature_2m"));
            d.feels = (int) Math.round(cur.optDouble("apparent_temperature"));
            d.humidity = (int) Math.round(cur.optDouble("relative_humidity_2m"));
            d.code = cur.optInt("weather_code");
            d.isDay = cur.optInt("is_day", 1) == 1;
            d.wind = (int) Math.round(cur.optDouble("wind_speed_10m"));
            d.windDir = compass(cur.optDouble("wind_direction_10m"));
            d.pressureIn = cur.optDouble("pressure_msl") * 0.0295300;
            JSONObject h = f.getJSONObject("hourly");
            JSONArray ht = h.getJSONArray("time"), htemp = h.getJSONArray("temperature_2m"),
                    hpop = h.getJSONArray("precipitation_probability"), hcode = h.getJSONArray("weather_code"),
                    hday = h.getJSONArray("is_day"), huv = h.optJSONArray("uv_index");
            d.rainPct = hpop.optInt(0);
            d.uv = huv == null ? 0 : huv.optDouble(0, 0);
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));   // times are local wall-clock; parse and print in UTC
            SimpleDateFormat hourFmt = new SimpleDateFormat(p.use24h(null) ? "H:00" : "h a", Locale.US);
            hourFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = 1; i < ht.length() && d.hours.size() < 6; i++) {
                Hour hr = new Hour();
                hr.label = hourFmt.format(iso.parse(ht.getString(i)));
                hr.temp = (int) Math.round(htemp.optDouble(i));
                hr.pop = hpop.optInt(i);
                hr.code = hcode.optInt(i);
                hr.day = hday.optInt(i, 1) == 1;
                d.hours.add(hr);
            }
            JSONObject dy = f.getJSONObject("daily");
            JSONArray dt = dy.getJSONArray("time"), dc = dy.getJSONArray("weather_code"),
                    dmax = dy.getJSONArray("temperature_2m_max"), dmin = dy.getJSONArray("temperature_2m_min"),
                    rise = dy.getJSONArray("sunrise"), set = dy.getJSONArray("sunset");
            d.hi = (int) Math.round(dmax.optDouble(0));
            d.lo = (int) Math.round(dmin.optDouble(0));
            SimpleDateFormat sunFmt = new SimpleDateFormat(p.use24h(null) ? "H:mm" : "h:mm a", Locale.US);
            sunFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
            d.sunrise = sunFmt.format(iso.parse(rise.getString(0))).toLowerCase(Locale.US);
            d.sunset = sunFmt.format(iso.parse(set.getString(0))).toLowerCase(Locale.US);
            SimpleDateFormat dayIso = new SimpleDateFormat("yyyy-MM-dd", Locale.US), dayName = new SimpleDateFormat("EEE", Locale.US);
            dayIso.setTimeZone(TimeZone.getTimeZone("UTC"));
            dayName.setTimeZone(TimeZone.getTimeZone("UTC"));
            for (int i = 1; i < dt.length() && d.days.size() < 5; i++) {
                Day day = new Day();
                day.name = dayName.format(dayIso.parse(dt.getString(i)));
                day.code = dc.optInt(i);
                day.hi = (int) Math.round(dmax.optDouble(i));
                day.lo = (int) Math.round(dmin.optDouble(i));
                d.days.add(day);
            }
            JSONObject air = all.optJSONObject("air");
            if (air != null && air.optJSONObject("current") != null) {
                d.aqi = (int) Math.round(air.getJSONObject("current").optDouble("us_aqi", -1));
            }
            d.moon = moonPhase(new Date());
            return d;
        } catch (Exception e) {
            Log.w(TAG, "parse: " + e.getMessage());
            return null;
        }
    }

    static String condition(int code, boolean day) {
        if (code == 0) return day ? "Sunny" : "Clear";
        if (code == 1) return day ? "Mostly sunny" : "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    /** Icon kind for WxIcon. */
    static int icon(int code, boolean day) {
        if (code <= 1) return day ? WxIcon.SUN : WxIcon.MOON;
        if (code == 2) return day ? WxIcon.PART : WxIcon.NIGHT_CLOUD;
        if (code == 3) return WxIcon.CLOUD;
        if (code == 45 || code == 48) return WxIcon.FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return WxIcon.SNOW;
        if (code >= 95) return WxIcon.STORM;
        return WxIcon.RAIN;
    }

    static String uvLabel(double uv) {
        int u = (int) Math.round(uv);
        String w = u <= 2 ? "Low" : u <= 5 ? "Moderate" : u <= 7 ? "High" : u <= 10 ? "Very high" : "Extreme";
        return u + " " + w;
    }

    static String aqiLabel(int aqi) {
        if (aqi < 0) return "—";
        String w = aqi <= 50 ? "Good" : aqi <= 100 ? "Moderate" : aqi <= 150 ? "Sensitive" : aqi <= 200 ? "Unhealthy" : "Very poor";
        return aqi + " " + w;
    }

    private static String compass(double deg) {
        String[] n = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return n[(int) Math.round(((deg % 360) + 360) % 360 / 45.0) % 8];
    }

    /** Moon phase name, computed on the TV from a known new moon (6 Jan 2000 18:14 UTC). */
    static String moonPhase(Date when) {
        Calendar ref = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        ref.set(2000, Calendar.JANUARY, 6, 18, 14, 0);
        double days = (when.getTime() - ref.getTimeInMillis()) / 86_400_000.0;
        double age = ((days % 29.530588853) + 29.530588853) % 29.530588853;
        String[] names = {"New moon", "Waxing crescent", "First quarter", "Waxing gibbous",
                "Full moon", "Waning gibbous", "Last quarter", "Waning crescent"};
        return names[(int) Math.floor((age / 29.530588853) * 8 + 0.5) % 8];
    }
}
