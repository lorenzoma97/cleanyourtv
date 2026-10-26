package it.lorenzoma97.casa;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** TMDB details for a TV series, cached a week on the TV; images from the TMDB CDN (no key needed there). */
final class Tmdb {
    private static final long DETAILS_TTL = 7L * 24 * 3600 * 1000;
    static final String IMAGE_BASE = "https://image.tmdb.org/t/p/";

    private Tmdb() { }

    /** Italian details plus images and videos; null when there is no key or no network and nothing cached. */
    static JSONObject details(Context ctx, long id) {
        File f = new File(Store.dir(ctx, "tmdb"), id + ".json");
        if (Store.fresh(f, DETAILS_TTL)) {
            JSONObject cached = parse(Store.read(f));
            if (cached != null) return cached;
        }
        String key = Store.tmdbKey(ctx);
        if (key != null) {
            try {
                String url = "https://api.themoviedb.org/3/tv/" + id + "?language=it-IT&append_to_response=images,videos"
                        + "&include_image_language=it,en,null&include_video_language=it,en&api_key=" + key;
                String text = new String(Net.get(url), StandardCharsets.UTF_8);
                JSONObject d = new JSONObject(text);
                Store.write(f, text);
                return d;
            } catch (IOException | JSONException e) {
                android.util.Log.w("Casa", "TMDB " + id + ": " + e);
            }
        }
        return f.isFile() ? parse(Store.read(f)) : null;   // stale is better than nothing
    }

    /** Best backdrop path: a textless one (our own title goes on top), the best voted; else the default one. */
    static String backdrop(JSONObject d) {
        JSONArray b = d.optJSONObject("images") != null ? d.optJSONObject("images").optJSONArray("backdrops") : null;
        String best = null;
        double score = -1;
        if (b != null) {
            for (int i = 0; i < b.length(); i++) {
                JSONObject x = b.optJSONObject(i);
                if (x == null || !x.isNull("iso_639_1")) continue;
                double v = x.optDouble("vote_average", 0) + x.optInt("vote_count", 0) / 1000.0;
                if (v > score) {
                    score = v;
                    best = x.optString("file_path", null);
                }
            }
        }
        if (best == null) best = d.optString("backdrop_path", null);
        return best == null || best.isEmpty() || "null".equals(best) ? null : best;
    }

    /** The title's logo (transparent PNG, the Netflix / Apple TV look): Italian, then English, then language-free. */
    static String logo(JSONObject d) {
        JSONArray l = d.optJSONObject("images") != null ? d.optJSONObject("images").optJSONArray("logos") : null;
        if (l == null) return null;
        for (String lang : new String[]{"it", "en", null}) {
            String best = null;
            double score = -1;
            for (int i = 0; i < l.length(); i++) {
                JSONObject x = l.optJSONObject(i);
                if (x == null) continue;
                boolean match = lang == null ? x.isNull("iso_639_1") : lang.equals(x.optString("iso_639_1"));
                String path = x.optString("file_path", "");
                // SVG logos would need a renderer the TV does not have; PNG only.
                if (!match || !path.endsWith(".png")) continue;
                double v = x.optDouble("vote_average", 0) + x.optInt("vote_count", 0) / 1000.0;
                if (v > score) {
                    score = v;
                    best = path;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** The plot cut at a sentence or word boundary near max characters (the shelf shows two lines). */
    static String shortPlot(String overview, int max) {
        if (overview == null) return "";
        String s = overview.trim().replaceAll("\\s+", " ");
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(". ", max);
        if (cut > max / 2) return s.substring(0, cut + 1);
        cut = s.lastIndexOf(' ', max);
        return (cut > 0 ? s.substring(0, cut) : s.substring(0, max)) + "…";
    }

    /** YouTube key of the trailer: Italian first, then English; teasers only when there is no trailer. */
    static String trailer(JSONObject d) {
        JSONArray v = d.optJSONObject("videos") != null ? d.optJSONObject("videos").optJSONArray("results") : null;
        if (v == null) return null;
        String[][] order = {{"it", "Trailer"}, {"en", "Trailer"}, {"it", "Teaser"}, {"en", "Teaser"}};
        for (String[] want : order) {
            for (int i = 0; i < v.length(); i++) {
                JSONObject x = v.optJSONObject(i);
                if (x != null && "YouTube".equals(x.optString("site")) && want[1].equals(x.optString("type"))
                        && want[0].equals(x.optString("iso_639_1"))) {
                    return x.optString("key", null);
                }
            }
        }
        return null;
    }

    /** Local file for a TMDB image path, downloaded once at the given size (w780, w1280...). */
    static File image(Context ctx, String dirName, String path, String size) throws IOException {
        String name = size + "_" + path.replaceAll("[^A-Za-z0-9._-]", "");
        File f = new File(Store.dir(ctx, dirName), name);
        if (!f.isFile()) Net.download(IMAGE_BASE + size + path, f);
        f.setLastModified(System.currentTimeMillis());
        return f;
    }

    private static JSONObject parse(String text) {
        if (text == null) return null;
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }
}
