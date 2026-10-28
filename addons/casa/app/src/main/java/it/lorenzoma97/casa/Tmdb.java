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

    /** A logo drawn for light backgrounds comes back white (alpha kept), so it reads on the dark page; null stays null. */
    static android.graphics.Bitmap lightLogo(android.graphics.Bitmap b) {
        if (b == null) return null;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        if (!darkLogo(px)) return b;
        for (int i = 0; i < px.length; i++) px[i] |= 0x00FFFFFF;
        android.graphics.Bitmap white = android.graphics.Bitmap.createBitmap(px, w, h, android.graphics.Bitmap.Config.ARGB_8888);
        b.recycle();
        return white;
    }

    /**
     * True when most of the logo's solid pixels are dark (black lettering: Terminal List, Painkiller, Beckham). A logo
     * that is mostly solid is a dark plate with light text (Jury Duty) and stays as it is. Same rule as the launcher's
     * ContentShelf.darkLogo (top shelf).
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

    /**
     * The facts the launcher's detail shows after its badges: "2024 · Crime · 2 stagioni · ★ 7,8" (first year, first
     * genre, seasons or Miniserie, TMDB rating once 20 people voted). Parts TMDB lacks are left out; null when none.
     */
    static String meta(JSONObject d) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        String date = d.isNull("first_air_date") ? "" : d.optString("first_air_date", "");
        if (date.matches("\\d{4}.*")) parts.add(date.substring(0, 4));
        JSONArray genres = d.optJSONArray("genres");
        JSONObject genre = genres == null ? null : genres.optJSONObject(0);
        if (genre != null && !genre.optString("name", "").trim().isEmpty()) parts.add(genre.optString("name").trim());
        int seasons = d.optInt("number_of_seasons", 0);
        if ("Miniseries".equals(d.optString("type"))) parts.add("Miniserie");
        else if (seasons > 0) parts.add(seasons == 1 ? "1 stagione" : seasons + " stagioni");
        if (d.optInt("vote_count", 0) >= 20 && d.optDouble("vote_average", 0) > 0) {
            parts.add(String.format(java.util.Locale.ITALY, "★ %.1f", d.optDouble("vote_average")));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
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

    /**
     * The TMDB series for a title another app published (Watch Next): Italian search, exact name first, then the most
     * popular. Remembered per title for a week, a miss too. -1 when nothing matches or there is no key.
     */
    static long findSeries(Context ctx, String title) {
        if (title == null || title.trim().isEmpty()) return -1;
        String norm = java.text.Normalizer.normalize(title.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("[^a-z0-9]+", " ").trim();
        File f = new File(Store.dir(ctx, "tmdb"), "find_" + Integer.toHexString(norm.hashCode()) + ".txt");
        if (Store.fresh(f, DETAILS_TTL)) {
            try {
                return Long.parseLong(Store.read(f).trim());
            } catch (Exception ignored) {
            }
        }
        String key = Store.tmdbKey(ctx);
        if (key == null) return -1;
        long found = -1;
        try {
            JSONArray res = new JSONObject(new String(Net.get("https://api.themoviedb.org/3/search/tv?language=it-IT&query="
                    + java.net.URLEncoder.encode(title.trim(), "UTF-8") + "&api_key=" + key), StandardCharsets.UTF_8))
                    .optJSONArray("results");
            double best = -1;
            for (int i = 0; res != null && i < res.length(); i++) {
                JSONObject x = res.optJSONObject(i);
                if (x == null || x.isNull("backdrop_path")) continue;
                String n1 = java.text.Normalizer.normalize(x.optString("name").toLowerCase(), java.text.Normalizer.Form.NFD).replaceAll("[^a-z0-9]+", " ").trim();
                String n2 = java.text.Normalizer.normalize(x.optString("original_name").toLowerCase(), java.text.Normalizer.Form.NFD).replaceAll("[^a-z0-9]+", " ").trim();
                double score = (norm.equals(n1) || norm.equals(n2) ? 100000 : 0) + x.optDouble("popularity", 0);
                if (score > best) {
                    best = score;
                    found = x.optLong("id");
                }
            }
            Store.write(f, Long.toString(found));
        } catch (Exception e) {
            android.util.Log.w("Casa", "find " + title + ": " + e);
        }
        return found;
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
