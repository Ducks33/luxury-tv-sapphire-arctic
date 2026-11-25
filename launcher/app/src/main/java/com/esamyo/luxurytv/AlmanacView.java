package com.esamyo.luxurytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The almanac screensaver: a full-screen photo slideshow framed by large, readable information, in one fixed
 * layout. Top-left: day, date, sunrise, sunset, moon. Top centre: the photo's title and credit. Top-right: the
 * clock. Bottom-left: 5-day forecast and details. Bottom-right: conditions now and the next 6 hours.
 */
final class AlmanacView extends FrameLayout {
    private final Prefs prefs;
    private final float cq;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ImageView[] photo = new ImageView[2];
    private int front;
    private final TextView day, date, sun, moon, photoTitle, photoCredit;
    private final LinearLayout band;
    private PhotoFeeds feeds;
    private final Runnable minute = new Runnable() {
        @Override
        public void run() {
            updateDate();
            handler.postDelayed(this, 60_000L);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.get(getContext(), prefs, AlmanacView.this::showWeather);
            handler.postDelayed(this, Weather.MAX_AGE_MS);
        }
    };

    AlmanacView(Context c) {
        super(c);
        prefs = Prefs.load(c);
        cq = c.getResources().getDisplayMetrics().widthPixels / 100f;
        setBackgroundColor(0xFF040A1A);
        for (int i = 0; i < 2; i++) {
            photo[i] = new ImageView(c);
            photo[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo[i].setAlpha(0f);
            addView(photo[i], new LayoutParams(-1, -1));
        }
        shade = new View(c);
        shade.setBackground(Ui.vertical(new int[]{0xB8000000, 0x00000000, 0x00000000, 0xC0000000, 0xDD000000},
                new float[]{0f, 0.26f, 0.52f, 0.78f, 1f}));
        addView(shade, new LayoutParams(-1, -1));

        Typeface rd = Ui.read(c), bd = Ui.readBold(c);
        // Top-left: day, date, sun, moon.
        LinearLayout tl = column();
        day = shadowed(Ui.text(c, bd, 4.6f * cq, Color.WHITE));
        date = shadowed(Ui.text(c, rd, 3.4f * cq, 0xFFE8EEF6));
        date.setPadding(0, Math.round(0.6f * cq), 0, 0);
        sun = shadowed(Ui.text(c, rd, 2f * cq, Color.WHITE));
        sun.setPadding(0, Math.round(1.6f * cq), 0, 0);
        moon = shadowed(Ui.text(c, rd, 2f * cq, Color.WHITE));
        moon.setPadding(0, Math.round(0.9f * cq), 0, 0);
        tl.addView(day, wrap());
        tl.addView(date, wrap());
        tl.addView(sun, wrap());
        tl.addView(moon, wrap());
        LayoutParams tlp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tlp.leftMargin = Math.round(4 * cq);
        tlp.topMargin = Math.round(3.6f * cq);
        addView(tl, tlp);

        // Top centre: the photo's title and credit.
        LinearLayout tc = column();
        tc.setGravity(Gravity.CENTER_HORIZONTAL);
        photoTitle = shadowed(Ui.text(c, bd, 1.9f * cq, Color.WHITE));
        photoCredit = shadowed(Ui.text(c, rd, 1.5f * cq, 0xC0FFFFFF));
        // Wrap onto more lines rather than cut the title or credit off.
        for (TextView t : new TextView[]{photoTitle, photoCredit}) {
            t.setSingleLine(false);
            t.setMaxLines(3);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
        }
        photoCredit.setPadding(0, Math.round(0.5f * cq), 0, 0);
        tc.addView(photoTitle, new LinearLayout.LayoutParams(-1, -2));
        tc.addView(photoCredit, new LinearLayout.LayoutParams(-1, -2));
        LayoutParams tcp = new LayoutParams(Math.round(34 * cq), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tcp.topMargin = Math.round(4.2f * cq);
        addView(tc, tcp);

        // Top-right: the clock.
        LinearLayout clockRow = new LinearLayout(c);
        clockRow.setGravity(Gravity.BOTTOM);
        TextClock clock = new TextClock(c);
        String pattern = prefs.use24h(c) ? "H:mm" : "h:mm";
        clock.setFormat12Hour(pattern);
        clock.setFormat24Hour(pattern);
        clock.setTypeface(Ui.outfit(c, 400));
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10 * cq);
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(cq, 0, cq * 0.3f, 0x99000000);
        CoverFlowActivity.gradientText(clock, prefs.clockGradient);
        clockRow.addView(clock);
        if (!prefs.use24h(c)) {
            TextClock ampm = new TextClock(c);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("a");
            ampm.setTypeface(bd);
            ampm.setTextSize(TypedValue.COMPLEX_UNIT_PX, 3.4f * cq);
            ampm.setTextColor(prefs.accent);
            ampm.setIncludeFontPadding(false);
            shadowed(ampm);
            ampm.setPadding(Math.round(1.1f * cq), 0, 0, Math.round(1.3f * cq));
            clockRow.addView(ampm);
        }
        LayoutParams crp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        crp.rightMargin = Math.round(4 * cq);
        crp.topMargin = Math.round(3f * cq);
        addView(clockRow, crp);

        // Bottom: the weather band (filled by showWeather).
        band = column();
        LayoutParams bp = new LayoutParams(-1, -2, Gravity.BOTTOM);
        bp.leftMargin = bp.rightMargin = Math.round(4 * cq);
        bp.bottomMargin = Math.round(2.4f * cq);
        addView(band, bp);
        overlay = new View[]{shade, tl, tc, clockRow, band};
        updateDate();
    }

    /** After 15 s the time, weather and photo text fade away so the photo shows on its own. */
    private static final long INFO_MS = 15_000L;
    private View shade;
    private View[] overlay;

    void start() {
        for (View v : overlay) v.setAlpha(1f);
        handler.postDelayed(() -> {
            for (View v : overlay) v.animate().alpha(0f).setDuration(2500).start();
        }, INFO_MS);
        handler.post(minute);
        handler.post(weatherTick);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        feeds = new PhotoFeeds(prefs.feeds, prefs.photoMs, prefs.sourceMs, w, h);
        feeds.start(this::showPhoto);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (feeds != null) feeds.stop();
    }

    private void showPhoto(Bitmap bmp, PhotoFeeds.Photo p, String source, int n, int count) {
        ImageView old = photo[front];
        front = 1 - front;
        ImageView now = photo[front];
        now.animate().cancel();
        now.setImageBitmap(bmp);
        now.setScaleX(1.06f);
        now.setScaleY(1.06f);
        now.setTranslationX(-0.01f * getWidth());
        now.animate().alpha(1f).setDuration(2400).start();
        // A slow drift across the whole time the photo is up.
        now.postDelayed(() -> now.animate().scaleX(1.12f).scaleY(1.12f).translationX(0.01f * getWidth())
                .setDuration(prefs.photoMs).setInterpolator(new LinearInterpolator()).start(), 2400);
        old.animate().cancel();
        old.animate().alpha(0f).setDuration(2400).withEndAction(() -> old.setImageDrawable(null)).start();
        android.util.Log.w("LuxuryTV.Saver", "photo from " + source + " (" + n + " of " + count + "): " + p.title);
        photoTitle.setText(p.title == null ? "" : p.title);
        photoCredit.setText(p.credit + " · source " + n + " of " + count);
    }

    private void updateDate() {
        Date now = new Date();
        fit(day, new SimpleDateFormat("EEEE", Locale.getDefault()).format(now));
        fit(date, new SimpleDateFormat("MMMM d", Locale.getDefault()).format(now));
        fit(moon, label("Moon ", Weather.moonPhase(now)));
    }

    private CharSequence label(String k, String v) {
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(k);
        s.setSpan(new android.text.style.ForegroundColorSpan(prefs.accent), 0, s.length(), 0);
        int st = s.length();
        s.append(v);
        s.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), st, s.length(), 0);
        return s;
    }

    private void showWeather(Weather.Data d) {
        Context c = getContext();
        Typeface rd = Ui.read(c), bd = Ui.readBold(c);
        android.text.SpannableStringBuilder sunText = new android.text.SpannableStringBuilder();
        sunText.append(label("Sunrise ", d.sunrise)).append("    ").append(label("Sunset ", d.sunset));
        fit(sun, sunText);
        fit(moon, label("Moon ", d.moon));
        band.removeAllViews();

        // Row 1: 5-day forecast (left) and conditions now (right).
        LinearLayout row1 = row();
        LinearLayout days = new LinearLayout(c);
        for (Weather.Day dy : d.days) {
            LinearLayout col = column();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.addView(shadowed(Ui.text(c, rd, 1.9f * cq, prefs.accent)), wrap());
            fit((TextView) col.getChildAt(0), dy.name);
            col.addView(icon(Weather.icon(dy.code, true), 3.2f));
            TextView hi = shadowed(Ui.text(c, bd, 2.4f * cq, Color.WHITE));
            fit(hi, dy.hi + "°");
            TextView lo = shadowed(Ui.text(c, rd, 1.8f * cq, 0xFFB9C6D8));
            fit(lo, dy.lo + "°");
            col.addView(hi, wrap());
            col.addView(lo, wrap());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Math.round(2.2f * cq);
            days.addView(col, lp);
        }
        row1.addView(days);
        row1.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout nowBlock = new LinearLayout(c);
        nowBlock.setGravity(Gravity.CENTER_VERTICAL);
        nowBlock.addView(icon(Weather.icon(d.code, d.isDay), 6f));
        TextView temp = Ui.text(c, Ui.outfit(c, 400), 6.6f * cq, Color.WHITE);
        shadowed(temp);
        fit(temp, d.temp + d.unit);
        temp.setPadding(Math.round(1.3f * cq), 0, Math.round(1.3f * cq), 0);
        nowBlock.addView(temp);
        LinearLayout meta = column();
        TextView cond = shadowed(Ui.text(c, bd, 2.6f * cq, Color.WHITE));
        fit(cond, d.condition());
        TextView feels = shadowed(Ui.text(c, rd, 1.9f * cq, 0xFFE3EAF4));
        fit(feels, "Feels like " + d.feels + d.unit);
        TextView hilo = shadowed(Ui.text(c, rd, 1.9f * cq, 0xFFE3EAF4));
        fit(hilo, "High " + d.hi + "° · Low " + d.lo + "°");
        meta.addView(cond, wrap());
        meta.addView(feels, wrap());
        meta.addView(hilo, wrap());
        nowBlock.addView(meta);
        row1.addView(nowBlock);
        band.addView(row1);

        // Row 2: details (left) and the next 6 hours (right).
        LinearLayout row2 = row();
        GridLayout dets = new GridLayout(c);
        dets.setColumnCount(3);
        String[][] items = {
                {"Humidity ", d.humidity + "%"}, {"Wind ", d.windDir + " " + d.wind + " " + d.speedUnit},
                {"Rain ", d.rainPct + "%"}, {"UV ", Weather.uvLabel(d.uv)},
                {"Air ", Weather.aqiLabel(d.aqi)},
                {"Pressure ", prefs.fahrenheit ? String.format(Locale.US, "%.1f in", d.pressureIn)
                        : Math.round(d.pressureIn / 0.02953) + " hPa"}};
        for (String[] it : items) {
            TextView t = shadowed(Ui.text(c, rd, 2.1f * cq, Color.WHITE));
            fit(t, label(it[0], it[1]));
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams();
            gp.rightMargin = Math.round(2.6f * cq);
            gp.bottomMargin = Math.round(0.8f * cq);
            dets.addView(t, gp);
        }
        row2.addView(dets);
        row2.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout hours = new LinearLayout(c);
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(1.2f * cq);
        box.setColor(0x24FFFFFF);
        box.setStroke(1, 0x22FFFFFF);
        hours.setBackground(box);
        int px = Math.round(1.4f * cq), py = Math.round(0.8f * cq);
        hours.setPadding(px, py, px - Math.round(1.0f * cq), py);
        for (Weather.Hour h : d.hours) {
            LinearLayout col = column();
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView lb = shadowed(Ui.text(c, rd, 1.6f * cq, prefs.accent));
            fit(lb, h.label);
            col.addView(lb, wrap());
            col.addView(icon(Weather.icon(h.code, h.day), 2.4f));
            TextView t = shadowed(Ui.text(c, bd, 2f * cq, Color.WHITE));
            fit(t, h.temp + "°");
            col.addView(t, wrap());
            TextView pp = shadowed(Ui.text(c, rd, 1.5f * cq, 0xFF9FD6F6));
            fit(pp, "💧" + h.pop + "%");
            col.addView(pp, wrap());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Math.round(1.0f * cq);
            hours.addView(col, lp);
        }
        row2.addView(hours);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(-1, -2);
        r2.topMargin = Math.round(1.3f * cq);
        band.addView(row2, r2);
    }

    private ImageView icon(int kind, float sizeCq) {
        ImageView v = new ImageView(getContext());
        v.setImageDrawable(new WxIcon(kind));
        v.setLayerType(View.LAYER_TYPE_SOFTWARE, null);   // shadow layers on paths
        int s = Math.round(sizeCq * cq);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    /** Children of a vertical column must be wrap_content: match_parent text does not count toward the
     *  column's width, so the column took the icon's width and cut the text off. */
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(getContext());
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(getContext());
        l.setGravity(Gravity.BOTTOM);
        l.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    /**
     * Sets the text and reserves its full drawn width. On the TCL the dream's text views came out narrower than
     * their text (cut to the width of the icon or line above), so each one claims its own measured width, plus
     * room for the shadow.
     */
    private void fit(TextView t, CharSequence text) {
        t.setText(text);
        float w = android.text.Layout.getDesiredWidth(text, t.getPaint());
        t.setMinWidth((int) Math.ceil(w) + t.getPaddingLeft() + t.getPaddingRight() + Math.round(0.3f * cq));
        t.setMinimumWidth(t.getMinWidth());
    }

    private <T extends TextView> T shadowed(T t) {
        Ui.shadow(t, cq * 0.6f);
        return t;
    }
}
