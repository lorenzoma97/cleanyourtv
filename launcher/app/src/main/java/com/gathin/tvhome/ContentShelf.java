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
        /** What the play button says when it is not "Guarda il titolo" (Prime: "Cerca su Prime Video"). */
        String cta;
        /** "mancano 23 min" on the Riprendi button; the detail's facts, "2024 · Thriller · 2 stagioni · ★ 7,8". */
        String left, meta;
        int top;
        float progress = -1f;
        /** Came from Casa: a list without any is cached only briefly (Casa may still be on its first download). */
        boolean fromCasa;

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
        List<Item> casa = casa(ctx, pkg);
        out.addAll(casa);
        // Apps Casa has nothing for (YouTube, DAZN on this TV) still show what they publish themselves.
        if (casa.isEmpty()) out.addAll(previews(ctx, pkg));
        return out;
    }

    private static final int MAX_PREVIEWS = 6;

    /** The app's own preview channel rows (the old Android TV home rows): title, picture, deep link, short text. */
    private static List<Item> previews(Context ctx, String pkg) {
        List<Item> out = new ArrayList<>();
        if (ctx.checkSelfPermission(ContentProbe.PERMISSION) != PackageManager.PERMISSION_GRANTED) return out;
        String[] cols = {
                TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
                TvContract.PreviewPrograms.COLUMN_TITLE,
                TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
                TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
                TvContract.PreviewPrograms.COLUMN_INTENT_URI,
                TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
                TvContract.PreviewPrograms.COLUMN_WEIGHT,
        };
        List<float[]> order = new ArrayList<>();
        List<Item> rows = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, cols, null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (!pkg.equals(c.getString(0)) || blank(c.getString(1))) continue;
                // Prefer the 16:9 thumbnail for a full-screen picture; posters can be 2:3.
                Uri image = picture(c.getString(3) != null ? c.getString(3) : c.getString(2));
                if (image == null) continue;
                Item item = new Item(c.getString(1).trim(), null, image, intent(c.getString(4), pkg));
                item.plot = c.getString(5);
                order.add(new float[]{c.getInt(6), rows.size()});
                rows.add(item);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "previews: " + e);
        }
        // Highest weight first (the app's own ranking); the provider's order otherwise.
        order.sort((a, b) -> Float.compare(b[0], a[0]));
        for (float[] o : order) {
            if (out.size() >= MAX_PREVIEWS) break;
            out.add(rows.get((int) o[1]));
        }
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
                item.left = timeLeft(c.getLong(10), c.getLong(11));
                rows.add(item);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Watch Next: " + e);
        }
        order.sort((a, b) -> Long.compare(b[0], a[0]));
        for (long[] o : order) {
            if (out.size() >= MAX_RESUME) break;
            out.add(cleanArt(ctx, rows.get((int) o[1])));
        }
        return out;
    }

    /**
     * Swaps an app's own Watch Next picture for Casa's clean TMDB backdrop and logo of the same series: Netflix
     * prints its logo and a TOP 10 badge on its art, which clashed with the shelf's own title and badges.
     */
    private static Item cleanArt(Context ctx, Item in) {
        Uri uri = Uri.parse("content://" + CASA + "/art").buildUpon().appendQueryParameter("t", in.title).build();
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return in;
            String image = column(c, "image");
            if (blank(image)) return in;
            Item out = new Item(in.title, in.line, Uri.parse(image), in.action);
            String logo = column(c, "logo");
            if (!blank(logo)) out.logo = Uri.parse(logo);
            out.plot = column(c, "plot");
            out.meta = column(c, "meta");
            out.resume = in.resume;
            out.progress = in.progress;
            out.left = in.left;
            return out;
        } catch (RuntimeException e) {
            return in;   // Casa missing or offline: the app's own picture
        }
    }

    /** "S2E5", else the episode title, else null. */
    static String episode(String season, String episode, String episodeTitle) {
        StringBuilder se = new StringBuilder();
        if (!blank(season)) se.append('S').append(season.trim());
        if (!blank(episode)) se.append('E').append(episode.trim());
        if (se.length() > 0) return se.toString();
        return blank(episodeTitle) ? null : episodeTitle.trim();
    }

    /** What is left of the episode, "mancano 23 min" or "mancano 1 h 5 min"; null when the app gave no times. */
    static String timeLeft(long position, long duration) {
        if (duration <= 0 || position <= 0 || position >= duration) return null;
        long min = Math.max(1, Math.round((duration - position) / 60_000.0));
        if (min == 1) return "manca 1 min";
        if (min < 60) return "mancano " + min + " min";
        return "mancano " + min / 60 + " h" + (min % 60 == 0 ? "" : " " + min % 60 + " min");
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
                item.cta = column(c, "cta");
                item.meta = column(c, "meta");
                int top = c.getColumnIndex("top");
                item.top = top >= 0 ? c.getInt(top) : 0;
                item.fromCasa = true;
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

    /** Sampled down to about maxWidth (the UI plane is 1280x720 on the Metz: ~3.7 MB a picture). Null on failure. */
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
            // Full colour: RGB_565 banded the dark gradients of film stills (looked "pixelated" on the Metz).
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

    /** One of tonight's five: its title, a small picture, and for the first a full-screen backdrop (Home at rest). */
    static final class Pick {
        final long tmdb;
        final String title;
        final Bitmap image;
        final Uri backdrop;

        Pick(long tmdb, String title, Bitmap image, Uri backdrop) {
            this.tmdb = tmdb;
            this.title = title;
            this.image = image;
            this.backdrop = backdrop;
        }
    }

    /** Tonight's five from Casa as small pictures for Home's bar and Home at rest; null when Casa is not installed. */
    static List<Pick> picks(Context ctx, int maxWidth) {
        List<Pick> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://" + CASA + "/picks"), null, null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String image = column(c, "image"), backdrop = column(c, "backdrop");
                Bitmap b = blank(image) ? null : decode(ctx, Uri.parse(image), maxWidth);
                int id = c.getColumnIndex("tmdb");
                if (b != null) out.add(new Pick(id < 0 ? 0 : c.getLong(id), column(c, "title"), b,
                        blank(backdrop) ? null : Uri.parse(backdrop)));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    /** Casa's downloaded and verified update: {what it updates, an id}; null when there is none, or no Casa. */
    static String[] update(Context ctx) {
        try (Cursor c = ctx.getContentResolver().query(Uri.parse("content://" + CASA + "/update"), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            String apps = column(c, "apps"), id = column(c, "id");
            return blank(apps) || blank(id) ? null : new String[]{apps, id};
        } catch (RuntimeException e) {
            return null;
        }
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
            Bitmap b;
            try (InputStream in = r.openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            return b == null ? null : lightLogo(b);
        } catch (Exception e) {
            return null;
        }
    }

    /** A logo drawn for light backgrounds comes back white (alpha kept), so it reads on the dark shelf. */
    private static Bitmap lightLogo(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        if (!darkLogo(px)) return b;
        for (int i = 0; i < px.length; i++) px[i] |= 0x00FFFFFF;
        Bitmap white = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        b.recycle();
        return white;
    }

    /**
     * True when most of the logo's solid pixels are dark (black lettering: Terminal List, Painkiller, Beckham; 7 of
     * 230 TMDB logos on 28 Oct 2026). A logo that is mostly solid is a dark plate with light text (Jury Duty, Pam &
     * Tommy) and stays as it is. Same rule as Tmdb.darkLogo in Casa (Scopri).
     */
    static boolean darkLogo(int[] argb) {
        int solid = 0, dark = 0;
        for (int p : argb) {
            if (p >>> 24 < 128) continue;
            solid++;
            if (Math.max(Math.max(p >> 16 & 0xFF, p >> 8 & 0xFF), p & 0xFF) < 100) dark++;
        }
        return solid > 0 && solid < argb.length * 0.7 && dark >= solid * 0.65;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
