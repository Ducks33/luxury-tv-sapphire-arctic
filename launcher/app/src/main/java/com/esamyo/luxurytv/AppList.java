package com.esamyo.luxurytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The TV apps shown in the cover flow: dock_apps first in their configured order, then the rest alphabetically.
 * Each app keeps its own leanback banner; apps without one get a banner drawn from their real icon and colour.
 */
final class AppList {
    private AppList() {}

    static final class App {
        final String label, pkg;
        final ComponentName component;
        final Drawable icon;
        /** The app's own banner, or null. */
        final Drawable banner;
        /** The icon's dominant colour, for generated banners and glows. */
        final int color;

        App(String label, ComponentName component, Drawable icon, Drawable banner, int color) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.icon = icon;
            this.banner = banner;
            this.color = color;
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    static List<App> load(Context ctx, Prefs prefs) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        Set<String> hidden = new HashSet<>(prefs.cfg.hiddenApps);
        Map<String, App> byPkg = new HashMap<>();
        List<App> all = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            Drawable icon = ri.loadIcon(pm);
            Drawable banner = null;
            try {
                banner = ri.activityInfo.loadBanner(pm);
            } catch (RuntimeException ignored) {
            }
            App app = new App(label, cn, icon, banner, dominantColor(icon));
            byPkg.put(pkg, app);
            all.add(app);
        }
        List<App> out = new ArrayList<>();
        for (String pkg : prefs.cfg.dockApps) {
            App a = byPkg.remove(pkg);
            if (a != null) out.add(a);
        }
        List<App> rest = new ArrayList<>(byPkg.values());
        rest.sort((a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        out.addAll(rest);
        return out;
    }

    /** Average colour of the icon's saturated, visible pixels; a soft blue when there are none. */
    static int dominantColor(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) {
                for (int x = 0; x < 24; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 200) continue;
                    Color.colorToHSV(p, hsv);
                    if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue;
                    r += Color.red(p);
                    g += Color.green(p);
                    bl += Color.blue(p);
                    n++;
                }
            }
            b.recycle();
            if (n == 0) return 0xFF3A6FB0;
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (RuntimeException e) {
            return 0xFF3A6FB0;
        }
    }

    /**
     * A 16:9 banner for an app that has none: its real icon on a gradient of its own colour, with its name.
     * Drawn once per app at the card's pixel size.
     */
    static Drawable makeBanner(Context ctx, App app, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int col = app.color;
        int light = Ui.mix(col, Color.WHITE, 0.35f), dark = Ui.mix(col, 0xFF050A18, 0.65f);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new RadialGradient(w * 0.2f, h * 0.2f, w * 1.1f,
                new int[]{light, col, dark}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
        gloss.setShader(new LinearGradient(0, 0, 0, h * 0.5f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.5f, gloss);
        int s = Math.round(h * 0.6f), left = Math.round(h * 0.2f), top = (h - s) / 2;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x55000000);
        c.drawRoundRect(new RectF(left, top + s * 0.06f, left + s, top + s * 1.06f), s * 0.22f, s * 0.22f, shadow);
        app.icon.setBounds(left, top, left + s, top + s);
        app.icon.draw(c);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.WHITE);
        txt.setTypeface(Typeface.create(Ui.readBold(ctx), Typeface.BOLD));
        float size = h * 0.15f;
        txt.setTextSize(size);
        txt.setShadowLayer(size * 0.3f, 0, size * 0.08f, 0x77000000);
        float tx = left + s + h * 0.14f, maxW = w - tx - h * 0.12f;
        String label = app.label;
        while (txt.measureText(label) > maxW && size > h * 0.09f) {
            size *= 0.92f;
            txt.setTextSize(size);
        }
        while (txt.measureText(label) > maxW && label.length() > 3) label = label.substring(0, label.length() - 2) + "…";
        c.drawText(label, tx, h / 2f + size * 0.35f, txt);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }
}
