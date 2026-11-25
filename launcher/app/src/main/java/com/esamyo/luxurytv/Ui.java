package com.esamyo.luxurytv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

/** Small helpers for the views the Sapphire Arctic screens build in code. Sizes are in "cq": 1% of screen width. */
final class Ui {
    private Ui() {}

    static Typeface read(Context c) {
        return c.getResources().getFont(R.font.atkinson_regular);
    }

    static Typeface readBold(Context c) {
        return c.getResources().getFont(R.font.atkinson_bold);
    }

    /** Outfit at a given weight (the variable font). */
    static Typeface outfit(Context c, int weight) {
        Typeface base = c.getResources().getFont(R.font.outfit_var);
        if (android.os.Build.VERSION.SDK_INT >= 28) return Typeface.create(base, weight, false);
        return base;
    }

    /** A text view sized in pixels, with a soft dark shadow so it reads over any photo. */
    static TextView text(Context c, Typeface tf, float px, int color) {
        TextView t = new TextView(c);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        return t;
    }

    static void shadow(TextView t, float px) {
        t.setShadowLayer(px, 0, px * 0.25f, 0xCC000000);
    }

    /** A full-size vertical gradient with any number of stops. */
    static ShapeDrawable vertical(int[] colors, float[] stops) {
        ShapeDrawable d = new ShapeDrawable(new RectShape());
        d.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int w, int h) {
                return new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP);
            }
        });
        return d;
    }

    /** A radial gradient centred at (cx, cy) as fractions of the view, radius as a fraction of its width. */
    static ShapeDrawable radial(float cx, float cy, float r, int[] colors, float[] stops) {
        ShapeDrawable d = new ShapeDrawable(new RectShape());
        d.setShaderFactory(new ShapeDrawable.ShaderFactory() {
            @Override
            public Shader resize(int w, int h) {
                return new RadialGradient(w * cx, h * cy, Math.max(1, w * r), colors, stops, Shader.TileMode.CLAMP);
            }
        });
        return d;
    }

    static void round(View v, float radiusPx) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radiusPx);
            }
        });
        v.setClipToOutline(true);
    }

    static int alpha(int color, float a) {
        return (Math.round(a * 255) << 24) | (color & 0xFFFFFF);
    }

    static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
