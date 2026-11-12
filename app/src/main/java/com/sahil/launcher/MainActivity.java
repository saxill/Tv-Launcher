package com.sahil.launcher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;

/**
 * Thin host for HomeView. The same HomeView is shown by HomeRedirectService as a
 * live overlay the instant Home is pressed, so the launcher feels immediate even
 * though Fire OS holds this activity's launch for a few seconds.
 */
public class MainActivity extends Activity {
    static Bitmap snapshot; // last full launcher frame, used by HomeRedirectService as an instant cover
    private HomeView home;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        home = new HomeView(this, true, null);
        setContentView(home);
    }

    @Override protected void onResume() {
        super.onResume();
        if (HomeRedirectService.instance != null) HomeRedirectService.instance.onLauncherResumed();
    }

    @Override protected void onPause() {
        if (HomeRedirectService.instance != null) HomeRedirectService.instance.onLauncherPaused();
        try {
            int w = home.getWidth(), h = home.getHeight();
            if (w > 0 && h > 0) {
                if (snapshot == null || snapshot.getWidth() != w || snapshot.getHeight() != h)
                    snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                home.draw(new Canvas(snapshot));
            }
        } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.toTop();
    }

    @Override public void onBackPressed() {
        if (home != null) home.toTop();
    }
}
