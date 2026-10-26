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
    private static final String[] COLUMNS = {"title", "line", "image", "action"};
    private static final long URL_CACHE_BYTES = 15L * 1024 * 1024;

    @Override
    public boolean onCreate() {
        RefreshJob.schedule(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        List<String> seg = uri.getPathSegments();
        if (seg.size() != 2 || !"items".equals(seg.get(0))) return c;
        File f = Store.file(getContext(), "shelf.json");
        if (!Store.fresh(f, Shelf.CONTENT_TTL * 2)) RefreshJob.kick(getContext());
        String text = Store.read(f);
        if (text == null) return c;
        try {
            JSONArray items = new JSONObject(text).optJSONArray(seg.get(1));
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject x = items.getJSONObject(i);
                c.addRow(new Object[]{x.optString("title"), x.optString("line"),
                        "content://" + AUTHORITY + "/img/" + x.optString("image"), x.optString("action", null)});
            }
        } catch (Exception ignored) {
            // a broken shelf.json means an empty shelf, never a crash in the launcher's process
        }
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read only");
        List<String> seg = uri.getPathSegments();
        File f;
        if (seg.size() == 2 && "img".equals(seg.get(0)) && seg.get(1).matches("[A-Za-z0-9._-]+")) {
            f = new File(Store.dir(getContext(), "img"), seg.get(1));
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
