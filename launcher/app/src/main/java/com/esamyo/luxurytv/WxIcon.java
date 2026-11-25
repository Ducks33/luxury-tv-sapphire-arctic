package com.esamyo.luxurytv;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Weather icons drawn in code on a 24-unit grid: soft white shapes with a pale-blue accent for rain and snow. */
final class WxIcon extends Drawable {
    static final int SUN = 0, MOON = 1, CLOUD = 2, PART = 3, NIGHT_CLOUD = 4, RAIN = 5, SNOW = 6, FOG = 7, STORM = 8;
    private static final int SUN_C = 0xFFFFF1CC, MOON_C = 0xFFEAF4FF, CLOUD_C = 0xFFDCE8F6, CLOUD2_C = 0xFFC9D8EA, DROP_C = 0xFF8FD3FF;

    private final int kind;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

    WxIcon(int kind) {
        this.kind = kind;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 24f;
        c.save();
        c.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f);
        c.scale(s, s);
        line.setStrokeWidth(1.6f);
        fill.setShadowLayer(1.2f, 0, 0.4f, 0x66000000);
        switch (kind) {
            case SUN: sun(c, 12, 12, 4.6f, true); break;
            case MOON: moon(c, 12, 12, 8.5f); break;
            case CLOUD: cloud(c, 0, 0, CLOUD_C); break;
            case PART: sun(c, 8.5f, 8.5f, 3.6f, false); cloud(c, 1.5f, 2.5f, CLOUD_C); break;
            case NIGHT_CLOUD: moon(c, 10.5f, 9, 6f); cloud(c, 1.5f, 3f, CLOUD2_C); break;
            case RAIN: cloud(c, 0, -3, CLOUD_C); drops(c, false); break;
            case SNOW: cloud(c, 0, -3, CLOUD_C); drops(c, true); break;
            case FOG: cloud(c, 0, -3, CLOUD2_C); line.setColor(CLOUD_C); c.drawLine(5, 18, 19, 18, line); c.drawLine(7, 21, 17, 21, line); break;
            case STORM: cloud(c, 0, -3, CLOUD2_C); bolt(c); break;
        }
        c.restore();
    }

    private void sun(Canvas c, float cx, float cy, float r, boolean rays) {
        fill.setColor(SUN_C);
        c.drawCircle(cx, cy, r, fill);
        if (!rays) return;
        line.setColor(SUN_C);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float x1 = cx + (float) Math.cos(a) * (r + 2.2f), y1 = cy + (float) Math.sin(a) * (r + 2.2f);
            float x2 = cx + (float) Math.cos(a) * (r + 4.6f), y2 = cy + (float) Math.sin(a) * (r + 4.6f);
            c.drawLine(x1, y1, x2, y2, line);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        Path a = new Path(), b = new Path();
        a.addCircle(cx, cy, r, Path.Direction.CW);
        b.addCircle(cx + r * 0.55f, cy - r * 0.35f, r * 0.85f, Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        fill.setColor(MOON_C);
        c.drawPath(a, fill);
    }

    private void cloud(Canvas c, float dx, float dy, int color) {
        Path p = new Path();
        p.addCircle(9 + dx, 13.5f + dy, 4.2f, Path.Direction.CW);
        Path q = new Path();
        q.addCircle(13 + dx, 11.5f + dy, 5.3f, Path.Direction.CW);
        p.op(q, Path.Op.UNION);
        Path r = new Path();
        r.addCircle(17 + dx, 14.5f + dy, 3.4f, Path.Direction.CW);
        p.op(r, Path.Op.UNION);
        Path base = new Path();
        base.addRoundRect(5 + dx, 13 + dy, 20.4f + dx, 18 + dy, 2.5f, 2.5f, Path.Direction.CW);
        p.op(base, Path.Op.UNION);
        fill.setColor(color);
        c.drawPath(p, fill);
    }

    private void drops(Canvas c, boolean snow) {
        if (snow) {
            fill.setColor(0xFFFFFFFF);
            for (float x : new float[]{8, 12, 16}) c.drawCircle(x, 19.5f, 1.2f, fill);
            return;
        }
        line.setColor(DROP_C);
        for (float x : new float[]{8.5f, 12.5f, 16.5f}) c.drawLine(x, 17.5f, x - 1, 20.5f, line);
    }

    private void bolt(Canvas c) {
        Path p = new Path();
        p.moveTo(12.5f, 15);
        p.lineTo(9.5f, 19.5f);
        p.lineTo(12, 19.5f);
        p.lineTo(10.5f, 23);
        p.lineTo(15, 17.8f);
        p.lineTo(12.4f, 17.8f);
        p.lineTo(13.8f, 15);
        p.close();
        fill.setColor(SUN_C);
        c.drawPath(p, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        fill.setColorFilter(cf);
        line.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
