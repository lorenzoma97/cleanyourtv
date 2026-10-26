package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-off diagnostic for the content features (fork lorenzoma97): what the installed apps publish in the TV
 * provider. Counts per package for Watch Next, preview channels/programs, channels and the programme guide, and
 * which fields are filled (poster, deep link, progress, preview video). Runs once per process, off the main
 * thread, only when READ_TV_LISTINGS has been granted (pm grant). Output: logcat tag TvHomeProbe and
 * files/probe.txt. Changes nothing.
 * scorciatoia: diagnostica temporanea, va tolta quando la hero legge davvero questi dati.
 */
final class ContentProbe {
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final String TAG = "TvHomeProbe";
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private ContentProbe() { }

    static void startOnce(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (app.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return;
        if (!STARTED.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                write(app, report(app.getContentResolver(), System.currentTimeMillis()));
            } catch (Exception | LinkageError e) {
                // A diagnostic must never take Home down with it.
                Log.w(TAG, "probe failed: " + e);
            }
        }, "content-probe");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static String report(ContentResolver r, long now) {
        StringBuilder out = new StringBuilder();
        out.append("probe ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date(now))).append('\n');
        watchNext(r, out);
        preview(r, out);
        channels(r, out);
        programs(r, now, out);
        return out.toString();
    }

    private static void watchNext(ContentResolver r, StringBuilder out) {
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
        };
        Map<String, int[]> per = new TreeMap<>();   // rows, poster, content:// poster, intent, progress, watchlist
        Map<String, String> sample = new TreeMap<>();
        try (Cursor c = r.query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                int[] n = per.computeIfAbsent(String.valueOf(c.getString(0)), k -> new int[6]);
                String poster = c.getString(1) != null ? c.getString(1) : c.getString(2);
                n[0]++;
                if (poster != null) n[1]++;
                if (poster != null && poster.startsWith("content:")) n[2]++;
                if (c.getString(3) != null) n[3]++;
                if (c.getLong(4) > 0 && c.getLong(5) > 0) n[4]++;
                if (c.getInt(6) == 3) n[5]++;
                sample.putIfAbsent(c.getString(0), c.getString(7));
            }
        } catch (RuntimeException e) {
            out.append("watch_next ERROR ").append(e).append('\n');
            return;
        }
        if (per.isEmpty()) out.append("watch_next EMPTY\n");
        for (Map.Entry<String, int[]> e : per.entrySet()) {
            int[] n = e.getValue();
            out.append("watch_next pkg=").append(e.getKey()).append(" rows=").append(n[0]).append(" poster=").append(n[1])
                    .append(" poster_content=").append(n[2]).append(" intent=").append(n[3]).append(" progress=").append(n[4])
                    .append(" watchlist=").append(n[5]).append(" sample=\"").append(sample.get(e.getKey())).append("\"\n");
        }
    }

    private static void preview(ContentResolver r, StringBuilder out) {
        String[] cols = {
                TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_PREVIEW_VIDEO_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_TITLE,
        };
        Map<String, int[]> per = new TreeMap<>();   // rows, poster, video, intent
        Map<String, String> sample = new TreeMap<>();
        try (Cursor c = r.query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                int[] n = per.computeIfAbsent(String.valueOf(c.getString(0)), k -> new int[4]);
                n[0]++;
                if (c.getString(1) != null) n[1]++;
                if (c.getString(2) != null) n[2]++;
                if (c.getString(3) != null) n[3]++;
                sample.putIfAbsent(c.getString(0), c.getString(4));
            }
        } catch (RuntimeException e) {
            out.append("preview ERROR ").append(e).append('\n');
            return;
        }
        if (per.isEmpty()) out.append("preview EMPTY\n");
        for (Map.Entry<String, int[]> e : per.entrySet()) {
            int[] n = e.getValue();
            out.append("preview pkg=").append(e.getKey()).append(" rows=").append(n[0]).append(" poster=").append(n[1])
                    .append(" video=").append(n[2]).append(" intent=").append(n[3])
                    .append(" sample=\"").append(sample.get(e.getKey())).append("\"\n");
        }
    }

    private static void channels(ContentResolver r, StringBuilder out) {
        String[] cols = {
                TvContract.Channels.COLUMN_PACKAGE_NAME,
                TvContract.Channels.COLUMN_TYPE,
                TvContract.Channels.COLUMN_BROWSABLE,
                TvContract.Channels.COLUMN_INPUT_ID,
        };
        Map<String, Map<String, Integer>> per = new TreeMap<>();
        try (Cursor c = r.query(TvContract.Channels.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                Map<String, Integer> n = per.computeIfAbsent(String.valueOf(c.getString(0)), k -> new TreeMap<>());
                n.merge("rows", 1, Integer::sum);
                n.merge("browsable", c.getInt(2) == 1 ? 1 : 0, Integer::sum);
                n.merge("type:" + c.getString(1), 1, Integer::sum);
                n.merge("input:" + c.getString(3), 1, Integer::sum);
            }
        } catch (RuntimeException e) {
            out.append("channels ERROR ").append(e).append('\n');
            return;
        }
        if (per.isEmpty()) out.append("channels EMPTY\n");
        for (Map.Entry<String, Map<String, Integer>> e : per.entrySet()) {
            out.append("channels pkg=").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
    }

    private static void programs(ContentResolver r, long now, StringBuilder out) {
        String[] cols = {
                TvContract.Programs.COLUMN_PACKAGE_NAME,
                TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
                TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_POSTER_ART_URI,
        };
        long horizon = now + 6 * 3600_000L;
        Map<String, int[]> per = new TreeMap<>();   // rows, on now, starting in the next 6 h, poster
        Map<String, StringBuilder> nowTitles = new TreeMap<>();
        try (Cursor c = r.query(TvContract.Programs.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                int[] n = per.computeIfAbsent(String.valueOf(c.getString(0)), k -> new int[4]);
                long start = c.getLong(1), end = c.getLong(2);
                n[0]++;
                if (start <= now && now < end) {
                    n[1]++;
                    StringBuilder t = nowTitles.computeIfAbsent(String.valueOf(c.getString(0)), k -> new StringBuilder());
                    if (n[1] <= 5) t.append(t.length() == 0 ? "" : " | ").append(c.getString(3));
                }
                if (start > now && start <= horizon) n[2]++;
                if (c.getString(4) != null) n[3]++;
            }
        } catch (RuntimeException e) {
            out.append("programs ERROR ").append(e).append('\n');
            return;
        }
        if (per.isEmpty()) out.append("programs EMPTY\n");
        for (Map.Entry<String, int[]> e : per.entrySet()) {
            int[] n = e.getValue();
            out.append("programs pkg=").append(e.getKey()).append(" rows=").append(n[0]).append(" now=").append(n[1])
                    .append(" next6h=").append(n[2]).append(" poster=").append(n[3])
                    .append(" now_titles=\"").append(nowTitles.containsKey(e.getKey()) ? nowTitles.get(e.getKey()) : "")
                    .append("\"\n");
        }
    }

    private static void write(Context ctx, String text) {
        for (String line : text.split("\n")) Log.i(TAG, line);
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return;
        try (FileOutputStream f = new FileOutputStream(new File(dir, "probe.txt"))) {
            f.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "could not write probe.txt: " + e);
        }
    }
}
