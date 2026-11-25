package com.esamyo.luxurytv;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The screensaver's photo slideshow, streamed from online feeds: Windows Spotlight, Bing daily, Wikimedia
 * Picture of the Day, GNOME 44 and KDE Plasma wallpapers. Photos are downloaded into memory only; nothing is
 * written to the TV. A new photo every photoMs; the next source every sourceMs.
 */
final class PhotoFeeds {
    private static final String TAG = "LuxuryTV.PhotoFeeds";

    static final class Photo {
        final String url, title, credit;

        Photo(String url, String title, String credit) {
            this.url = url;
            this.title = title;
            this.credit = credit;
        }
    }

    interface Callback {
        void onPhoto(Bitmap bitmap, Photo photo, String source, int sourceNumber, int sourceCount);
    }

    private static final String[] GNOME = {"adwaita", "design-is-rounded-rectangles", "fold", "keys", "pills",
            "pixels", "symbolic", "truchet", "vnc"};
    private static final String[] KDE = {"Altai", "Autumn", "BytheWater", "Canopee", "Cascade", "Cluster", "Coast",
            "ColdRipple", "ColorfulCups", "DarkestHour", "Elarun", "EveningGlow", "FallenLeaf", "Flow", "FlyingKonqui",
            "Grey", "Honeywave", "IceCold", "Kay", "Kite", "Kokkini", "MilkyWay", "Mountain", "Nexus", "Nuvole",
            "OneStandsOut", "Opal", "Orionids", "PastelHills", "Patak", "Path", "SafeLanding", "ScarletTree", "Shell",
            "Sub-Arctic", "Volna", "Waterfall", "summer_1am"};

    private final List<String> order;
    private final long photoMs, sourceMs;
    private final int targetW, targetH;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<Photo>> lists = new HashMap<>();
    private final Map<String, Long> listTime = new HashMap<>();
    private final Random random = new Random();
    private Callback callback;
    private int sourceIndex, photoIndex, failures;
    private long sourceStarted;
    private boolean running;

    PhotoFeeds(List<String> order, long photoMs, long sourceMs, int targetW, int targetH) {
        this.order = new ArrayList<>(order);
        this.photoMs = photoMs;
        this.sourceMs = sourceMs;
        this.targetW = targetW;
        this.targetH = targetH;
    }

    void start(Callback cb) {
        callback = cb;
        running = true;
        sourceIndex = 0;
        sourceStarted = System.currentTimeMillis();
        photoIndex = -1;
        next();
    }

    void stop() {
        running = false;
        main.removeCallbacksAndMessages(null);
    }

    private final Runnable tick = this::next;

    private void next() {
        if (!running || order.isEmpty()) return;
        if (System.currentTimeMillis() - sourceStarted >= sourceMs) advanceSource();
        String source = order.get(sourceIndex % order.size());
        int number = sourceIndex % order.size() + 1;
        LiveArt.IO.execute(() -> {
            Bitmap bmp = null;
            Photo photo = null;
            try {
                List<Photo> list = list(source);
                if (!list.isEmpty()) {
                    if (photoIndex < 0) photoIndex = random.nextInt(list.size());
                    else photoIndex = (photoIndex + 1) % list.size();
                    photo = list.get(photoIndex);
                    bmp = LiveArt.decode(LiveArt.Net.bytes(photo.url, 24 << 20), targetW, targetH);
                }
            } catch (Exception e) {
                Log.w(TAG, source + ": " + e.getMessage());
            }
            Bitmap fb = bmp;
            Photo fp = photo;
            main.post(() -> {
                if (!running) return;
                if (fb == null) {
                    // This source failed: move on to the next one straight away (after a short pause on repeats).
                    failures++;
                    advanceSource();
                    main.postDelayed(tick, failures > order.size() ? 60_000 : 500);
                    return;
                }
                failures = 0;
                callback.onPhoto(fb, fp, source, number, order.size());
                main.postDelayed(tick, photoMs);
            });
        });
    }

    private void advanceSource() {
        sourceIndex++;
        sourceStarted = System.currentTimeMillis();
        photoIndex = -1;
    }

    /** The source's photo list, refreshed when it is more than 6 hours old. Runs on the IO thread. */
    private List<Photo> list(String source) throws Exception {
        Long t = listTime.get(source);
        List<Photo> cached = lists.get(source);
        if (cached != null && t != null && System.currentTimeMillis() - t < 6 * 3_600_000L) return cached;
        List<Photo> fresh;
        switch (source) {
            case "spotlight": fresh = spotlight(); break;
            case "bing": fresh = bing(); break;
            case "wikimedia": fresh = wikimedia(); break;
            case "gnome": fresh = gnome(); break;
            case "kde": fresh = kde(); break;
            default: fresh = new ArrayList<>();
        }
        if (!fresh.isEmpty()) {
            lists.put(source, fresh);
            listTime.put(source, System.currentTimeMillis());
            return fresh;
        }
        return cached != null ? cached : fresh;
    }

