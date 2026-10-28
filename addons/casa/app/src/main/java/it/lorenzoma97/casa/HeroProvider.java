package it.lorenzoma97.casa;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.security.MessageDigest;
import java.util.List;

/**
 * Read-only data for the launcher's top shelf (fork lorenzoma97/cleanyourtv):
 *   content://it.lorenzoma97.casa.hero/items/<package>  -> title, line, image (content URI), action (intent URI)
 *   content://it.lorenzoma97.casa.hero/img/<file>       -> a cached backdrop
 *   content://it.lorenzoma97.casa.hero/url?u=<https>    -> a remote image (Watch Next posters), cached; the launcher
 *                                                           has no INTERNET permission, Casa fetches it.
 * Everything served is public catalogue data.
 */
public class HeroProvider extends ContentProvider {
    static final String AUTHORITY = "it.lorenzoma97.casa.hero";
    private static final String[] COLUMNS = {"title", "line", "image", "action", "logo", "plot", "top", "tag", "season", "cta", "meta"};
    private static final String[] WEATHER = {"city", "temp", "max", "min", "desc", "icon", "air", "aqi"};
    private static final long URL_CACHE_BYTES = 15L * 1024 * 1024;

    @Override
    public boolean onCreate() {
        RefreshJob.schedule(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        List<String> seg = uri.getPathSegments();
        if (seg.size() == 1 && "weather".equals(seg.get(0))) return weather();
        if (seg.size() == 1 && "art".equals(seg.get(0))) return art(uri.getQueryParameter("t"));
        if (seg.size() == 1 && "picks".equals(seg.get(0))) return picks();
        if (seg.size() == 1 && "update".equals(seg.get(0))) return update();
        MatrixCursor c = new MatrixCursor(COLUMNS);
        if (seg.size() != 2 || !"items".equals(seg.get(0))) return c;
        File f = Store.file(getContext(), "shelf.json");
        if (!Store.fresh(f, Shelf.CONTENT_TTL * 2)) RefreshJob.kick(getContext());
        String text = Store.read(f);
        if (text == null) return c;
        try {
            JSONArray items = new JSONObject(text).optJSONArray(seg.get(1));
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject x = items.getJSONObject(i);
                String logo = x.isNull("logo") ? null : "content://" + AUTHORITY + "/img/" + x.optString("logo");
                c.addRow(new Object[]{x.optString("title"), x.optString("line"),
                        "content://" + AUTHORITY + "/img/" + x.optString("image"), x.isNull("action") ? null : x.optString("action"),
                        logo, x.optString("plot"), x.optInt("top", 0),
                        x.isNull("tag") ? null : x.optString("tag"), x.isNull("season") ? null : x.optString("season"),
                        x.isNull("cta") ? null : x.optString("cta"), x.isNull("meta") ? null : x.optString("meta")});
            }
        } catch (Exception ignored) {
            // a broken shelf.json means an empty shelf, never a crash in the launcher's process
        }
        return c;
    }

    /**
     * Clean TMDB pictures for a title another app published ("Riprendi"): Netflix's own Watch Next art has its logo
     * and a TOP 10 badge printed on it, which clash with the shelf's text. One row {image, logo}, or none.
     */
    private Cursor art(String title) {
        MatrixCursor c = new MatrixCursor(new String[]{"image", "logo", "plot", "meta"});
        long id = Tmdb.findSeries(getContext(), title);
        if (id <= 0) return c;
        JSONObject d = Tmdb.details(getContext(), id);
        String path = d == null ? null : Tmdb.backdrop(d);
        if (path == null) return c;
        try {
            String image = "content://" + AUTHORITY + "/img/" + Tmdb.image(getContext(), "img", path, "w1280").getName();
            String logoPath = Tmdb.logo(d);
            String logo = null;
            if (logoPath != null) logo = "content://" + AUTHORITY + "/img/" + Tmdb.image(getContext(), "img", logoPath, "w500").getName();
            c.addRow(new Object[]{image, logo, Tmdb.shortPlot(d.optString("overview"), 170), Tmdb.meta(d)});
        } catch (Exception ignored) {
            // no clean picture: the launcher keeps the app's own
        }
        return c;
    }

