package com.sahil.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * Fire OS won't let the stock launcher be disabled, handles the Home key itself,
 * and holds app launches for ~5s after Home. When Amazon's home activity appears:
 *  1. instantly cover the screen with an accessibility overlay showing the last
 *     launcher frame (so there's no flash of Amazon's home);
 *  2. build a LIVE, usable HomeView into that overlay so the remote works at once;
 *  3. keep pushing our real launcher to the front until Amazon stops re-grabbing it,
 *     then fade the overlay away.
 * Events are filtered in XML to com.amazon.tv.launcher; no window content is read.
 */
public class HomeRedirectService extends AccessibilityService {
    static HomeRedirectService instance;
    private static long passUntil;

    /** Let Amazon's launcher show for a moment (we opened Settings/Mirroring on purpose). */
    static void passThrough(long ms) { passUntil = SystemClock.uptimeMillis() + ms; }

    private final Handler handler = new Handler();
    private long last;
    private FrameLayout mask;
    private boolean liveAdded;
    private WindowManager wm;

    private boolean launcherResumed, appLaunching;
    private long lastHome;

    private final Runnable safety = new Runnable() { public void run() { hideMask(false); } };
    // Keep nudging our launcher to the front until it's actually resumed (Fire OS queues it).
    private final Runnable retry = new Runnable() {
        public void run() {
            if (mask == null || launcherResumed || appLaunching) return;
            relaunch();
            handler.postDelayed(this, 1000);
        }
    };
    // Reveal only after our launcher has stayed in front for a moment (Amazon likes to re-grab).
    private final Runnable reveal = new Runnable() {
        public void run() { if (launcherResumed && !appLaunching) hideMask(true); }
    };

    @Override protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override public void onDestroy() {
        hideMask(false);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        CharSequence cls = e.getClassName();
        if (pkg == null || !"com.amazon.tv.launcher".contentEquals(pkg)) return;
        if (cls == null || !cls.toString().contains("HomeActivity")) return;
        long now = SystemClock.uptimeMillis();
        android.util.Log.w("HomeRedirect", "amazon home event text=" + e.getText()
                + (now < passUntil ? " (pass-through)" : ""));
        if (now < passUntil) return; // we just opened a system screen on purpose
        if (now - last < 150) return;
        last = now;
        lastHome = now;
        launcherResumed = false;
        appLaunching = false;
        handler.removeCallbacks(reveal);
        showMask();
        relaunch();
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 1000);
    }

    /** MainActivity is the resumed (front) activity. */
    void onLauncherResumed() {
        launcherResumed = true;
        handler.removeCallbacks(reveal);
        if (mask != null) handler.postDelayed(reveal, 900);
    }

    /** MainActivity lost the front (e.g. Amazon re-grabbed it, or an app opened). */
    void onLauncherPaused() {
        launcherResumed = false;
        handler.removeCallbacks(reveal);
    }

    /** User opened an app or Settings from the live overlay. */
    void onAppLaunchedFromOverlay() {
        appLaunching = true;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(reveal);
        // Fire OS releases queued launches ~5s after the last Home press; lift the overlay then.
        long wait = Math.max(0, lastHome + 5200 - SystemClock.uptimeMillis()) + 700;
        handler.removeCallbacks(safety);
        handler.postDelayed(safety, wait);
    }

    private void relaunch() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { startActivity(i); } catch (Exception ignored) { }
    }

    private void showMask() {
        if (wm == null) return;
        if (mask != null) { ensureLive(); return; }

        FrameLayout container = new FrameLayout(this) {
            @Override public boolean dispatchKeyEvent(KeyEvent ev) {
                // Until the live view is in, swallow keys so nothing hits Amazon's hidden screen.
                return super.dispatchKeyEvent(ev) || !liveAdded;
            }
        };
        container.setFocusable(true);
        container.setFocusableInTouchMode(true);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        Bitmap b = coverBitmap();
        if (b != null) img.setImageBitmap(b);
        else img.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1016, 0xFF18222E, 0xFF0A0F14}));
        container.addView(img, new FrameLayout.LayoutParams(-1, -1));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        try { wm.addView(container, lp); mask = container; liveAdded = false; }
        catch (Exception ignored) { return; }
        // Build the live launcher on the next frame, so the cover shows immediately first.
        container.post(new Runnable() { public void run() { ensureLive(); } });
        bump();
    }

    private void ensureLive() {
        if (mask == null || liveAdded) return;
        try {
            HomeView live = new HomeView(this, false, HomeView.lastFocusedPkg);
            mask.addView(live, new FrameLayout.LayoutParams(-1, -1));
            live.requestFocus();
            liveAdded = true;
        } catch (Throwable ignored) { }
    }

    private Bitmap coverBitmap() {
        if (MainActivity.snapshot != null) return MainActivity.snapshot;
        if (HomeView.cover != null) return HomeView.cover;
        try {
            File f = new File(getCacheDir(), "wall.jpg");
            if (f.exists()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeFile(f.getPath(), o);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private void bump() {
        handler.removeCallbacks(safety);
        handler.postDelayed(safety, 15000); // hard cap so it can never get stuck
    }

    void hideMask(boolean fade) {
        handler.removeCallbacks(safety);
        final View v = mask;
        if (v == null) return;
        mask = null;
        liveAdded = false;
        appLaunching = false;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(reveal);
        if (fade) {
            v.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                public void run() { remove(v); }
            }).start();
        } else remove(v);
    }

    private void remove(View v) {
        try { wm.removeViewImmediate(v); } catch (Exception ignored) { }
    }

    @Override public void onInterrupt() { }
}
