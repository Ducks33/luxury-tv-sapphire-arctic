package com.esamyo.luxurytv;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The live background for an app: what it last played (from its media session, kept by LiveArtListener) or what it
 * offers in Android TV's Watch Next row. Results are cached in files/liveart/ so they survive a restart.
 */
final class LiveArt {
    private LiveArt() {}

    private static final String TAG = "LuxuryTV.LiveArt";
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    interface Callback {
        /** art may be null: nothing live for this app. */
        void onArt(String pkg, Bitmap art, String caption);
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "liveart");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Called by LiveArtListener when an app's media session reports new metadata. */
    static void saveFromSession(Context c, String pkg, MediaMetadata md) {
        if (md == null) return;
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String title = first(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE), md.getString(MediaMetadata.METADATA_KEY_TITLE));
        String who = first(md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                md.getString(MediaMetadata.METADATA_KEY_ARTIST), md.getString(MediaMetadata.METADATA_KEY_ALBUM));
        // Chromium/Cobalt-based apps often give the artwork as a link instead of a bitmap.
        String artUri = art != null ? null : first(md.getString(MediaMetadata.METADATA_KEY_ART_URI),
                md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI), md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI));
        Log.i(TAG, "session " + pkg + " keys=" + md.keySet() + " bitmap=" + (art != null) + " uri=" + artUri);
        if (art == null && title == null && artUri == null) return;
        boolean music = pkg.contains("spotify") || pkg.contains("music") || pkg.contains("audio");
        StringBuilder cap = new StringBuilder(music ? "Last played" : "Last watched");
        if (title != null) cap.append(" · ").append(title);
        if (who != null && !who.equals(title)) cap.append(" · ").append(who);
        Bitmap copy = art == null ? null : art.copy(Bitmap.Config.ARGB_8888, false);
        String caption = cap.toString();
        IO.execute(() -> {
            Bitmap a = copy;
            if (a == null && artUri != null) {
                try {
                    if (artUri.startsWith("http")) {
                        byte[] b = Net.bytes(artUri, 8 << 20);
                        a = android.graphics.BitmapFactory.decodeByteArray(b, 0, b.length);
                    } else {
                        try (java.io.InputStream in = c.getContentResolver().openInputStream(android.net.Uri.parse(artUri))) {
                            a = android.graphics.BitmapFactory.decodeStream(in);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "art uri " + artUri + ": " + e.getMessage());
                }
            }
            write(c, pkg, a, caption);
        });
    }

    private static void write(Context c, String pkg, Bitmap art, String caption) {
        File d = dir(c);
        try {
            if (art != null) {
                try (FileOutputStream out = new FileOutputStream(new File(d, pkg + ".jpg"))) {
                    art.compress(Bitmap.CompressFormat.JPEG, 85, out);
                }
            }
            try (FileOutputStream out = new FileOutputStream(new File(d, pkg + ".txt"))) {
                out.write(caption.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "save " + pkg + ": " + e.getMessage());
        }
    }

    /** Looks up live art for pkg off the main thread and answers on the main thread. */
    static void get(Context c, String pkg, int maxW, Callback cb) {
        Context app = c.getApplicationContext();
        IO.execute(() -> {
            Bitmap art = null;
            String caption = null;
            // 1. What the app last played (media session).
            File jpg = new File(dir(app), pkg + ".jpg"), txt = new File(dir(app), pkg + ".txt");
            if (txt.isFile()) caption = readText(txt);
            if (jpg.isFile()) art = decodeFile(jpg, maxW);
            // 2. Android TV's Watch Next row, where the app publishes one (needs READ_EPG_DATA; skipped if refused).
            if (art == null) {
                String[] wn = watchNext(app, pkg);
                if (wn != null) {
                    art = loadUri(app, wn[0], maxW);
                    if (art != null) caption = wn[1];
                }
            }
            Bitmap fa = art;
            String fc = caption;
            MAIN.post(() -> cb.onArt(pkg, fa, fc));
        });
    }

    /** {imageUri, caption} for the app's most recently engaged Watch Next program, or null. */
    private static String[] watchNext(Context c, String pkg) {
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        };
        ContentResolver cr = c.getContentResolver();
        try (Cursor cur = cr.query(TvContract.WatchNextPrograms.CONTENT_URI, cols,
                "package_name=?", new String[]{pkg},
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (cur == null || !cur.moveToFirst()) return null;
            String title = cur.getString(0), ep = cur.getString(1);
            String uri = first(cur.getString(3), cur.getString(2));
            if (uri == null) return null;
            long pos = cur.getLong(4), dur = cur.getLong(5);
            StringBuilder cap = new StringBuilder("Continue");
            if (title != null) cap.append(" · ").append(title);
            if (ep != null) cap.append(" · ").append(ep);
            if (dur > 0 && pos > 0 && pos < dur) cap.append(" · ").append((dur - pos) / 60000).append(" min left");
            return new String[]{uri, cap.toString()};
        } catch (SecurityException e) {
            return null;   // READ_EPG_DATA not granted
        } catch (RuntimeException e) {
            Log.w(TAG, "watch next: " + e.getMessage());
            return null;
        }
    }

    static Bitmap loadUri(Context c, String uri, int maxW) {
        try {
            byte[] data;
            if (uri.startsWith("http")) data = Net.bytes(uri, 8 << 20);
            else {
                try (InputStream in = c.getContentResolver().openInputStream(Uri.parse(uri))) {
                    if (in == null) return null;
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    data = bo.toByteArray();
                }
            }
            return data == null ? null : decode(data, maxW, 0);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static Bitmap decodeFile(File f, int maxW) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxW, 0);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Decodes at the smallest power-of-two size still at least maxW wide (and maxH tall when given). */
    static Bitmap decode(byte[] data, int maxW, int maxH) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;
        o.inSampleSize = sample(o.outWidth, o.outHeight, maxW, maxH);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static int sample(int w, int h, int maxW, int maxH) {
        int s = 1;
        while (w / (s * 2) >= maxW && (maxH <= 0 || h / (s * 2) >= maxH)) s *= 2;
        return s;
    }

    private static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String first(String... s) {
        for (String x : s) if (x != null && !x.trim().isEmpty()) return x.trim();
        return null;
    }

    /** Tiny HTTP helper shared with the screensaver. */
    static final class Net {
        private Net() {}

        static byte[] bytes(String url, int max) throws IOException {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "LuxuryTV-SapphireFork/1.0 (Android TV launcher)");
            c.setInstanceFollowRedirects(true);
            try {
                int code = c.getResponseCode();
                if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + url);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[32768];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > max) throw new IOException("too large: " + url);
                        bo.write(buf, 0, n);
                    }
                    return bo.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        }

        static String text(String url) throws IOException {
            return new String(bytes(url, 4 << 20), StandardCharsets.UTF_8);
        }
    }
}
