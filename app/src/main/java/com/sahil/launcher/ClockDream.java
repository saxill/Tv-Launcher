package com.sahil.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.service.dreams.DreamService;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Screensaver: the wallpaper with the big depth clock, drifting slowly to avoid burn-in. */
public class ClockDream extends DreamService {
    private HeroView hero;
    private final Handler h = new Handler();
    private final SimpleDateFormat tf = new SimpleDateFormat("h:mm", Locale.getDefault());
    private final SimpleDateFormat df = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());
    private final Runnable tick = new Runnable() {
        public void run() {
            SharedPreferences p = getSharedPreferences("home", Context.MODE_PRIVATE);
            String wx = p.getString("weather", "");
            Date now = new Date();
            hero.setTexts(tf.format(now), df.format(now) + (wx.isEmpty() ? "" : "   •   " + wx));
            long m = now.getTime() / 60000;
            hero.setOffset((float) Math.sin(m / 7.0) * 60, (float) Math.cos(m / 5.0) * 40);
            h.postDelayed(this, 15000);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        hero = new HeroView(this);
        hero.setDrawBase(true);
        hero.setBackgroundColor(0xFF0B1016);
        setContentView(hero);
        try {
            File f = new File(getCacheDir(), "wall.jpg");
            if (f.exists()) {
                int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 1;
                Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
                if (src != null) {
                    Bitmap scr = Bitmap.createScaledBitmap(src, W, H, true);
                    if (scr != src) src.recycle();
                    hero.setWall(scr);
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override public void onDreamingStarted() { super.onDreamingStarted(); h.post(tick); }
    @Override public void onDreamingStopped() { h.removeCallbacks(tick); super.onDreamingStopped(); }
}
