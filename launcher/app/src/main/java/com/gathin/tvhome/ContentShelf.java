package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

/**
 * Fork lorenzoma97: what the top shelf shows for a dock app, Apple TV Top Shelf style. First "Riprendi" from the
 * TV's own Watch Next rows (READ_TV_LISTINGS, granted with pm grant), then the Casa add-on's items (Top 10 Italia,
 * Novita) from content://it.lorenzoma97.casa.hero. The launcher keeps no INTERNET permission: remote pictures go
 * through Casa's image proxy. Everything here is blocking: call it off the main thread.
 * Watch Next reading follows SferaDev/android-tv WatchNext.java (MIT).
 */
final class ContentShelf {
    private static final String TAG = "TvHome.Shelf";
    static final String CASA = "it.lorenzoma97.casa.hero";
    private static final int MAX_RESUME = 3;
    /** TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST: a saved title, not a resume point. */
    private static final int TYPE_WATCHLIST = 3;

    static final class Item {
        final String title, line;
        final Uri image;
        final Intent action;
        /** The redesigned shelf: title logo (null: text), two lines of plot, badges, the resume point and progress. */
        Uri logo;
        String plot, tag, season, resume;
        int top;
        float progress = -1f;

        Item(String title, String line, Uri image, Intent action) {
            this.title = title;
            this.line = line;
            this.image = image;
            this.action = action;
        }
    }

    private ContentShelf() { }

    static List<Item> load(Context ctx, String pkg) {
        List<Item> out = new ArrayList<>();
        if (pkg == null) return out;
        out.addAll(resume(ctx, pkg));
        out.addAll(casa(ctx, pkg));
        return out;
    }

    /** "Riprendi" rows this app published, newest first; poster through Casa when it is a web address. */
    private static List<Item> resume(Context ctx, String pkg) {
        List<Item> out = new ArrayList<>();
        if (ctx.checkSelfPermission(ContentProbe.PERMISSION) != PackageManager.PERMISSION_GRANTED) return out;
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
        };
        List<long[]> order = new ArrayList<>();
        List<Item> rows = new ArrayList<>();
        // No selection: with READ_TV_LISTINGS alone the provider refuses one (docs/watch-next.md); filter here.
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(c.getString(0)) || c.getInt(8) == TYPE_WATCHLIST || blank(c.getString(1))) continue;
                String poster = c.getString(5) != null ? c.getString(5) : c.getString(6);
                Uri image = picture(poster);
                if (image == null) continue;
                String line = resumeLine(c.getString(3), c.getString(4), c.getString(2), c.getLong(10), c.getLong(11));
                order.add(new long[]{c.getLong(9), rows.size()});
                Item item = new Item(c.getString(1).trim(), line, image, intent(c.getString(7), pkg));
                item.resume = episode(c.getString(3), c.getString(4), c.getString(2));
                item.progress = c.getLong(11) > 0 && c.getLong(10) > 0 ? Math.min(1f, (float) c.getLong(10) / c.getLong(11)) : -1f;
                rows.add(item);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Watch Next: " + e);
        }
        order.sort((a, b) -> Long.compare(b[0], a[0]));
        for (long[] o : order) {
            if (out.size() >= MAX_RESUME) break;
            out.add(rows.get((int) o[1]));
        }
        return out;
    }

    /** "S2E5", else the episode title, else null. */
    static String episode(String season, String episode, String episodeTitle) {
        StringBuilder se = new StringBuilder();
        if (!blank(season)) se.append('S').append(season.trim());
        if (!blank(episode)) se.append('E').append(episode.trim());
        if (se.length() > 0) return se.toString();
        return blank(episodeTitle) ? null : episodeTitle.trim();
    }

    /** "Riprendi · S2E5 · 40%" from whichever parts the app filled in. */
    static String resumeLine(String season, String episode, String episodeTitle, long position, long duration) {
        StringBuilder sb = new StringBuilder("Riprendi");
        String ep = episode(season, episode, episodeTitle);
        if (ep != null) sb.append(" · ").append(ep);
        if (duration > 0 && position > 0) sb.append(" · ").append(Math.min(99, (int) (100 * position / duration))).append('%');
        return sb.toString();
    }

    /** Casa's items for this app; empty when Casa is not installed. */
    private static List<Item> casa(Context ctx, String pkg) {
        List<Item> out = new ArrayList<>();
        Uri uri = Uri.parse("content://" + CASA + "/items/" + Uri.encode(pkg));
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String title = c.getString(c.getColumnIndexOrThrow("title"));
                String image = c.getString(c.getColumnIndexOrThrow("image"));
                if (blank(title) || blank(image)) continue;
                Item item = new Item(title, c.getString(c.getColumnIndexOrThrow("line")), Uri.parse(image),
                        intent(c.getString(c.getColumnIndexOrThrow("action")), pkg));
                // Columns added with the redesign; an older Casa simply lacks them.
                String logo = column(c, "logo");
                if (!blank(logo)) item.logo = Uri.parse(logo);
                item.plot = column(c, "plot");
                item.tag = column(c, "tag");
                item.season = column(c, "season");
                int top = c.getColumnIndex("top");
                item.top = top >= 0 ? c.getInt(top) : 0;
                out.add(item);
            }
        } catch (RuntimeException e) {
            // Casa missing or broken: the shelf simply keeps the app's own artwork.
        }
        return out;
    }

    /** content:// as is; https through Casa's proxy (this launcher has no network permission); else nothing. */
    private static Uri picture(String uri) {
        if (blank(uri)) return null;
        if (uri.startsWith("content:")) return Uri.parse(uri);
        if (uri.startsWith("https://")) return Uri.parse("content://" + CASA + "/url").buildUpon().appendQueryParameter("u", uri).build();
        return null;
    }

    /** An intent: URI aimed at this app only; anything aimed elsewhere or malformed is dropped. */
    static Intent intent(String uri, String pkg) {
        if (blank(uri)) return null;
        try {
            Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            if (i.getPackage() == null) i.setPackage(pkg);
            if (!pkg.equals(i.getPackage())) return null;
            i.setSelector(null);
            i.setComponent(null);
            return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** RGB_565, sampled down to about maxWidth: one shelf picture costs ~1.8 MB, not 8 MB. Null on failure. */
    static Bitmap decode(Context ctx, Uri uri, int maxWidth) {
        ContentResolver r = ctx.getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = r.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inSampleSize = 1;
            while (bounds.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
            try (InputStream in = r.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            Log.w(TAG, "picture " + uri + ": " + e);
            return null;
        }
    }

    private static String column(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i < 0 ? null : c.getString(i);
    }

    /** Bologna now from Casa: {city, temp, max, min, desc, icon, air, aqi}; null when Casa has no reading. */
    static String[] weather(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://" + CASA + "/weather"), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            String[] out = new String[8];
            String[] cols = {"city", "temp", "max", "min", "desc", "icon", "air", "aqi"};
            for (int i = 0; i < cols.length; i++) out[i] = column(c, cols[i]);
            return out;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Logos keep their transparency (ARGB), small: at most maxWidth wide. */
    static Bitmap decodeLogo(Context ctx, Uri uri, int maxWidth) {
        ContentResolver r = ctx.getContentResolver();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = r.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            while (bounds.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
            try (InputStream in = r.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
