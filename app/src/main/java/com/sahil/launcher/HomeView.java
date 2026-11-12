package com.sahil.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The entire home screen as a self-contained view: photo wallpaper, clock,
 * settings/wifi icons, frosted-glass dock and the rest of the apps below.
 * Used both as MainActivity's content and, by HomeRedirectService, as a live
 * overlay so the Home button feels instant despite Fire OS's launch delay.
 * Manages its own clock/wallpaper/package-change lifecycle via attach/detach.
 */
public class HomeView extends FrameLayout {
    private static final int COLS = 10;
    /** TV overscan-safe margins (~5% of a 1920x1080 screen), in design px. */
    private static final int SAFE_X = 96, SAFE_Y = 54, DOCK_PAD = 22;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2.2f);
    private static final long ROTATE_MS = 3 * 60 * 60 * 1000L;

    /** Last app the user had highlighted, so the overlay and the real launcher stay in sync. */
    static String lastFocusedPkg;
    /** Screen-sized wallpaper frame, so the overlay can cover instantly before the live UI builds. */
    static Bitmap cover;

    private static class App { String pkg, label; Drawable banner, icon; }

    /** Installed apps the user chose not to see on the home screen. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
            "com.aajtak.firetv", "com.abp.firetv", "com.firestick.shemaroome",
            "com.amazon.android.kidsfirst"));

    /** System apps still worth showing: Prime Video and the Appstore (needed to install apps). */
    private static final Set<String> KEEP_SYSTEM = new HashSet<>(Arrays.asList(
            "com.amazon.firebat", "com.amazon.avod", "com.amazon.avod.thirdpartyclient",
            "com.amazon.venezia"));

    private final Handler handler = new Handler();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm", Locale.getDefault());

    private final int W, H;
    private final float S; // scale vs a 1920px-wide design
    private boolean attached;
    private boolean animateIn;
    private String initialFocusPkg;

    private FrameLayout rootScrim;
    private ImageView wallBack, wallFront;
    private Bitmap wallBitmap, blurBitmap;
    private TextView clock, credit;
    private HeroView hero;
    private View sysGear, sysCast, dotsBtn, menu;
    private WifiIcon wifi;
    private ScrollView scroll;
    private LinearLayout content;
    private GlassView dock;
    private final SharedPreferences prefs;
    private List<App> apps = new ArrayList<>();
    private String refocusTag;
    private boolean firstRender = true;

    private final Runnable tick = new Runnable() {
        public void run() {
            clock.setText(timeFmt.format(new Date()));
            updateHero();
            wifi.setConnected(isOnline());
            handler.postDelayed(this, 15_000);
        }
    };
    private final Runnable rotate = new Runnable() {
        public void run() { refreshWallpaper(); handler.postDelayed(this, ROTATE_MS); }
    };
    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { load(); }
    };

    HomeView(Context ctx, boolean animateIn, String initialFocusPkg) {
        super(ctx);
        this.animateIn = animateIn;
        this.initialFocusPkg = initialFocusPkg != null ? initialFocusPkg : lastFocusedPkg;
        prefs = ctx.getSharedPreferences("home", Context.MODE_PRIVATE);
        W = getResources().getDisplayMetrics().widthPixels;
        H = getResources().getDisplayMetrics().heightPixels;
        S = W / 1920f;
        setFocusable(true);
        setFocusableInTouchMode(true);
        build();
        loadCachedWallpaper();
        load();
    }

    private int px(float design) { return Math.round(design * S); }

    private void build() {
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1016, 0xFF18222E, 0xFF0A0F14}));

        wallBack = wallView();
        wallFront = wallView();
        addView(wallBack, new LayoutParams(-1, -1));
        addView(wallFront, new LayoutParams(-1, -1));
        hero = new HeroView(getContext());
        addView(hero, new LayoutParams(-1, -1));

        View topShade = new View(getContext());
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x00000000}));
        addView(topShade, new LayoutParams(-1, px(240), Gravity.TOP));
        View botShade = new View(getContext());
        botShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0x99000000, 0x00000000}));
        addView(botShade, new LayoutParams(-1, px(420), Gravity.BOTTOM));

        scroll = new ScrollView(getContext());
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, -1));

        FrameLayout top = new FrameLayout(getContext());
        LinearLayout leftIcons = new LinearLayout(getContext());
        leftIcons.setOrientation(LinearLayout.HORIZONTAL);
        leftIcons.setGravity(Gravity.CENTER_VERTICAL);
        GearButton gear = new GearButton(getContext());
        sysGear = gear;
        gear.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { hideMenu(); openSettings(); }
        });
        leftIcons.addView(gear, new LinearLayout.LayoutParams(px(150), px(64)));
        wifi = new WifiIcon(getContext());
        wifi.setFocusable(true);
        wifi.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                hideMenu();
                openFirst("Wi-Fi settings", new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        });
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(px(120), px(64));
        wp.leftMargin = px(18);
        leftIcons.addView(wifi, wp);
        CastButton cast = new CastButton(getContext());
        sysCast = cast;
        cast.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { hideMenu(); openMirroring(); }
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(px(176), px(64));
        mp.leftMargin = px(18);
        leftIcons.addView(cast, mp);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(px(SAFE_X), px(SAFE_Y - 14), 0, 0);

        LinearLayout right = new LinearLayout(getContext());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        clock = text(44, Color.WHITE, "sans-serif-medium");
        clock.setShadowLayer(px(8), 0, px(1), 0x80000000);
        clock.setText(timeFmt.format(new Date()));
        credit = text(22, 0xD9FFFFFF, "sans-serif");
        credit.setShadowLayer(px(6), 0, px(1), 0x99000000);
        credit.setSingleLine(true);
        credit.setEllipsize(TextUtils.TruncateAt.END);
        credit.setMaxWidth(px(900));
        credit.setGravity(Gravity.END);
        credit.setText(prefs.getString("credit", ""));
        credit.setVisibility(View.GONE); // no watermark
        right.addView(clock);
        LayoutParams rp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rp.setMargins(0, px(SAFE_Y - 12), px(SAFE_X), 0);
        top.addView(right, rp);
        LayoutParams cp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, px(SAFE_Y + 86), px(SAFE_X), 0);
        top.addView(credit, cp);
        clock.setVisibility(View.GONE);
        updateHero();
        fetchWeather();
        dotsBtn = new DotsButton(getContext());
        dotsBtn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { if (menu != null && menu.getParent() != null) hideMenu(); else showMenu(); }
        });
        LayoutParams dp = new LayoutParams(px(64), px(64), Gravity.TOP | Gravity.END);
        dp.setMargins(0, px(SAFE_Y - 14), px(SAFE_X), 0);
        top.addView(dotsBtn, dp);
        addView(top, new LayoutParams(-1, -2, Gravity.TOP));

        scroll.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
            public void onScrollChanged() { if (dock != null) dock.invalidate(); }
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        try { getContext().registerReceiver(pkgReceiver, f); } catch (Exception ignored) { }
        handler.post(tick);
        handler.postDelayed(rotate, ROTATE_MS);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        try { getContext().unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        handler.removeCallbacksAndMessages(null);
        super.onDetachedFromWindow();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) { if (menu != null && menu.getParent() != null) hideMenu(); else toTop(); }
            return true; // stay home; never fall through to whatever is behind
        }
        return super.dispatchKeyEvent(e);
    }

    void toTop() {
        scroll.smoothScrollTo(0, 0);
        View first = dock != null ? firstCard(dock) : null;
        if (first != null) first.requestFocus();
    }

    // ================= wallpaper =================
    private ImageView wallView() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.FIT_XY);
        return v;
    }

    private File wallFile() { return new File(getContext().getCacheDir(), "wall.jpg"); }

    private void ui(Runnable r) { post(r); }

    private static final String[] SCENES = {"night", "golden", "fog", "dawn"};
    private Bitmap sceneFg;

    private void loadCachedWallpaper() { loadScene(false); }

    /** Rotate to the next bundled scene (photo + foreground cut-out for the clock-behind-scenery look). */
    private void refreshWallpaper() { loadScene(true); }

    private void loadScene(final boolean advance) {
        bg.execute(new Runnable() {
            public void run() {
                try {
                    int idx = prefs.getInt("scene", 0) % SCENES.length;
                    if (advance) { idx = (idx + 1) % SCENES.length; prefs.edit().putInt("scene", idx).apply(); }
                    String n = SCENES[idx];
                    File f = wallFile();
                    java.io.InputStream in = getContext().getAssets().open("scenes/" + n + ".jpg");
                    java.io.FileOutputStream out = new java.io.FileOutputStream(f);
                    byte[] buf = new byte[16384]; int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    out.close(); in.close();
                    final Bitmap[] wb = prepare(f);
                    Bitmap fg = null;
                    try {
                        java.io.InputStream fi = getContext().getAssets().open("scenes/" + n + "-fg.webp");
                        Bitmap raw = BitmapFactory.decodeStream(fi);
                        fi.close();
                        if (raw != null) { fg = Bitmap.createScaledBitmap(raw, W, H, true); if (fg != raw) raw.recycle(); }
                    } catch (Throwable ignored) { }
                    final Bitmap fgF = fg;
                    prefs.edit().putLong("wallTime", System.currentTimeMillis()).putString("credit", "").apply();
                    if (wb != null) ui(new Runnable() { public void run() { sceneFg = fgF; showWallpaper(wb, "", advance); } });
                } catch (Throwable ignored) { }
            }
        });
    }

    private static BitmapFactory.Options sampleOpts() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 2;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return o;
    }

    private Bitmap[] prepare(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= W && o.outHeight / (sample * 2) >= H) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap src = BitmapFactory.decodeFile(f.getPath(), o);
            if (src == null) return null;
            Bitmap screen = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(screen);
            float scale = Math.max(W / (float) src.getWidth(), H / (float) src.getHeight());
            float dw = src.getWidth() * scale, dh = src.getHeight() * scale;
            RectF dst = new RectF((W - dw) / 2f, (H - dh) / 2f, (W + dw) / 2f, (H + dh) / 2f);
            c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            Bitmap tiny = Bitmap.createScaledBitmap(screen, Math.max(1, W / 40), Math.max(1, H / 40), true);
            Bitmap mid = Bitmap.createScaledBitmap(tiny, W / 8, H / 8, true);
            tiny.recycle();
            return new Bitmap[]{screen, mid};
        } catch (Throwable t) { return null; }
    }

    private void showWallpaper(Bitmap[] b, String cr, boolean animate) {
        Bitmap oldWall = wallBitmap;
        wallBitmap = b[0];
        blurBitmap = b[1];
        cover = wallBitmap;
        if (oldWall != null) wallBack.setImageBitmap(oldWall);
        wallFront.setImageBitmap(wallBitmap);
        if (animate || oldWall == null) {
            wallFront.setAlpha(0f);
            wallFront.animate().alpha(1f).setDuration(700).setInterpolator(EASE).start();
        }
        hero.setWall(wallBitmap, sceneFg);
        credit.setText(cr);
        if (dock != null) dock.invalidate();
    }

    private static String get(String u) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    private static void download(String u, File to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally { c.disconnect(); }
    }

    // ================= apps =================
    private void load() {
        bg.execute(new Runnable() {
            public void run() {
                final List<App> list = queryApps();
                ui(new Runnable() { public void run() { apps = list; render(); } });
            }
        });
    }

    private List<App> queryApps() {
        PackageManager pm = getContext().getPackageManager();
        Map<String, ResolveInfo> found = new LinkedHashMap<>();
        for (String cat : new String[]{"android.intent.category.LEANBACK_LAUNCHER", Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || found.containsKey(pkg)) continue;
                boolean system = (ri.activityInfo.applicationInfo.flags
                        & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !KEEP_SYSTEM.contains(pkg)) continue; // hide Amazon's built-in extras
                if (HIDDEN.contains(pkg)) continue;
                found.put(pkg, ri);
            }
        }
        List<App> list = new ArrayList<>();
        for (ResolveInfo ri : found.values()) {
            ActivityInfo ai = ri.activityInfo;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = ri.loadLabel(pm).toString();
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.banner = banner;
            if (banner == null) a.icon = ri.loadIcon(pm);
            list.add(a);
        }
        Collections.sort(list, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });
        return list;
    }

    private List<String> favorites() {
        List<String> out = new ArrayList<>();
        for (String p : prefs.getString("fav", "").split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void toggleFavorite(String pkg) {
        List<String> f = favorites();
        boolean adding = !f.contains(pkg);
        if (adding) f.add(pkg); else f.remove(pkg);
        prefs.edit().putString("fav", TextUtils.join(",", f)).apply();
        refocusTag = pkg;
        Toast.makeText(getContext(), adding ? "Pinned to dock" : "Removed from dock", Toast.LENGTH_SHORT).show();
        render();
    }

    // ================= "Keep watching" =================
    private List<String> recentPkgs() {
        List<String> out = new ArrayList<>();
        try { // apps used recently anywhere (needs the usage-access grant; silently empty otherwise)
            android.app.usage.UsageStatsManager u = (android.app.usage.UsageStatsManager)
                    getContext().getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<android.app.usage.UsageStats> l = u.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 7L * 86400000L, now);
            if (l != null) {
                java.util.Collections.sort(l, new java.util.Comparator<android.app.usage.UsageStats>() {
                    public int compare(android.app.usage.UsageStats a, android.app.usage.UsageStats b) {
                        return Long.compare(b.getLastTimeUsed(), a.getLastTimeUsed());
                    }
                });
                for (android.app.usage.UsageStats us : l)
                    if (us.getLastTimeUsed() > 0 && !out.contains(us.getPackageName())) out.add(us.getPackageName());
            }
        } catch (Throwable ignored) { }
        for (String p : prefs.getString("recent", "").split(",")) if (!p.isEmpty() && !out.contains(p)) out.add(p);
        return out;
    }

    private void noteLaunched(String pkg) {
        List<String> r = new ArrayList<>(Arrays.asList(prefs.getString("recent", "").split(",")));
        r.remove(pkg); r.remove("");
        r.add(0, pkg);
        while (r.size() > 8) r.remove(r.size() - 1);
        prefs.edit().putString("recent", TextUtils.join(",", r)).apply();
    }

    private View buildRecents(Map<String, App> byPkg) {
        List<App> picks = new ArrayList<>();
        for (String p : recentPkgs()) {
            App a = byPkg.get(p);
            if (a != null && picks.size() < 3) picks.add(a);
        }
        if (picks.isEmpty()) return null;
        LinearLayout col = new LinearLayout(getContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(SAFE_X + DOCK_PAD), 0, 0, 0);
        col.setGravity(Gravity.BOTTOM);
        TextView label = text(24, 0xE6FFFFFF, "sans-serif-medium");
        label.setShadowLayer(px(8), 0, px(1), 0x99000000);
        label.setText("Keep watching");
        col.addView(label);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(12), 0, px(14));
        row.setClipChildren(false);
        row.setClipToPadding(false);
        for (final App a : picks) {
            final LinearLayout chip = new LinearLayout(getContext());
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(px(18), 0, px(28), 0);
            chip.setFocusable(true);
            chip.setClickable(true);
            chip.setTag("recent:" + a.pkg);
            final GradientDrawable bgd = new GradientDrawable();
            bgd.setCornerRadius(px(22));
            bgd.setColor(0x4D000000);
            bgd.setStroke(px(1), 0x33FFFFFF);
            chip.setBackground(bgd);
            ImageView iv = new ImageView(getContext());
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(a.icon != null ? a.icon : a.banner);
            chip.addView(iv, new LinearLayout.LayoutParams(px(56), px(56)));
            LinearLayout tc = new LinearLayout(getContext());
            tc.setOrientation(LinearLayout.VERTICAL);
            tc.setPadding(px(18), 0, 0, 0);
            TextView sub = text(15, 0xB3FFFFFF, "sans-serif-medium");
            sub.setText("CONTINUE");
            sub.setLetterSpacing(0.12f);
            TextView t = text(26, Color.WHITE, "sans-serif-medium");
            t.setText(a.label);
            t.setSingleLine(true);
            tc.addView(sub);
            tc.addView(t);
            chip.addView(tc, new LinearLayout.LayoutParams(px(230), -2));
            chip.setOnFocusChangeListener(new OnFocusChangeListener() {
                public void onFocusChange(View v, boolean f) {
                    bgd.setColor(f ? 0xB3FFFFFF : 0x4D000000);
                    LinearLayout tcol = (LinearLayout) ((LinearLayout) v).getChildAt(1);
                    ((TextView) tcol.getChildAt(0)).setTextColor(f ? 0x99111111 : 0xB3FFFFFF);
                    ((TextView) tcol.getChildAt(1)).setTextColor(f ? 0xFF111111 : Color.WHITE);
                    v.animate().scaleX(f ? 1.06f : 1f).scaleY(f ? 1.06f : 1f).setDuration(140).start();
                }
            });
            chip.setOnClickListener(new OnClickListener() { public void onClick(View v) { launch(a.pkg); } });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, px(76));
            cp.rightMargin = px(20);
            row.addView(chip, cp);
        }
        col.addView(row);
        return col;
    }

    // ================= layout =================
    private int cardW() { return px(148); }
    private int cardH() { return px(83); }
    /** Spread 6 cards exactly across the safe width, leaving room for the focus zoom. */
    private int gap() { return (W - 2 * px(SAFE_X) - 2 * px(DOCK_PAD) - COLS * cardW()) / (COLS - 1); }

    private void render() {
        content.removeAllViews();
        Map<String, App> byPkg = new HashMap<>();
        for (App a : apps) byPkg.put(a.pkg, a);
        List<App> dockApps = new ArrayList<>();
        for (String p : favorites()) if (byPkg.containsKey(p)) dockApps.add(byPkg.get(p));
        boolean usingFavs = !dockApps.isEmpty();
        if (!usingFavs) for (int i = 0; i < Math.min(COLS, apps.size()); i++) dockApps.add(apps.get(i));
        List<App> rest = new ArrayList<>();
        for (App a : apps) if (!dockApps.contains(a)) rest.add(a);

        final int slots = COLS;
        List<App> first = new ArrayList<>(dockApps.subList(0, Math.min(slots, dockApps.size())));
        List<App> more = new ArrayList<>();
        if (dockApps.size() > slots) more.addAll(dockApps.subList(slots, dockApps.size()));
        more.addAll(rest);
        while (first.size() < slots && !more.isEmpty()) first.add(more.remove(0));
        rest = more;
        int dockPadV = px(20);
        int dockH = cardH() + dockPadV * 2;
        int dockTop = H - px(SAFE_Y + 50) - dockH;
        View recents = buildRecents(byPkg);
        int recH = recents == null ? 0 : px(150);
        content.setPadding(0, dockTop - recH, 0, px(120));
        if (recents != null) content.addView(recents, new LinearLayout.LayoutParams(-1, recH));

        dock = new GlassView(getContext());
        dock.setClipChildren(false);
        dock.setClipToPadding(false); // let the zoomed edge card spill into the padding
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(px(DOCK_PAD), dockPadV, px(DOCK_PAD), dockPadV);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, dockH);
        dlp.setMargins(px(SAFE_X), 0, px(SAFE_X), 0);
        content.addView(dock, dlp);
        addRows(dock, first, true);
        LinearLayout dockRow = dock.getChildCount() > 0 ? (LinearLayout) dock.getChildAt(0) : null;
        if (dockRow == null) {
            dockRow = new LinearLayout(getContext());
            dockRow.setOrientation(LinearLayout.HORIZONTAL);
            dockRow.setClipChildren(false);
            dock.addView(dockRow, new LinearLayout.LayoutParams(-1, cardH()));
        }
        if (firstRender && animateIn) {
            dock.setAlpha(0f);
            dock.setTranslationY(px(40));
            dock.animate().alpha(1f).translationY(0).setDuration(420).setInterpolator(EASE).start();
        }

        LinearLayout below = new LinearLayout(getContext());
        below.setOrientation(LinearLayout.VERTICAL);
        below.setClipChildren(false);
        below.setClipToPadding(false);
        below.setPadding(px(SAFE_X + DOCK_PAD), px(50), px(SAFE_X + DOCK_PAD), 0);
        content.addView(below, new LinearLayout.LayoutParams(-1, -2));
        addRows(below, rest, false);
        if (firstRender && animateIn) {
            below.setAlpha(0f);
            below.animate().alpha(1f).setStartDelay(150).setDuration(420).start();
        }
        firstRender = false;

        View target = null;
        if (refocusTag != null) target = content.findViewWithTag(refocusTag);
        if (target == null && initialFocusPkg != null) target = content.findViewWithTag(initialFocusPkg);
        refocusTag = null;
        initialFocusPkg = null;
        if (target == null) target = firstCard(dock);
        if (target != null) target.requestFocus();
    }

    private void addRows(LinearLayout parent, List<App> list, boolean inDock) {
        LinearLayout row = null;
        for (int i = 0; i < list.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, cardH());
                if (i > 0) rp.topMargin = px(inDock ? 30 : 46);
                parent.addView(row, rp);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cardW(), cardH());
            if (i % COLS != 0) p.leftMargin = gap();
            row.addView(card(list.get(i), inDock), p);
        }
    }

    private View firstCard(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getTag() instanceof String && c.isFocusable()) return c;
            if (c instanceof ViewGroup) { View r = firstCard((ViewGroup) c); if (r != null) return r; }
        }
        return null;
    }

    private View card(final App app, final boolean inDock) {
        final FrameLayout card = new FrameLayout(getContext());
        card.setFocusable(true);
        card.setTag(app.pkg);
        final int r = px(14);

        GradientDrawable face = new GradientDrawable();
        face.setCornerRadius(r);
        face.setColor(app.banner != null ? 0xFF1A1A1A : 0xE6262A30);
        card.setBackground(face);

        if (app.banner != null) {
            ImageView iv = new ImageView(getContext());
            iv.setImageDrawable(app.banner);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setOutlineProvider(round(r));
            iv.setClipToOutline(true);
            card.addView(iv, new LayoutParams(-1, -1));
        } else {
            LinearLayout inner = new LinearLayout(getContext());
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setGravity(Gravity.CENTER);
            inner.setPadding(px(8), 0, px(8), 0);
            ImageView iv = new ImageView(getContext());
            iv.setImageDrawable(app.icon);
            inner.addView(iv, new LinearLayout.LayoutParams(px(40), px(40)));
            TextView t = text(15, Color.WHITE, "sans-serif-medium");
            t.setText(app.label);
            t.setMaxLines(2);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPadding(px(8), 0, 0, 0);
            inner.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(inner, new LayoutParams(-1, -1));
        }

        final GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(r);
        ring.setStroke(px(4), 0xF2FFFFFF);
        ring.setAlpha(0);
        card.setForeground(ring);

        card.setOnFocusChangeListener(new OnFocusChangeListener() {
            public void onFocusChange(View v, boolean f) {
                v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f)
                        .setDuration(f ? 170 : 120).setInterpolator(EASE).start();
                ring.setAlpha(f ? 255 : 0);
                if (!f) return;
                lastFocusedPkg = app.pkg;
                if (inDock) { scroll.smoothScrollTo(0, 0); return; }
                int[] loc = new int[2];
                v.getLocationOnScreen(loc);
                if (loc[1] + v.getHeight() > H - px(60) || loc[1] < px(200)) {
                    scroll.smoothScrollBy(0, loc[1] - (int) (H * 0.5f));
                }
            }
        });
        card.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { launch(app.pkg); }
        });
        card.setOnKeyListener(new OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP) {
                    toggleFavorite(app.pkg); return true;
                }
                return false;
            }
        });
        card.setOnLongClickListener(new OnLongClickListener() {
            public boolean onLongClick(View v) { toggleFavorite(app.pkg); return true; }
        });
        return card;
    }

    // ================= custom views =================
    private class GlassView extends LinearLayout {
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final int[] loc = new int[2];
        GlassView(Context c) {
            super(c);
            setWillNotDraw(false);
            tint.setColor(0x4D3A3A3A);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(Math.max(1, px(2)));
            edge.setColor(0x40FFFFFF);
        }
        @Override protected void onDraw(Canvas c) {
            float rad = px(30);
            rect.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(rect, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (blurBitmap != null) {
                getLocationOnScreen(loc);
                c.drawBitmap(blurBitmap, null, new RectF(-loc[0], -loc[1], W - loc[0], H - loc[1]), bmpPaint);
                tint.setColor(0x593C3C3C);
            } else tint.setColor(0x66202630);
            c.drawRect(rect, tint);
            c.restore();
            float h = edge.getStrokeWidth() / 2f;
            rect.inset(h, h);
            c.drawRoundRect(rect, rad, rad, edge);
        }
    }

    /** Square glass tile with a text label, like the Settings tile in couch-tv's app grid. */
    private abstract class GlassIcon extends View {
        private final Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        GlassIcon(Context c) {
            super(c);
            setFocusable(true);
            tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            tp.setTextAlign(Paint.Align.CENTER);
            setOnFocusChangeListener(new OnFocusChangeListener() {
                public void onFocusChange(View v, boolean f) {
                    v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(140).start();
                    invalidate();
                }
            });
        }
        abstract String label();
        boolean dim() { return false; }
        private final Path clipP = new Path();
        private final int[] iloc = new int[2];
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h * 0.17f;
            boolean f = isFocused();
            RectF rf = new RectF(1, 1, w - 1, h - 1);
            if (f) {
                bgP.setStyle(Paint.Style.FILL);
                bgP.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFFFFFFFF, 0xFFE9E9EE, Shader.TileMode.CLAMP));
                c.drawRoundRect(rf, r, r, bgP);
                bgP.setShader(null);
            } else {
                clipP.reset();
                clipP.addRoundRect(rf, r, r, Path.Direction.CW);
                c.save();
                c.clipPath(clipP);
                bgP.setStyle(Paint.Style.FILL);
                bgP.setColor(0x8C1A1D26); // dark glass tint
                c.drawRoundRect(rf, r, r, bgP);
                bgP.setShader(new android.graphics.LinearGradient(0, 0, 0, h * 0.6f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(rf, r, r, bgP); // soft top sheen
                bgP.setShader(null);
                c.restore();
                bgP.setStyle(Paint.Style.STROKE);
                bgP.setStrokeWidth(Math.max(1.5f, h * 0.022f));
                bgP.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0x66FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
                c.drawRoundRect(rf, r, r, bgP); // light rim, brighter on top
                bgP.setShader(null);
            }
            tp.setTextSize(h * 0.26f);
            tp.setLetterSpacing(0.01f);
            tp.setColor(f ? 0xFF15151A : (dim() ? 0x99FFFFFF : 0xFFFFFFFF));
            if (!f) tp.setShadowLayer(h * 0.08f, 0, h * 0.02f, 0x66000000); else tp.clearShadowLayer();
            c.drawText(label(), w / 2f, h / 2f + tp.getTextSize() * 0.35f, tp);
        }
    }

    private class GearButton extends GlassIcon {
        GearButton(Context c) { super(c); setContentDescription("Settings"); }
        @Override String label() { return "Settings"; }
    }

    private class WifiIcon extends GlassIcon {
        private boolean connected = true;
        WifiIcon(Context c) { super(c); setContentDescription("Wi-Fi"); }
        void setConnected(boolean c) { if (c != connected) { connected = c; invalidate(); } }
        @Override String label() { return "Wi-Fi"; }
        @Override boolean dim() { return !connected; }
    }

    private class CastButton extends GlassIcon {
        CastButton(Context c) { super(c); setContentDescription("Screen mirroring"); }
        @Override String label() { return "Mirroring"; }
    }

    // ================= top-right "..." menu =================
    private void showMenu() {
        GlassView m = new GlassView(getContext());
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(px(12), px(12), px(12), px(12));
        MenuRow r1 = new MenuRow(getContext(), "Settings", 0, null);
        r1.setOnClickListener(new OnClickListener() { public void onClick(View v) { hideMenu(); openSettings(); } });
        MenuRow r2 = new MenuRow(getContext(), "Wi-Fi", 1, isOnline() ? "Connected" : "Offline");
        r2.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { hideMenu(); openFirst("Wi-Fi settings", new Intent(Settings.ACTION_WIFI_SETTINGS)); }
        });
        MenuRow r3 = new MenuRow(getContext(), "Mirroring", 2, null);
        r3.setOnClickListener(new OnClickListener() { public void onClick(View v) { hideMenu(); openMirroring(); } });
        MenuRow[] rows = {r1, r2, r3};
        for (int i = 0; i < rows.length; i++) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(px(360), px(72));
            if (i > 0) tp.topMargin = px(6);
            m.addView(rows[i], tp);
        }
        menu = m;
        LayoutParams mp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        mp.setMargins(0, px(SAFE_Y + 62), px(SAFE_X), 0);
        addView(m, mp);
        m.setAlpha(0f);
        m.setScaleX(0.92f);
        m.setScaleY(0.92f);
        m.post(new Runnable() { public void run() {
            menu.setPivotX(menu.getWidth()); menu.setPivotY(0);
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(EASE).start();
        } });
        r1.requestFocus();
    }

    /** One row of the "..." menu: glyph, label, optional status; focus shows a white pill. */
    private class MenuRow extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label, status;
        private final int kind;
        MenuRow(Context ctx, String label, int kind, String status) {
            super(ctx);
            this.label = label; this.kind = kind; this.status = status;
            setFocusable(true);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            setOnFocusChangeListener(new OnFocusChangeListener() {
                public void onFocusChange(View v, boolean f) { invalidate(); }
            });
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean f = isFocused();
            int ink = f ? 0xFF15151A : 0xFFFFFFFF;
            if (f) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(new RectF(0, 0, w, h), h * 0.3f, h * 0.3f, p);
            }
            float cx = h * 0.62f, cy = h / 2f, u = h * 0.36f;
            p.setColor(ink);
            if (kind == 0) { // gear
                Path g = new Path();
                g.setFillType(Path.FillType.EVEN_ODD);
                int steps = 32;
                for (int i = 0; i < steps; i++) {
                    double a = 2 * Math.PI * i / steps - Math.PI / steps;
                    float r = (i % 4 == 0 || i % 4 == 1) ? u : u * 0.8f;
                    float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                    if (i == 0) g.moveTo(x, y); else g.lineTo(x, y);
                }
                g.close();
                g.addCircle(cx, cy, u * 0.36f, Path.Direction.CW);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(g, p);
            } else if (kind == 1) { // wifi
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u * 0.2f);
                float by = cy + u * 0.55f;
                for (int i = 1; i <= 3; i++) {
                    float r = u * 0.42f * i;
                    c.drawArc(new RectF(cx - r, by - r, cx + r, by + r), 228, 84, false, p);
                }
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, by, u * 0.11f, p);
            } else { // mirroring
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u * 0.17f);
                c.drawRoundRect(new RectF(cx - u, cy - u * 0.72f, cx + u, cy + u * 0.5f), u * 0.22f, u * 0.22f, p);
                float ax = cx - u * 0.62f, ay = cy + u * 0.18f;
                for (int i = 1; i <= 2; i++) {
                    float r = u * 0.26f * i;
                    c.drawArc(new RectF(ax - r, ay - r, ax + r, ay + r), 270, 90, false, p);
                }
                c.drawLine(cx - u * 0.35f, cy + u * 0.82f, cx + u * 0.35f, cy + u * 0.82f, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(h * 0.36f);
            c.drawText(label, h * 1.12f, cy + p.getTextSize() * 0.35f, p);
            if (status != null) {
                p.setTextSize(h * 0.27f);
                p.setColor(f ? 0x99151515 : 0x99FFFFFF);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(status, w - h * 0.38f, cy + p.getTextSize() * 0.35f, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    private void hideMenu() {
        if (menu != null && menu.getParent() != null) {
            ((ViewGroup) menu.getParent()).removeView(menu);
            if (dotsBtn != null) dotsBtn.requestFocus();
        }
    }

    private class DotsButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DotsButton(Context c) {
            super(c);
            setFocusable(true);
            setContentDescription("More");
            setOnFocusChangeListener(new OnFocusChangeListener() {
                public void onFocusChange(View v, boolean f) {
                    v.animate().scaleX(f ? 1.1f : 1f).scaleY(f ? 1.1f : 1f).setDuration(140).start();
                    invalidate();
                }
            });
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean f = isFocused();
            p.setStyle(Paint.Style.FILL);
            p.setColor(f ? 0xFFFFFFFF : 0x99262A36);
            c.drawCircle(w / 2, h / 2, w / 2 - 1, p);
            if (!f) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, w * 0.02f));
                p.setColor(0x33FFFFFF);
                c.drawCircle(w / 2, h / 2, w / 2 - 1, p);
                p.setStyle(Paint.Style.FILL);
            }
            p.setColor(f ? 0xFF15151A : 0xFFFFFFFF);
            for (int i = -1; i <= 1; i++) c.drawCircle(w / 2 + i * w * 0.18f, h / 2, w * 0.055f, p);
        }
    }

    // ================= system screens =================
    /** Fire TV's own Settings app first; plain Android settings only as a fallback. */
    private void openSettings() {
        PackageManager pm = getContext().getPackageManager();
        openFirst("Settings",
                new Intent("amazon.intent.action.MAINSETTINGS").addCategory("android.intent.category.MAINSETTINGS")
                        .setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"),
                new Intent(Settings.ACTION_SETTINGS),
                pm.getLaunchIntentForPackage("com.amazon.tv.settings.v2"));
    }

    // Fire TV's Mirroring is only reachable from the protected long-press-Home menu.
    private void openMirroring() {
        Intent cast = new Intent(Intent.ACTION_MAIN).setClassName("com.amazon.cast.sink",
                "com.amazon.cast.sink.DisplayMirroringSinkActivity");
        HomeRedirectService.passThrough(5000);
        if (safeStart(cast)) return;
        Toast.makeText(getContext(), "Press and hold the Home button, then choose Mirroring",
                Toast.LENGTH_LONG).show();
    }

    private void openFirst(String what, Intent... candidates) {
        PackageManager pm = getContext().getPackageManager();
        for (Intent i : candidates) {
            if (i == null || pm.resolveActivity(i, 0) == null) continue;
            // Fire OS may host these screens inside Amazon's launcher; don't bounce back home.
            HomeRedirectService.passThrough(5000);
            if (safeStart(i)) return;
        }
        Toast.makeText(getContext(), what + " isn't available on this device", Toast.LENGTH_SHORT).show();
    }

    private String weather = "";
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault());

    private void updateHero() {
        if (hero == null) return;
        Date now = new Date();
        hero.setTexts(timeFmt.format(now), dateFmt.format(now));
    }

    private void fetchWeather() {
        weather = prefs.getString("weather", "");
        updateHero();
        new Thread(new Runnable() { public void run() {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                        new java.net.URL("https://wttr.in/?format=%25t+%25C").openConnection();
                c.setConnectTimeout(6000); c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "curl/8");
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream()));
                final String t = r.readLine(); r.close();
                if (t != null && t.length() < 40 && !t.toLowerCase().contains("unknown")) {
                    final String w = t.replace("+", "").trim();
                    prefs.edit().putString("weather", w).apply();
                    post(new Runnable() { public void run() { weather = w; updateHero(); } });
                }
            } catch (Throwable ignored) { }
        } }).start();
    }

    // ================= helpers =================
    private ViewOutlineProvider round(final int r) {
        return new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        };
    }

    private TextView text(float designPx, int color, String family) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, designPx * S);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    private void launch(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) { noteLaunched(pkg); safeStart(i); }
    }

    private boolean safeStart(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); }
        catch (Exception ignored) { return false; }
        if (getContext() instanceof HomeRedirectService)
            ((HomeRedirectService) getContext()).onAppLaunchedFromOverlay();
        return true;
    }
}