    private static List<Photo> spotlight() throws Exception {
        List<Photo> out = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            JSONObject r = new JSONObject(LiveArt.Net.text("https://fd.api.iris.microsoft.com/v4/api/selection"
                    + "?placement=88000820&bcnt=4&country=US&locale=en-US&fmt=json"));
            JSONArray items = r.getJSONObject("batchrsp").getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject ad = new JSONObject(items.getJSONObject(i).getString("item")).getJSONObject("ad");
                String url = ad.getJSONObject("landscapeImage").getString("asset");
                boolean dup = false;
                for (Photo p : out) dup |= p.url.equals(url);
                if (!dup) out.add(new Photo(url, ad.optString("title"), "Windows Spotlight · " + ad.optString("copyright")));
            }
        }
        return out;
    }

    private static List<Photo> bing() throws Exception {
        List<Photo> out = new ArrayList<>();
        JSONArray imgs = new JSONObject(LiveArt.Net.text(
                "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US")).getJSONArray("images");
        for (int i = 0; i < imgs.length(); i++) {
            JSONObject im = imgs.getJSONObject(i);
            out.add(new Photo("https://www.bing.com" + im.getString("urlbase") + "_1920x1080.jpg",
                    im.optString("title"), "Bing daily"));
        }
        return out;
    }

    private static List<Photo> wikimedia() {
        List<Photo> out = new ArrayList<>();
        SimpleDateFormat f = new SimpleDateFormat("yyyy/MM/dd", Locale.US);
        Calendar day = Calendar.getInstance();
        for (int i = 0; i < 7; i++) {
            try {
                JSONObject img = new JSONObject(LiveArt.Net.text(
                        "https://api.wikimedia.org/feed/v1/wikipedia/en/featured/" + f.format(day.getTime())))
                        .optJSONObject("image");
                if (img != null && img.optJSONObject("thumbnail") != null) {
                    String url = img.getJSONObject("thumbnail").getString("source")
                            .replaceAll("/\\d+px-", "/1920px-").replaceAll("\\?.*$", "");
                    String title = img.optJSONObject("description") != null
                            ? img.getJSONObject("description").optString("text") : img.optString("title");
                    title = title.replaceAll("\\s+", " ").trim();
                    if (title.length() > 60) title = title.substring(0, 57).trim() + "…";
                    out.add(new Photo(url, title, "Wikimedia Commons · Picture of the Day"));
                }
            } catch (Exception e) {
                Log.w(TAG, "wikimedia day " + i + ": " + e.getMessage());
            }
            day.add(Calendar.DAY_OF_MONTH, -1);
        }
        return out;
    }

    private static List<Photo> gnome() {
        List<Photo> out = new ArrayList<>();
        for (String n : GNOME) {
            for (String v : new String[]{"-d", "-l"}) {
                out.add(new Photo("https://gitlab.gnome.org/GNOME/gnome-backgrounds/-/raw/44.0/backgrounds/" + n + v + ".webp",
                        pretty(n) + (v.equals("-d") ? " (dark)" : " (light)"), "GNOME Backgrounds · CC BY-SA"));
            }
        }
        Collections.shuffle(out);
        return out;
    }

    /** KDE wallpapers: each folder's largest landscape image, looked up through the KDE GitLab API. */
    private List<Photo> kde() {
        List<Photo> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Collections.addAll(names, KDE);
        Collections.shuffle(names);
        for (String n : names) {
            if (out.size() >= 8) break;   // enough for one 30-minute turn; the list refreshes later
            try {
                JSONArray files = new JSONArray(LiveArt.Net.text("https://invent.kde.org/api/v4/projects/"
                        + "plasma%2Fplasma-workspace-wallpapers/repository/tree?path=" + n + "/contents/images"));
                String best = null;
                int bestW = 0;
                for (int i = 0; i < files.length(); i++) {
                    String f = files.getJSONObject(i).getString("name");
                    String[] wh = f.replaceAll("\\.[a-z]+$", "").split("x");
                    if (wh.length != 2) continue;
                    int w = Integer.parseInt(wh[0]), h = Integer.parseInt(wh[1]);
                    if (w <= h) continue;   // landscape only
                    // Closest to 4K without being much bigger: smaller downloads.
                    int score = w > 3840 ? 3840 - (w - 3840) : w;
                    if (score > bestW) {
                        bestW = score;
                        best = f;
                    }
                }
                if (best != null) {
                    out.add(new Photo("https://invent.kde.org/plasma/plasma-workspace-wallpapers/-/raw/master/" + n
                            + "/contents/images/" + best, pretty(n), "KDE Plasma wallpapers"));
                }
            } catch (Exception e) {
                Log.w(TAG, "kde " + n + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static String pretty(String s) {
        String t = s.replace('-', ' ').replace('_', ' ').replaceAll("([a-z])([A-Z])", "$1 $2");
        return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }
}
