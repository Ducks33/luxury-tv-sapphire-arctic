package com.esamyo.luxurytv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Sapphire Arctic home: a full-screen 3D cover flow of app banners over a sapphire background that turns into the
 * focused app's live picture. Left/Right move, OK opens, Up reaches Settings, Back opens the almanac screensaver.
 */
public class CoverFlowActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    private static final PathInterpolator SWING = new PathInterpolator(0.34f, 1.32f, 0.5f, 1f);
    private static final PathInterpolator EASE = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);
    private static final int BLUE_DEEP = 0xFF040A1A;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private float cq;   // 1% of screen width in px
    private FrameLayout root, stage, liveLayer;
    private ImageView liveImg;
    private TextView liveCaption, name, line, posText, tempView;
    private LinearLayout dots;
    private ImageView settingsBtn;
    private List<AppList.App> apps = new ArrayList<>();
    private final List<Card> cards = new ArrayList<>();
    private int cur;
    private static String lastPkg;
    private boolean appsStale;
    private String liveFor;
    private final Runnable liveRunnable = this::fetchLive;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(i.getAction()) && i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            appsStale = true;
            if (hasWindowFocus()) reloadApps();
        }
    };

    /** One app's card: the outer view carries its place in the flow, the face spins on focus changes. */
    private static final class Card {
        FrameLayout outer, face, clip;
        ImageView img, preview;
        View dim, shine, ring, ripple;
        int k = Integer.MIN_VALUE;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.load(this);
        cq = getResources().getDisplayMetrics().widthPixels / 100f;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        buildUi();
        setContentView(root);
        reloadApps();
        Weather.get(this, prefs, this::showWeather);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Opened by hand while the Home helper is down (e.g. TCL blocked it at boot): switch it back on.
        getWindow().getDecorView().postDelayed(() -> BootRepair.repairHelper(getApplicationContext()), 4000);
        if (appsStale) reloadApps();
        Weather.get(this, prefs, this::showWeather);
        scheduleLive();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(liveRunnable);
        stopPreview();   // no animation running behind an app or the screensaver
        super.onPause();
    }

    /** Home pressed while already home: go back to the default app. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int def = defaultIndex();
        if (def != cur && !apps.isEmpty()) moveTo(def, def > cur ? 1 : -1);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        root.setClipChildren(false);
        root.setBackgroundColor(BLUE_DEEP);
        getWindow().setBackgroundDrawable(null);

        View sapphire = new View(this);
        sapphire.setBackground(Ui.radial(0.55f, 0.35f, 0.85f,
                new int[]{0xFF1B3A6B, 0xFF0F2347, 0xFF081430, BLUE_DEEP}, new float[]{0f, 0.42f, 0.72f, 1f}));
        root.addView(sapphire, match());

        liveLayer = new FrameLayout(this);
        liveLayer.setAlpha(0f);
        liveImg = new ImageView(this);
        liveImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        liveImg.setScaleX(1.08f);
        liveImg.setScaleY(1.08f);
        liveLayer.addView(liveImg, match());
        View shade = new View(this);
        shade.setBackground(Ui.vertical(new int[]{0x99040A1A, 0x00040A1A, 0x00040A1A, 0xB3040A1A}, new float[]{0f, 0.25f, 0.62f, 1f}));
        liveLayer.addView(shade, match());
        root.addView(liveLayer, match());

        View floor = new View(this);
        floor.setBackground(Ui.radial(0.5f, 0f, 0.5f, new int[]{Ui.alpha(prefs.accent, 0.08f), 0x00000000}, new float[]{0f, 0.7f}));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(-1, Math.round(20 * cq));
        flp.topMargin = Math.round(36 * cq);
        root.addView(floor, flp);

        stage = new FrameLayout(this);
        stage.setClipChildren(false);
        stage.setClipToPadding(false);
        root.addView(stage, match());

        root.addView(topBar(), new FrameLayout.LayoutParams(-1, -2));

        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        name = Ui.text(this, Ui.readBold(this), 4.8f * cq, Color.WHITE);
        line = Ui.text(this, Ui.read(this), 2.2f * cq, 0xFFD3DBE8);
        Ui.shadow(name, cq * 0.8f);
        Ui.shadow(line, cq * 0.6f);
        line.setPadding(0, Math.round(cq), 0, 0);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        line.setGravity(Gravity.CENTER_HORIZONTAL);
        caption.addView(name, new LinearLayout.LayoutParams(-1, -2));
        caption.addView(line, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-1, -2);
        clp.topMargin = Math.round(39.5f * cq);
        root.addView(caption, clp);

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        dlp.bottomMargin = Math.round(2.4f * cq);
        root.addView(dots, dlp);
        posText = Ui.text(this, Ui.read(this), 1.6f * cq, 0xFF8E9AB0);
        posText.setGravity(Gravity.CENTER);
        root.addView(posText, dlp);
    }

    private View topBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Math.round(4 * cq), Math.round(3.2f * cq), Math.round(4 * cq), 0);
        TextClock clock = new TextClock(this);
        // Both patterns set to the theme's choice: TextClock otherwise follows the TV's own 12/24-hour setting.
        String pattern = prefs.use24h(this) ? "H:mm" : "h:mm";
        clock.setFormat12Hour(pattern);
        clock.setFormat24Hour(pattern);
        clock.setTypeface(Ui.outfit(this, 400));
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 4.6f * cq);
        clock.setIncludeFontPadding(false);
        gradientText(clock, prefs.clockGradient);
        bar.addView(clock);
        if (!prefs.use24h(this)) {
            TextClock ampm = new TextClock(this);
            ampm.setFormat12Hour("a");
            ampm.setFormat24Hour("a");
            ampm.setTypeface(Ui.readBold(this));
            ampm.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 1.9f * cq);
            ampm.setTextColor(prefs.accent);
            ampm.setPadding(Math.round(cq), Math.round(1.2f * cq), 0, 0);
            bar.addView(ampm);
        }
        tempView = Ui.text(this, Ui.read(this), 2.3f * cq, Color.WHITE);
        tempView.setPadding(Math.round(2.4f * cq), 0, 0, 0);
        bar.addView(tempView);
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        liveCaption = Ui.text(this, Ui.read(this), 1.6f * cq, 0xFFDCE4F0);
        liveCaption.setAlpha(0f);
        liveCaption.setPadding(0, 0, Math.round(2.6f * cq), 0);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(prefs.accent);
        dot.setSize(Math.round(cq), Math.round(cq));
        liveCaption.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        liveCaption.setCompoundDrawablePadding(Math.round(0.9f * cq));
        bar.addView(liveCaption, new LinearLayout.LayoutParams(-2, -2));
        ImageView wifi = new ImageView(this);
        wifi.setImageResource(R.drawable.ic_wifi);
        wifi.setColorFilter(0xFFC9D2E0);
        int ic = Math.round(2.4f * cq);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ic, ic);
        wlp.rightMargin = Math.round(2.2f * cq);
        bar.addView(wifi, wlp);
        settingsBtn = new ImageView(this);
        settingsBtn.setImageResource(R.drawable.ic_settings);
        settingsBtn.setColorFilter(0xFFC9D2E0);
        settingsBtn.setFocusable(true);
        int pad = Math.round(0.7f * cq);
        settingsBtn.setPadding(pad, pad, pad, pad);
        GradientDrawable focusBg = new GradientDrawable();
        focusBg.setShape(GradientDrawable.OVAL);
        focusBg.setColor(0x00000000);
        settingsBtn.setBackground(focusBg);
        settingsBtn.setOnFocusChangeListener((v, has) -> {
            focusBg.setColor(has ? Ui.alpha(prefs.accent, 0.35f) : 0x00000000);
            focusBg.setStroke(has ? Math.round(0.25f * cq) : 0, prefs.ring);
            settingsBtn.setColorFilter(has ? Color.WHITE : 0xFFC9D2E0);
        });
        settingsBtn.setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        bar.addView(settingsBtn, new LinearLayout.LayoutParams(ic + 2 * pad, ic + 2 * pad));
        return bar;
    }

    static void gradientText(TextView t, int[] colors) {
        t.addOnLayoutChangeListener((v, l, top, r, bottom, ol, ot, or, ob) -> {
            float h = bottom - top;
            if (h <= 0) return;
            t.getPaint().setShader(new LinearGradient(0, 0, 0, h, colors, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            t.invalidate();
        });
    }

    private void showWeather(Weather.Data d) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        s.append(d.temp + d.unit);
        s.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int st = s.length();
        s.append("  ").append(d.condition());
        s.setSpan(new ForegroundColorSpan(0xFFC9D6E8), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tempView.setText(s);
    }

    // ---------------------------------------------------------------- apps and cards

    private void reloadApps() {
        appsStale = false;
        String keep = cards.isEmpty() ? null : (cur < apps.size() ? apps.get(cur).pkg : null);
        apps = AppList.load(this, prefs);
        stage.removeAllViews();
        cards.clear();
        int cw = Math.round(31 * cq), ch = Math.round(17.5f * cq);
        for (AppList.App a : apps) cards.add(makeCard(a, cw, ch));
        if (apps.isEmpty()) {
            name.setText("No TV apps found");
            return;
        }
        String want = keep != null ? keep : (lastPkg != null ? lastPkg : null);
        int idx = want == null ? defaultIndex() : indexOf(want);
        cur = idx < 0 ? defaultIndex() : idx;
        place(0);
        updateCaption(0);
        scheduleLive();
    }

    private int defaultIndex() {
        int i = prefs.defaultFocus == null ? -1 : indexOf(prefs.defaultFocus);
        return Math.max(0, i);
    }

    private int indexOf(String pkg) {
        for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(pkg)) return i;
        return -1;
    }

    private Card makeCard(AppList.App app, int cw, int ch) {
        Card c = new Card();
        float radius = 1.6f * cq;
        c.outer = new FrameLayout(this);
        c.outer.setClipChildren(false);
        c.outer.setCameraDistance(cw * 9f);
        c.outer.setVisibility(View.INVISIBLE);
        c.face = new FrameLayout(this);
        c.face.setClipChildren(false);
        c.face.setCameraDistance(cw * 9f);
        c.clip = new FrameLayout(this);
        Ui.round(c.clip, radius);
        c.clip.setElevation(cq * 0.8f);
        c.img = new ImageView(this);
        Drawable banner = app.banner != null ? app.banner : AppList.makeBanner(this, app, cw, ch);
        c.img.setImageDrawable(banner);
        c.img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.clip.addView(c.img, match());
        // Motion preview: the app's last-watched thumbnail, drifting slowly while the tile is focused.
        c.preview = new ImageView(this);
        c.preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.preview.setAlpha(0f);
        c.clip.addView(c.preview, match());
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(radius);
        rim.setStroke(Math.max(1, Math.round(cq * 0.08f)), 0x40FFFFFF);
        View rimView = new View(this);
        rimView.setBackground(rim);
        c.clip.addView(rimView, match());
        c.dim = new View(this);
        c.dim.setBackgroundColor(Color.BLACK);
        c.dim.setAlpha(0f);
        c.clip.addView(c.dim, match());
        c.shine = new View(this);
        ShapeDrawable sh = new ShapeDrawable(new RectShape());
        sh.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, w, 0, new int[]{0x00FFFFFF, 0x70FFFFFF, 0x00FFFFFF}, new float[]{0.1f, 0.5f, 0.9f}, Shader.TileMode.CLAMP);
            }
        });
        c.shine.setBackground(sh);
        c.shine.setRotation(15f);
        c.shine.setTranslationX(-cw * 1.3f);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(Math.round(cw * 0.45f), Math.round(ch * 1.8f), Gravity.CENTER);
        c.clip.addView(c.shine, slp);
        c.face.addView(c.clip, match());

        int o = Math.round(0.6f * cq), stroke = Math.round(0.4f * cq);
        c.ring = ringView(radius + o, stroke);
        c.ring.setAlpha(0f);
        c.ripple = ringView(radius + o, Math.round(0.35f * cq));
        c.ripple.setAlpha(0f);
        for (View r : new View[]{c.ring, c.ripple}) {
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(cw + 2 * o, ch + 2 * o);
            r.setTranslationX(-o);
            r.setTranslationY(-o);
            c.face.addView(r, rlp);
        }
        c.outer.addView(c.face, match());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cw, ch, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = Math.round(14 * cq);
        stage.addView(c.outer, lp);
        return c;
    }

    private View ringView(float radius, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radius);
        g.setStroke(stroke, prefs.ring);
        View v = new View(this);
        v.setBackground(g);
        return v;
    }

    /** Lays the cards out around cur. dir != 0 animates (and plays the focus effects); 0 jumps. */
    private void place(int dir) {
        int n = cards.size();
        for (int i = 0; i < n; i++) {
            Card c = cards.get(i);
            int k = ((i - cur) % n + n) % n;
            if (k > n / 2) k -= n;
            int a = Math.abs(k), sg = Integer.signum(k);
            boolean wrap = c.k != Integer.MIN_VALUE && Math.abs(k - c.k) > 1;
            boolean visibleNow = a <= 3, visibleBefore = c.k != Integer.MIN_VALUE && Math.abs(c.k) <= 3;
            c.k = k;
            float tx = k == 0 ? 0 : sg * (18 + (a - 1) * 10) * cq;
            float ty = a * 1.2f * cq;
            float rot = -sg * 55f;
            float sc = k == 0 ? 1.06f : 1f - 0.1f * a;
            float al = a > 2 ? 0f : 1f;
            float dimA = k == 0 ? 0f : a == 1 ? 0.4f : 0.62f;
            c.outer.setTranslationZ(10 - a);
            c.ring.setAlpha(k == 0 ? 1f : 0f);
            if (!visibleNow && !visibleBefore) {
                c.outer.setVisibility(View.INVISIBLE);
                continue;
            }
            c.outer.setVisibility(View.VISIBLE);
            c.outer.animate().cancel();
            if (dir == 0 || wrap) {
                c.outer.setTranslationX(tx);
                c.outer.setTranslationY(ty);
                c.outer.setRotationY(rot);
                c.outer.setScaleX(sc);
                c.outer.setScaleY(sc);
                c.outer.setAlpha(al);
                c.dim.setAlpha(dimA);
            } else {
                c.outer.animate().translationX(tx).translationY(ty).rotationY(rot).scaleX(sc).scaleY(sc).alpha(al)
                        .setDuration(800).setInterpolator(SWING).start();
                c.dim.animate().alpha(dimA).setDuration(600).start();
            }
            if (!visibleNow) c.outer.setVisibility(dir == 0 ? View.INVISIBLE : View.VISIBLE);
        }
        if (dir != 0 && !cards.isEmpty()) focusEffects(cards.get(cur), dir);
    }

    /** The new centre card spins in from the side you moved towards, a light sweeps its glass, a ring pulses out. */
    private void focusEffects(Card c, int dir) {
        float from = dir > 0 ? -200f : 200f;
        PropertyValuesHolder rot = PropertyValuesHolder.ofKeyframe(View.ROTATION_Y,
                Keyframe.ofFloat(0f, from), Keyframe.ofFloat(0.55f, from * -0.06f), Keyframe.ofFloat(1f, 0f));
        PropertyValuesHolder sx = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                Keyframe.ofFloat(0f, 0.82f), Keyframe.ofFloat(0.55f, 1.1f), Keyframe.ofFloat(1f, 1f));
        PropertyValuesHolder sy = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                Keyframe.ofFloat(0f, 0.82f), Keyframe.ofFloat(0.55f, 1.1f), Keyframe.ofFloat(1f, 1f));
        ObjectAnimator spin = ObjectAnimator.ofPropertyValuesHolder(c.face, rot, sx, sy);
        spin.setDuration(900);
        spin.setInterpolator(EASE);
        float cw = c.outer.getLayoutParams().width;
        c.shine.setTranslationX(-cw * 1.3f);
        ObjectAnimator shine = ObjectAnimator.ofFloat(c.shine, View.TRANSLATION_X, -cw * 1.3f, cw * 1.3f);
        shine.setStartDelay(300);
        shine.setDuration(900);
        c.ripple.setScaleX(1f);
        c.ripple.setScaleY(1f);
        ObjectAnimator ripple = ObjectAnimator.ofPropertyValuesHolder(c.ripple,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.4f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.4f));
        ripple.setStartDelay(250);
        ripple.setDuration(1000);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(spin, shine, ripple);
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                c.face.setRotationY(0f);
                c.face.setScaleX(1f);
                c.face.setScaleY(1f);
            }
        });
        set.start();
    }

    private void updateCaption(int dir) {
        AppList.App a = apps.get(cur);
        name.setText(a.label);
        line.setText("");
        lastPkg = a.pkg;
        if (dir != 0) {
            for (View v : new View[]{name, line}) {
                v.setAlpha(0f);
                v.setTranslationX(dir * 6 * cq);
                v.animate().alpha(1f).translationX(0f).setDuration(550).setInterpolator(EASE).start();
            }
        }
        int n = apps.size();
        dots.removeAllViews();
        if (n <= 12) {
            posText.setVisibility(View.GONE);
            for (int i = 0; i < n; i++) {
                View d = new View(this);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(i == cur ? prefs.accent : 0xFF3A4558);
                d.setBackground(g);
                int s = Math.round(0.9f * cq);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
                lp.leftMargin = lp.rightMargin = Math.round(0.5f * cq);
                dots.addView(d, lp);
            }
        } else {
            posText.setVisibility(View.VISIBLE);
            posText.setText((cur + 1) + " of " + n);
        }
    }

    private void moveTo(int index, int dir) {
        if (apps.isEmpty()) return;
        cur = ((index % apps.size()) + apps.size()) % apps.size();
        place(dir);
        updateCaption(dir);
        hideLive();
        scheduleLive();
    }

    // ---------------------------------------------------------------- live background

    private void hideLive() {
        liveFor = null;
        stopPreview();
        handler.removeCallbacks(liveRunnable);
        liveLayer.animate().alpha(0f).setDuration(350).start();
        liveCaption.animate().alpha(0f).setDuration(300).start();
    }

    private void scheduleLive() {
        handler.removeCallbacks(liveRunnable);
        if (prefs.liveBackground && !apps.isEmpty()) handler.postDelayed(liveRunnable, prefs.liveDelayMs);
    }

    private void fetchLive() {
        if (apps.isEmpty()) return;
        AppList.App app = apps.get(cur);
        liveFor = app.pkg;
        LiveArt.get(this, app.pkg, Math.round(64 * cq), (pkg, art, caption) -> {
            if (!pkg.equals(liveFor) || isFinishing()) return;
            if (art != null) {
                if (Build.VERSION.SDK_INT >= 31) liveImg.setRenderEffect(null);   // real artwork: sharp
                liveImg.setImageBitmap(art);
                startPreview(art);
                line.setText(caption == null ? "" : caption);
                showLiveCaption(caption);
            } else if (app.banner != null) {
                if (Build.VERSION.SDK_INT >= 31) liveImg.setRenderEffect(RenderEffect.createBlurEffect(cq * 1.2f, cq * 1.2f, Shader.TileMode.CLAMP));
                liveImg.setImageDrawable(app.banner);   // nothing live: the app's own banner, blurred
                liveCaption.setAlpha(0f);
            } else {
                return;   // stay on sapphire
            }
            liveLayer.animate().alpha(1f).setDuration(1200).start();
        });
    }

    // ---------------------------------------------------------------- motion preview in the tile

    private Card previewCard;
    private android.animation.AnimatorSet previewAnim;

    /** Fades the last-watched thumbnail into the focused tile and lets it drift (slow zoom and pan) until focus moves. */
    private void startPreview(android.graphics.Bitmap art) {
        stopPreview();
        if (cur >= cards.size()) return;
        Card c = cards.get(cur);
        previewCard = c;
        c.preview.setImageBitmap(art);
        c.preview.setScaleX(1.04f);
        c.preview.setScaleY(1.04f);
        c.preview.setTranslationX(0f);
        c.preview.animate().alpha(1f).setDuration(700).start();
        float pan = cq * 1.6f;
        ObjectAnimator sx = ObjectAnimator.ofFloat(c.preview, View.SCALE_X, 1.04f, 1.22f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(c.preview, View.SCALE_Y, 1.04f, 1.22f);
        ObjectAnimator tx = ObjectAnimator.ofFloat(c.preview, View.TRANSLATION_X, -pan, pan);
        ObjectAnimator ty = ObjectAnimator.ofFloat(c.preview, View.TRANSLATION_Y, pan * 0.4f, -pan * 0.4f);
        for (ObjectAnimator a : new ObjectAnimator[]{sx, sy, tx, ty}) {
            a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        }
        previewAnim = new android.animation.AnimatorSet();
        previewAnim.playTogether(sx, sy, tx, ty);
        previewAnim.setDuration(9000);
        previewAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        previewAnim.start();
    }

    private void stopPreview() {
        if (previewAnim != null) previewAnim.cancel();
        previewAnim = null;
        if (previewCard != null) {
            ImageView v = previewCard.preview;
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(250).withEndAction(() -> v.setImageDrawable(null)).start();
        }
        previewCard = null;
    }

    private void showLiveCaption(String caption) {
        // The caption already shows under the tile; repeating it in the top bar crowded the weather.
        if (caption == null || line != null) return;
        liveCaption.setText(caption);
        liveCaption.animate().alpha(0.95f).setDuration(800).start();
    }

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        boolean onSettings = settingsBtn.isFocused();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (onSettings) return true;
                moveTo(cur - 1, -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (onSettings) return true;
                moveTo(cur + 1, 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                settingsBtn.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (onSettings) root.requestFocus();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (onSettings) return super.dispatchKeyEvent(e);
                if (!apps.isEmpty()) {
                    // The YouTube tile really opens YouTube (only the remote's YouTube button goes to youtube_button_app).
                    if (HomeKeyService.YOUTUBE.equals(apps.get(cur).pkg)) HomeKeyService.youtubeFromTile = System.currentTimeMillis();
                    open(apps.get(cur).launchIntent());
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (onSettings) {
                    root.requestFocus();
                    return true;
                }
                open(new Intent(this, AlmanacActivity.class));
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Couldn't open that", Toast.LENGTH_SHORT).show();
        }
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