    /**
     * Tonight's five for Home's "Scopri una nuova serie" bar and Home at rest: title and a small backdrop each (the
     * section's first cards), and for the first a full-size one (the still picture at rest).
     */
    private Cursor picks() {
        MatrixCursor c = new MatrixCursor(new String[]{"tmdb", "title", "image", "backdrop"});
        JSONObject content = Shelf.content(getContext(), false);
        JSONObject pool = content == null ? null : content.optJSONObject("pool");
        if (pool == null) return c;
        java.util.List<Long> ids = Pool.tonight(pool, Store.tonight(getContext()), Store.seen(getContext()), 5, new java.util.Random());
        Store.saveTonight(getContext(), ids);
        for (Long id : ids) {
            JSONObject d = Tmdb.details(getContext(), id);
            String path = d == null ? null : Tmdb.backdrop(d);
            if (path == null) continue;
            try {
                File f = Tmdb.image(getContext(), "pool", path, "w300");
                String big = null;
                if (c.getCount() == 0) {
                    try {
                        big = "content://" + AUTHORITY + "/pool/" + Tmdb.image(getContext(), "pool", path, "w1280").getName();
                    } catch (Exception ignored) {
                        // the still picture is optional: Home at rest shows the time over black
                    }
                }
                c.addRow(new Object[]{id, d.optString("name"), "content://" + AUTHORITY + "/pool/" + f.getName(), big});
            } catch (Exception ignored) {
                // a missing thumbnail just leaves a shorter strip
            }
        }
        return c;
    }

    /**
     * One row when an update is downloaded and verified: what it updates ("Home e Casa") and an id for "Più tardi";
     * none otherwise. Checks the release first when the last check is over 2 hours old (blocking, binder thread).
     */
    private Cursor update() {
        MatrixCursor c = new MatrixCursor(new String[]{"apps", "id"});
        Updater.checkIfStale(getContext());
        List<File> ready = Updater.ready(getContext());
        if (ready.isEmpty()) return c;
        StringBuilder id = new StringBuilder();
        for (File f : ready) id.append(f.getName()).append('@').append(f.lastModified()).append(' ');
        c.addRow(new Object[]{Updater.names(ready), id.toString().trim()});
        return c;
    }

    /** One row: Bologna now. Fetched on the binder thread when stale (the launcher asks off its main thread). */
    private Cursor weather() {
        MatrixCursor c = new MatrixCursor(WEATHER);
        JSONObject w = Weather.now(getContext());
        if (w == null) return c;
        String[] d = Weather.describe(w.optInt("code"), w.optInt("day", 1) == 1);
        int aqi = w.has("aqi") ? w.optInt("aqi") : -1;
        c.addRow(new Object[]{Weather.CITY, Math.round(w.optDouble("temp")), Math.round(w.optDouble("max")),
                Math.round(w.optDouble("min")), d[0], d[1], Weather.air(aqi), aqi});
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        List<String> seg = uri.getPathSegments();
        File f;
        if (seg.size() == 2 && ("img".equals(seg.get(0)) || "pool".equals(seg.get(0))) && seg.get(1).matches("[A-Za-z0-9._-]+")) {
            f = new File(Store.dir(getContext(), seg.get(0)), seg.get(1));
        } else if (seg.size() == 1 && "url".equals(seg.get(0))) {
            f = remote(uri.getQueryParameter("u"));
        } else {
            throw new FileNotFoundException(uri.toString());
        }
        if (f == null || !f.isFile()) throw new FileNotFoundException(uri.toString());
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** https only; one file per URL; blocking but bounded by Net's timeouts (the launcher calls it off its main thread). */
    private File remote(String url) {
        if (url == null || !url.startsWith("https://")) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder name = new StringBuilder();
            for (byte b : md.digest(url.getBytes("UTF-8"))) name.append(String.format("%02x", b));
            File dir = Store.dir(getContext(), "url");
            File f = new File(dir, name.toString());
            if (!f.isFile()) {
                Net.download(url, f);
                Store.trim(dir, URL_CACHE_BYTES);
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return uri.getPathSegments().size() > 0 && "items".equals(uri.getPathSegments().get(0))
                ? "vnd.android.cursor.dir/vnd.casa.hero" : "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
