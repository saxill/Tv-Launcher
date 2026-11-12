package com.sahil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Big thin gradient clock with an iOS-style "depth" effect: the clock is drawn first, then the
 * wallpaper's foreground (everything below the detected skyline) is drawn over it, so hills and
 * trees sit in front of the digits. Cheap: one 240x135 luminance pass per wallpaper.
 */
class HeroView extends View {
    private final float S;
    private String time = "", info = "";
    private final Paint clockP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dateP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgeP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap wall;
    private Path fg;
    private Bitmap fgBmp;
    private boolean fgGiven;
    private int accentTop = 0xFFFFFFFF, accentMid = 0xFFFFDCE3, accentBot = 0xFFF3A5B8;
    private float[] sky; // skyline per column, as a fraction of height
    private boolean drawBase;
    private float topDesign = 165, dx, dy, digH;

    HeroView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        S = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        Typeface tf;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) // light weight on the variable font
                tf = new Typeface.Builder(c.getAssets(), "Antonio.ttf").setFontVariationSettings("'wght' 230").build();
            else tf = Typeface.createFromAsset(c.getAssets(), "Antonio.ttf");
            if (tf == null) tf = Typeface.createFromAsset(c.getAssets(), "Antonio.ttf");
        } catch (Throwable t) { tf = Typeface.create("sans-serif-condensed", Typeface.NORMAL); }
        clockP.setTypeface(tf);
        
        clockP.setTextAlign(Paint.Align.CENTER);
        edgeP.setStyle(Paint.Style.STROKE);
        edgeP.setTextAlign(Paint.Align.CENTER);
        // size the digits so they are 27% of the screen tall (tall, condensed, Apple-TV-night style)
        float screenH = c.getResources().getDisplayMetrics().heightPixels;
        clockP.setTextSize(100f);
        android.graphics.Rect rb = new android.graphics.Rect();
        clockP.getTextBounds("0", 0, 1, rb);
        digH = 0.27f * screenH;
        clockP.setTextSize(100f * digH / Math.max(1, rb.height()));
        float sx = 1.87f * digH / Math.max(1f, clockP.measureText("8:47"));
        clockP.setTextScaleX(Math.max(0.9f, Math.min(1.5f, sx)));
        edgeP.setTypeface(tf);
        edgeP.setTextSize(clockP.getTextSize());
        edgeP.setTextScaleX(clockP.getTextScaleX());
        edgeP.setStrokeWidth(Math.max(1.5f, 2 * S));
        edgeP.setColor(0xCCFFFFFF);
        dateP.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        dateP.setTextAlign(Paint.Align.CENTER);
        dateP.setTextSize(30 * S);
        dateP.setColor(0xFFFFFFFF);
        dateP.setShadowLayer(10 * S, 0, 2 * S, 0xCC000000);
    }

    void setDrawBase(boolean b) { drawBase = b; }
    void setTop(float designY) { topDesign = designY; rebuild(); }
    void setOffset(float x, float y) { dx = x; dy = y; invalidate(); }

    void setTexts(String t, String i) { time = t; info = i; invalidate(); }

    void setWall(Bitmap full) { setWall(full, null); }

    void setWall(Bitmap full, Bitmap fgOverride) {
        wall = full;
        sky = null;
        fgGiven = fgOverride != null;
        fgBmp = fgOverride;
        if (full != null) {
            try {
                Bitmap small = Bitmap.createScaledBitmap(full, 240, 135, true);
                if (!fgGiven) sky = skyline(small);
                computeAccent(small);
                if (small != full) small.recycle();
            } catch (Throwable ignored) { }
        }
        rebuild();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { rebuild(); }

    /** Tint the clock from the wallpaper's colours behind it, like the iOS lock screen. */
    private void computeAccent(Bitmap sm) {
        int w = sm.getWidth(), h = sm.getHeight();
        long r = 0, g = 0, b = 0; int n = 0;
        for (int y = (int) (h * 0.12f); y < h * 0.60f; y += 2)
            for (int x = (int) (w * 0.20f); x < w * 0.80f; x += 2) {
                int c = sm.getPixel(x, y);
                int mx = Math.max((c >> 16) & 255, Math.max((c >> 8) & 255, c & 255));
                int mn = Math.min((c >> 16) & 255, Math.min((c >> 8) & 255, c & 255));
                int wgt = 1 + (mx - mn) / 16; // favour colourful pixels over grey/dark ones
                r += ((c >> 16) & 255) * wgt; g += ((c >> 8) & 255) * wgt; b += (c & 255) * wgt; n += wgt;
            }
        if (n == 0) return;
        float[] hsv = new float[3];
        android.graphics.Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
        float sat = Math.max(0.28f, Math.min(0.62f, hsv[1] * 1.15f));
        accentTop = android.graphics.Color.HSVToColor(new float[]{hsv[0], 0.03f, 1f});
        accentMid = android.graphics.Color.HSVToColor(new float[]{hsv[0], sat * 0.22f, 1f});
        accentBot = android.graphics.Color.HSVToColor(new float[]{hsv[0], Math.min(sat * 0.5f, 0.2f), 1f});
    }

    private float digitH() { return digH; }
    private float baseline() { return topDesign * S + digitH(); }

    private void rebuild() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float top = topDesign * S, base = baseline();
        // frosted glass: the wallpaper blurred and brightened, filling the digits
        if (wall != null) {
            try {
                Bitmap tiny = Bitmap.createScaledBitmap(wall, 128, 72, true);
                Bitmap blur = Bitmap.createScaledBitmap(tiny, Math.max(1, w / 4), Math.max(1, h / 4), true);
                tiny.recycle();
                Bitmap g = Bitmap.createBitmap(blur.getWidth(), blur.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas gc = new Canvas(g);
                Paint gp = new Paint();
                gp.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{
                        1.18f, 0, 0, 0, 14, 0, 1.18f, 0, 0, 14, 0, 0, 1.18f, 0, 14, 0, 0, 0, 1, 0}));
                gc.drawBitmap(blur, 0, 0, gp);
                gc.drawColor(0x0AFFFFFF); // clear glass: no frost
                blur.recycle();
                android.graphics.BitmapShader sh = new android.graphics.BitmapShader(g, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                android.graphics.Matrix mx = new android.graphics.Matrix();
                mx.setScale(4f, 4f);
                sh.setLocalMatrix(mx);
                clockP.setFilterBitmap(true); clockP.setShader(sh);
            } catch (Throwable t) { clockP.setShader(null); clockP.setColor(0xE6FFFFFF); }
        } else { clockP.setShader(null); clockP.setColor(0xE6FFFFFF); }
        edgeP.setShader(new LinearGradient(0, top, 0, base, 0xF2FFFFFF, 0x40FFFFFF, Shader.TileMode.CLAMP));
        fg = null;
        if (!fgGiven) { if (fgBmp != null) fgBmp.recycle(); fgBmp = null; }
        if (sky == null) { invalidate(); return; }
        int n = sky.length;
        // how much of the clock would end up behind the foreground?
        float tw = clockP.measureText("00:00") * 0.9f;
        float cover = 0; int cnt = 0;
        for (int i = 0; i < n; i++) {
            float x = i * (float) w / (n - 1);
            if (Math.abs(x - w / 2f) > tw / 2) continue;
            float sy = sky[i] * h;
            cover += Math.max(0, Math.min(1, (base - Math.max(sy, top)) / (base - top)));
            cnt++;
        }
        cover = cnt == 0 ? 0 : cover / cnt;
        if (cover > 0.02f && cover < 0.6f) {
            Path p = new Path();
            p.moveTo(0, h);
            for (int i = 0; i < n; i++) p.lineTo(i * (float) w / (n - 1), sky[i] * h);
            p.lineTo(w, h);
            p.close();
            fg = p;
            try {
                Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
                new Canvas(mask).drawPath(p, new Paint(Paint.ANTI_ALIAS_FLAG));
                Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas oc = new Canvas(out);
                oc.drawBitmap(wall, null, new android.graphics.Rect(0, 0, w, h), bmpP);
                Paint dp = new Paint();
                dp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                oc.drawBitmap(mask, 0, 0, dp);
                mask.recycle();
                fgBmp = out;
            } catch (Throwable t) { fgBmp = null; }
        }
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (drawBase && wall != null) c.drawBitmap(wall, null, new android.graphics.Rect(0, 0, w, h), bmpP);
        c.save();
        c.translate(dx, dy);
        c.drawText(time, w / 2f, baseline(), clockP);
        c.drawText(time, w / 2f, baseline(), edgeP);
        c.restore();
        if (fgBmp != null) c.drawBitmap(fgBmp, 0, 0, null);
        c.drawText(info, w / 2f + dx, topDesign * S - 20 * S + dy, dateP);
    }

    /** Skyline row per column (fraction of height): first strong brightening-to-dark edge going down. */
    static float[] skyline(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        float[][] L = new float[h][w];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = px[y * w + x];
                L[y][x] = 0.3f * ((p >> 16) & 255) + 0.59f * ((p >> 8) & 255) + 0.11f * (p & 255);
            }
        float[][] B = new float[h][w]; // horizontal blur
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float s = 0; int n = 0;
                for (int k = -2; k <= 2; k++) { int xx = x + k; if (xx >= 0 && xx < w) { s += L[y][xx]; n++; } }
                B[y][x] = s / n;
            }
        float[] sky = new float[w];
        int maxY = (int) (h * 0.85f);
        for (int x = 0; x < w; x++) {
            sky[x] = 0.97f;
            for (int y = 4; y < maxY - 12; y++) {
                if (B[y + 3][x] - B[y][x] < -22) {
                    float below = 0, above = 0;
                    for (int k = 4; k <= 12; k++) below += B[y + k][x];
                    for (int k = 1; k <= 3; k++) above += B[y - k][x];
                    if (below / 9f < above / 3f - 15f) { sky[x] = (y + 2f) / h; break; }
                }
            }
        }
        float[] med = new float[w];
        float[] win = new float[11];
        for (int x = 0; x < w; x++) {
            int m = 0;
            for (int k = -5; k <= 5; k++) win[m++] = sky[Math.max(0, Math.min(w - 1, x + k))];
            java.util.Arrays.sort(win);
            med[x] = win[5];
        }
        float[] out = new float[w];
        for (int x = 0; x < w; x++) {
            float s = 0; int n = 0;
            for (int k = -2; k <= 2; k++) { int xx = x + k; if (xx >= 0 && xx < w) { s += med[xx]; n++; } }
            out[x] = s / n;
        }
        // only trust clean horizons: most columns found, and a smooth line
        int found = 0; float rough = 0;
        for (int x = 0; x < w; x++) { if (sky[x] < 0.96f) found++; if (x > 0) rough += Math.abs(out[x] - out[x - 1]); }
        if (found < w * 0.75f || rough / w > 0.006f) return null;
        return out;
    }
}
