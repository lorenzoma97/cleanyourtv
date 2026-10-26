package it.lorenzoma97.casa;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The launcher's top shelf per app: which titles, in which order, with which line of text. Built from content.json
 * (tv-casa-data) plus TMDB details, written to shelf.json and served by {@link HeroProvider}.
 *   Netflix: Top 10 Italia (one per series) then Novita.  Prime, RaiPlay, Infinity: Novita.
 * "Riprendi" (Watch Next) is not here: the launcher reads it from the TV itself.
 */
final class Shelf {
    static final long CONTENT_TTL = 6L * 3600 * 1000;
    static final int NETFLIX_TOP = 8, NETFLIX_NEW = 4, OTHER_NEW = 10;
    private static final String TAG = "Casa";
    private static final Pattern SEASON = Pattern.compile("(?i).*?:\\s*(season|stagione|part|parte|volume)\\s+(\\d+)\\s*$");

    static final class Entry {
        final long tmdb;
        final String line;
        final String netflixId;

        Entry(long tmdb, String line, String netflixId) {
            this.tmdb = tmdb;
            this.line = line;
            this.netflixId = netflixId;
        }
    }

    private Shelf() { }

    // ------------------------------------------------------------------------------------- rules (pure)

    /** "Real Men: Season 2" -> "Stagione 2"; "Below: Limited Series" -> "Miniserie"; anything else -> null. */
    static String seasonLabel(String netflixSeason) {
        if (netflixSeason == null || netflixSeason.trim().isEmpty()) return null;
        if (netflixSeason.toLowerCase().endsWith("limited series")) return "Miniserie";
        Matcher m = SEASON.matcher(netflixSeason.trim());
        if (!m.matches()) return null;
        String kind = m.group(1).toLowerCase();
        String n = m.group(2);
        if (kind.startsWith("season") || kind.startsWith("stagione")) return "Stagione " + n;
        if (kind.startsWith("part") || kind.startsWith("parte")) return "Parte " + n;
        return "Volume " + n;
    }

    static String topLine(int rank, String netflixSeason) {
        String s = seasonLabel(netflixSeason);
        return "Top 10 in Italia · n. " + rank + (s == null ? "" : " · " + s);
    }

    static String novitaLine(String kind, int season) {
        return "new_series".equals(kind) ? "Nuova serie" : "Nuova stagione " + season;
    }

    /** Opens the title in the Netflix app (VIEW https://www.netflix.com/title/<id>, package-scoped). */
    static String netflixIntent(String netflixId) {
        if (netflixId == null || !netflixId.matches("\\d+")) return null;
        return "intent://www.netflix.com/title/" + netflixId
                + "#Intent;scheme=https;action=android.intent.action.VIEW;package=com.netflix.ninja;launchFlags=0x10000000;end";
    }

    /** package -> ordered entries, capped, one per series. */
    static Map<String, List<Entry>> plan(JSONObject content) {
        Map<String, List<Entry>> out = new LinkedHashMap<>();
        JSONObject services = content.optJSONObject("services");
        if (services == null) return out;
        JSONObject novita = content.optJSONObject("novita");
        for (String svc : new String[]{"netflix", "prime", "raiplay", "infinity"}) {
            JSONObject s = services.optJSONObject(svc);
            if (s == null) continue;
            String pkg = s.optString("package");
            List<Entry> list = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            if ("netflix".equals(svc)) {
                JSONObject top = content.optJSONObject("top10_netflix");
                JSONArray items = top == null ? null : top.optJSONArray("items");
                for (int i = 0; items != null && i < items.length() && list.size() < NETFLIX_TOP; i++) {
                    JSONObject x = items.optJSONObject(i);
                    long id = x == null ? 0 : x.optLong("tmdb", 0);
                    if (id <= 0 || !seen.add(id)) continue;   // Maschi veri S1 and S2 both in the chart: once
                    list.add(new Entry(id, topLine(x.optInt("rank"), x.optString("season", null)), opt(x, "netflix_id")));
                }
            }
            JSONArray news = novita == null ? null : novita.optJSONArray(svc);
            int cap = "netflix".equals(svc) ? list.size() + NETFLIX_NEW : OTHER_NEW;
            for (int i = 0; news != null && i < news.length() && list.size() < cap; i++) {
                JSONObject x = news.optJSONObject(i);
                long id = x == null ? 0 : x.optLong("tmdb", 0);
                if (id <= 0 || !seen.add(id)) continue;
                list.add(new Entry(id, novitaLine(x.optString("kind"), x.optInt("season", 1)), opt(x, "nf")));
            }
            if (!list.isEmpty()) out.put(pkg, list);
        }
        return out;
    }

    private static String opt(JSONObject x, String key) {
        return x.isNull(key) ? null : x.optString(key, null);
    }

    // ------------------------------------------------------------------------------------- refresh (Android)

    /** content.json, cached on the TV; downloaded again when older than CONTENT_TTL or when forced. */
    static JSONObject content(Context ctx, boolean force) {
        File f = Store.file(ctx, "content.json");
        if (force || !Store.fresh(f, CONTENT_TTL)) {
            try {
                String text = new String(Net.get(Store.CONTENT_URL), StandardCharsets.UTF_8);
                new JSONObject(text);   // validate before replacing the good copy
                Store.write(f, text);
            } catch (Exception e) {
                Log.w(TAG, "content.json: " + e);
            }
        }
        try {
            String text = Store.read(f);
            return text == null ? null : new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Rebuilds shelf.json: for every entry the Italian title, the line, a local backdrop and the action. */
    static void refresh(Context ctx, boolean force) {
        JSONObject content = content(ctx, force);
        if (content == null) return;
        JSONObject shelf = new JSONObject();
        int images = 0;
        for (Map.Entry<String, List<Entry>> e : plan(content).entrySet()) {
            JSONArray items = new JSONArray();
            for (Entry en : e.getValue()) {
                try {
                    JSONObject d = Tmdb.details(ctx, en.tmdb);
                    if (d == null) continue;
                    String path = Tmdb.backdrop(d);
                    if (path == null) continue;   // the shelf is made of pictures: no picture, no item
                    File img = Tmdb.image(ctx, "img", path, "w1280");
                    images++;
                    JSONObject item = new JSONObject()
                            .put("tmdb", en.tmdb)
                            .put("title", d.optString("name"))
                            .put("line", en.line)
                            .put("image", img.getName());
                    String action = netflixIntent(en.netflixId);
                    if (action != null) item.put("action", action);
                    items.put(item);
                } catch (Exception ex) {
                    Log.w(TAG, "shelf " + en.tmdb + ": " + ex);
                }
            }
            try {
                shelf.put(e.getKey(), items);
            } catch (JSONException ignored) {
            }
        }
        try {
            shelf.put("generated_at", content.optString("generated_at"));
            Store.write(Store.file(ctx, "shelf.json"), shelf.toString());
        } catch (Exception ex) {
            Log.w(TAG, "shelf.json: " + ex);
        }
        Store.trim(Store.dir(ctx, "img"), 30L * 1024 * 1024);
        Log.i(TAG, "shelf refreshed: " + images + " images");
    }
}
