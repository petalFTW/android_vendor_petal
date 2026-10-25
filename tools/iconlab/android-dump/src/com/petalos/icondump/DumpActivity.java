package com.petalos.icondump;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

public class DumpActivity extends Activity {
    private static final String TAG = "IconDump";
    private static final int SIZE = 512;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean all = getIntent().getBooleanExtra("all", false);
        new Thread(() -> dumpAll(all), "icon-dump").start();
    }

    private void dumpAll(boolean all) {
        long started = System.currentTimeMillis();
        File root = new File(getExternalFilesDir(null), "dump");
        if (!root.isDirectory() && !root.mkdirs()) {
            Log.e(TAG, "cannot create " + root);
            finish();
            return;
        }
        PackageManager pm = getPackageManager();
        TreeMap<String, ApplicationInfo> apps = new TreeMap<>();
        if (all) {
            for (ApplicationInfo ai : pm.getInstalledApplications(
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS)) {
                apps.put(ai.packageName, ai);
            }
        } else {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(intent,
                    PackageManager.MATCH_ALL | PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ResolveInfo ri : resolved) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if (ai != null) apps.put(ai.packageName, ai);
            }
        }
        Log.i(TAG, (all ? "all" : "launcher") + " apps=" + apps.size() + " out=" + root);

        StringBuilder csv = new StringBuilder();
        csv.append("package,label,adaptive,monochrome,intrinsic_w,intrinsic_h,has_fg,has_bg\n");
        int ok = 0, failed = 0;
        for (ApplicationInfo ai : apps.values()) {
            String pkg = ai.packageName;
            try {
                Drawable d = ai.loadIcon(pm);
                if (d == null) {
                    failed++;
                    csv.append(csv(pkg, ai.loadLabel(pm).toString(), false, false, 0, 0, false, false));
                    continue;
                }
                Bitmap masked = render(d, SIZE, SIZE, true);
                save(masked, new File(root, pkg + ".icon.png"));
                masked.recycle();

                boolean adaptive = d instanceof AdaptiveIconDrawable;
                boolean mono = false, fg = false, bg = false;
                if (adaptive) {
                    AdaptiveIconDrawable a = (AdaptiveIconDrawable) d;
                    float extra = AdaptiveIconDrawable.getExtraInsetFraction();
                    Drawable monoDrawable = a.getMonochrome();
                    mono = monoDrawable != null;
                    if (monoDrawable != null) {
                        Bitmap monoBmp = renderInset(monoDrawable, extra, SIZE, SIZE);
                        save(monoBmp, new File(root, pkg + ".mono.png"));
                        save(alphaOf(monoBmp), new File(root, pkg + ".monomask.png"));
                        monoBmp.recycle();
                    }
                    if (a.getForeground() != null) {
                        Bitmap fgBmp = renderInset(a.getForeground(), extra, SIZE, SIZE);
                        save(fgBmp, new File(root, pkg + ".fg.png"));
                        save(alphaOf(fgBmp), new File(root, pkg + ".fgmask.png"));
                        fgBmp.recycle();
                        fg = true;
                    }
                    if (a.getBackground() != null) {
                        Bitmap bgBmp = renderInset(a.getBackground(), extra, SIZE, SIZE);
                        save(bgBmp, new File(root, pkg + ".bg.png"));
                        bgBmp.recycle();
                        bg = true;
                    }
                    if (fg && bg) {
                        Bitmap flat = composeFlat(a, SIZE, SIZE);
                        save(flat, new File(root, pkg + ".flat.png"));
                        flat.recycle();
                    }
                }
                csv.append(csv(pkg, ai.loadLabel(pm).toString(), adaptive, mono,
                        d.getIntrinsicWidth(), d.getIntrinsicHeight(), fg, bg));
                ok++;
            } catch (Throwable t) {
                failed++;
                Log.w(TAG, "failed " + pkg, t);
                csv.append(csv(pkg, "?", false, false, 0, 0, false, false));
            }
        }
        try (FileWriter w = new FileWriter(new File(root, "metadata.csv"))) {
            w.write(csv.toString());
        } catch (IOException e) {
            Log.e(TAG, "metadata", e);
        }
        Log.i(TAG, "DONE ok=" + ok + " failed=" + failed + " ms="
                + (System.currentTimeMillis() - started));
        runOnUiThread(this::finish);
    }

    private static String csv(String pkg, String label, boolean adaptive, boolean mono,
            int iw, int ih, boolean fg, boolean bg) {
        return pkg + "," + label.replace(',', ' ') + "," + adaptive + "," + mono + ","
                + iw + "," + ih + "," + fg + "," + bg + "\n";
    }

    private static Bitmap render(Drawable d, int w, int h, boolean masked) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    private static Bitmap renderInset(Drawable d, float extra, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Drawable inset = new InsetDrawable(d, -extra);
        inset.setBounds(0, 0, w, h);
        inset.draw(c);
        return b;
    }

    private static Bitmap composeFlat(AdaptiveIconDrawable a, int w, int h) {
        float extra = AdaptiveIconDrawable.getExtraInsetFraction();
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.BLACK);
        Drawable bg = new InsetDrawable(a.getBackground(), -extra);
        Drawable fg = new InsetDrawable(a.getForeground(), -extra);
        bg.setBounds(0, 0, w, h);
        bg.draw(c);
        fg.setBounds(0, 0, w, h);
        fg.draw(c);
        return b;
    }

    private static Bitmap alphaOf(Bitmap src) {
        Bitmap alpha = src.extractAlpha();
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        c.drawBitmap(alpha, 0, 0, p);
        alpha.recycle();
        return out;
    }

    private static void save(Bitmap b, File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw new IOException("compress failed " + f);
            }
        }
    }
}
