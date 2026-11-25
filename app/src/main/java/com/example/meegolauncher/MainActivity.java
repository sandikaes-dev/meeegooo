package com.example.meegolauncher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "meego";
    private static final String KEY_RECENT = "recent";
    private static final int PAGE_EVENTS = 0, PAGE_APPS = 1, PAGE_OPEN = 2;
    private static final int PAGES = 3;
    private static final int MAX_RECENT = 8;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static class AppItem {
        String label, pkg;
        ComponentName cn;
        Drawable icon;
    }

    private static class Holder {
        ImageView icon;
        TextView label;
    }

    private final List<AppItem> apps = new ArrayList<>();
    private Pager pager;
    private int w;
    private float density;
    private TextView statusClock, statusBattery, bigClock, bigDate, bigBattery;
    private GridView grid;
    private AppAdapter adapter;
    private LinearLayout openList;
    private final View[] dots = new View[PAGES];
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        w = getResources().getDisplayMetrics().widthPixels;

        FrameLayout root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0A1A2F, 0xFF1A1030, 0xFF04060B});
        root.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(buildStatusBar(), new LinearLayout.LayoutParams(MATCH, dp(28)));

        pager = new Pager(this);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.addView(buildEventsPage(), new LinearLayout.LayoutParams(w, MATCH));
        strip.addView(buildAppsPage(), new LinearLayout.LayoutParams(w, MATCH));
        strip.addView(buildOpenPage(), new LinearLayout.LayoutParams(w, MATCH));
        pager.addView(strip, new FrameLayout.LayoutParams(w * PAGES, MATCH));
        pager.setOnScrollChangeListener((v, x, y, ox, oy) -> updateDots(Math.round(x / (float) w)));
        col.addView(pager, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        col.addView(buildDots(), new LinearLayout.LayoutParams(MATCH, dp(24)));
        root.addView(col, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);

        loadApps();
        refreshOpenApps();
        pager.post(() -> pager.scrollTo(w * PAGE_APPS, 0));
        updateDots(PAGE_APPS);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        Intent bat = registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) updateBattery(bat);
        refreshClock();
        refreshOpenApps();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(timeReceiver);
        unregisterReceiver(batteryReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(pkgReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_MAIN.equals(intent.getAction()) && intent.hasCategory(Intent.CATEGORY_HOME)) {
            goTo(PAGE_APPS);
            grid.smoothScrollToPosition(0);
        }
    }

    @Override
    public void onBackPressed() {
        goTo(PAGE_APPS);
    }

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { refreshClock(); }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateBattery(i); }
    };

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            loadApps();
            refreshOpenApps();
        }
    };

    private void refreshClock() {
        Date now = new Date();
        String t = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now);
        String d = new SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(now);
        statusClock.setText(t);
        bigClock.setText(t);
        bigDate.setText(d);
    }

    private void updateBattery(Intent i) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int pct = level * 100 / Math.max(1, scale);
        statusBattery.setText(pct + "%");
        bigBattery.setText("Baterai " + pct + "%" + (charging ? " \u2022 mengisi" : ""));
    }

    private View buildStatusBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0x88000000);

        TextView left = tv("MeeGo", 11, 0xFF8A97AB);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, MATCH, Gravity.START);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setPadding(dp(10), 0, 0, 0);
        bar.addView(left, lp);

        statusClock = tv("", 13, 0xFFFFFFFF);
        statusClock.setGravity(Gravity.CENTER);
        bar.addView(statusClock, new FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER));

        statusBattery = tv("", 11, 0xFFBFD0E8);
        statusBattery.setGravity(Gravity.CENTER_VERTICAL);
        statusBattery.setPadding(0, 0, dp(10), 0);
        bar.addView(statusBattery, new FrameLayout.LayoutParams(WRAP, MATCH, Gravity.END));
        return bar;
    }

    private View buildEventsPage() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setGravity(Gravity.CENTER_HORIZONTAL);
        p.setPadding(dp(24), dp(36), dp(24), dp(24));

        p.addView(tv("Events", 14, 0xFF6FA8FF));

        bigClock = tv("", 80, 0xFFFFFFFF);
        bigClock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        p.addView(bigClock);

        bigDate = tv("", 18, 0xFFCCD6E6);
        p.addView(bigDate);

        bigBattery = tv("", 14, 0xFF8A97AB);
        bigBattery.setPadding(0, dp(8), 0, dp(28));
        p.addView(bigBattery);

        TextView card = tv("Geser ke kanan untuk membuka aplikasi,\nke kiri untuk aplikasi terbuka.", 13, 0xFFB5C3D9);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(card(0x33FFFFFF, 12));
        p.addView(card, new LinearLayout.LayoutParams(MATCH, WRAP));
        return p;
    }

    private View buildAppsPage() {
        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(16));
        grid.setPadding(dp(8), dp(16), dp(8), dp(8));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(0));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            openAppInfo(apps.get(pos));
            return true;
        });
        return grid;
    }

    private View buildOpenPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        openList = new LinearLayout(this);
        openList.setOrientation(LinearLayout.VERTICAL);
        openList.setPadding(dp(16), dp(24), dp(16), dp(16));
        sv.addView(openList, new FrameLayout.LayoutParams(MATCH, WRAP));
        return sv;
    }

    private View buildDots() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < PAGES; i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(0x55FFFFFF);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(d, lp);
            dots[i] = d;
        }
        return row;
    }

    private void updateDots(int current) {
        for (int i = 0; i < PAGES; i++) {
            ((GradientDrawable) dots[i].getBackground()).setColor(i == current ? 0xFFFFFFFF : 0x55FFFFFF);
        }
    }

    private void refreshOpenApps() {
        if (openList == null) return;
        openList.removeAllViews();
        TextView title = tv("Aplikasi terbuka", 14, 0xFF6FA8FF);
        title.setPadding(dp(4), 0, 0, dp(12));
        openList.addView(title);

        List<AppItem> items = new ArrayList<>();
        for (String pkg : recent()) {
            AppItem a = find(pkg);
            if (a != null) items.add(a);
        }
        if (items.isEmpty()) {
            TextView empty = tv("Belum ada aplikasi yang dibuka.", 13, 0xFF8A97AB);
            empty.setPadding(dp(4), dp(8), 0, 0);
            openList.addView(empty);
            return;
        }
        for (int i = 0; i < items.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < i + 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(140), 1f);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                if (j < items.size()) row.addView(buildCard(items.get(j)), lp);
                else row.addView(new View(this), lp);
            }
            openList.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
    }

    private View buildCard(final AppItem a) {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(card(0x44FFFFFF, 14));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(a.icon);
        icon.setBackground(tile());
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        inner.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView label = tv(a.label, 12, 0xFFFFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(6), dp(8), dp(6), 0);
        inner.addView(label, new LinearLayout.LayoutParams(MATCH, WRAP));
        card.addView(inner, new FrameLayout.LayoutParams(MATCH, MATCH));

        TextView close = tv("\u2715", 14, 0xFFFFFFFF);
        close.setPadding(dp(12), dp(8), dp(12), dp(12));
        close.setOnClickListener(v -> {
            removeRecent(a.pkg);
            refreshOpenApps();
        });
        card.addView(close, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END));

        card.setOnClickListener(v -> launch(a));
        return card;
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        apps.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            AppItem a = new AppItem();
            a.label = String.valueOf(ri.loadLabel(pm));
            a.pkg = ri.activityInfo.packageName;
            a.cn = new ComponentName(a.pkg, ri.activityInfo.name);
            a.icon = ri.loadIcon(pm);
            apps.add(a);
        }
        Collections.sort(apps, (x, y) -> x.label.compareToIgnoreCase(y.label));
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private AppItem find(String pkg) {
        for (AppItem a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void launch(AppItem a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(a.cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            pushRecent(a.pkg);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa membuka " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void openAppInfo(AppItem a) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private List<String> recent() {
        List<String> out = new ArrayList<>();
        String s = prefs.getString(KEY_RECENT, "");
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void saveRecent(List<String> l) {
        prefs.edit().putString(KEY_RECENT, TextUtils.join(",", l)).apply();
    }

    private void pushRecent(String pkg) {
        List<String> l = recent();
        l.remove(pkg);
        l.add(0, pkg);
        while (l.size() > MAX_RECENT) l.remove(l.size() - 1);
        saveRecent(l);
    }

    private void removeRecent(String pkg) {
        List<String> l = recent();
        l.remove(pkg);
        saveRecent(l);
    }

    private void goTo(int p) {
        p = Math.max(0, Math.min(PAGES - 1, p));
        pager.smoothScrollTo(p * w, 0);
        updateDots(p);
    }

    private class Pager extends HorizontalScrollView {
        private int startX, flingDir;
        private final GestureDetector gd;

        Pager(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setFillViewport(true);
            gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                    if (Math.abs(vx) > 400 && Math.abs(vx) > Math.abs(vy)) flingDir = vx < 0 ? 1 : -1;
                    return false;
                }
            });
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startX = getScrollX();
                flingDir = 0;
            }
            return super.dispatchTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            gd.onTouchEvent(ev);
            boolean r = super.onTouchEvent(ev);
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                int cur = Math.round(getScrollX() / (float) w);
                int start = Math.round(startX / (float) w);
                goTo(flingDir != 0 ? start + flingDir : cur);
            }
            return r;
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                h = new Holder();
                h.icon = new ImageView(MainActivity.this);
                h.icon.setBackground(tile());
                h.icon.setPadding(dp(8), dp(8), dp(8), dp(8));
                h.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(h.icon, new LinearLayout.LayoutParams(dp(60), dp(60)));
                h.label = tv("", 11, 0xFFFFFFFF);
                h.label.setSingleLine(true);
                h.label.setEllipsize(TextUtils.TruncateAt.END);
                h.label.setGravity(Gravity.CENTER);
                h.label.setPadding(dp(2), dp(6), dp(2), 0);
                h.label.setShadowLayer(3f, 0f, 1f, 0xFF000000);
                cell.addView(h.label, new LinearLayout.LayoutParams(MATCH, WRAP));
                cell.setTag(h);
                cv = cell;
            } else {
                h = (Holder) cv.getTag();
            }
            AppItem a = apps.get(pos);
            h.icon.setImageDrawable(a.icon);
            h.label.setText(a.label);
            return cv;
        }
    }

    private Drawable tile() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF4A5565, 0xFF1B212B});
        g.setCornerRadius(dp(14));
        g.setStroke(Math.max(1, dp(1)), 0x44FFFFFF);
        return g;
    }

    private Drawable card(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private TextView tv(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
