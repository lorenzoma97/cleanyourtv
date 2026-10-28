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
        /** Badges for the shelf: Top 10 rank (0 = none), "Nuova stagione 4" / "Nuova serie", "Miniserie" / "Stagione 2". */
        final int top;
        final String tag, season;

        Entry(long tmdb, String line, String netflixId, int top, String tag, String season) {
            this.tmdb = tmdb;
            this.line = line;
            this.netflixId = netflixId;
            this.top = top;
            this.tag = tag;
            this.season = season;
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

    /**
     * Opens the title in the Netflix app (VIEW https://www.netflix.com/title/<id>, package-scoped). NEW_TASK|CLEAR_TASK
     * and source=30: on the Metz, a Netflix already running ignored the link and resumed the last series; started
     * clean it opened the title (tested 2026-10-27).
     */
    static String netflixIntent(String netflixId) {
        if (netflixId == null || !netflixId.matches("\\d+")) return null;
        return "intent://www.netflix.com/title/" + netflixId
                + "#Intent;scheme=https;action=android.intent.action.VIEW;package=com.netflix.ninja;launchFlags=0x10008000;S.source=30;end";
    }

    static final String PRIME_PKG = "com.amazon.amazonvideo.livingroom";
    static final String PRIME_CTA = "Cerca su Prime Video";

    /**
     * Prime Video's search for the title: the series comes first in the results. A title link needs Prime's own IDs,
     * which are not public; Wikidata has a third of ours, US-store ones that opened Prime's Home in Italy (tested
     * 2026-10-27). Prime may ask who is watching first; one OK and the search follows.
     */
    static String primeSearchIntent(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        String phrase;
        try {
            phrase = java.net.URLEncoder.encode(title.trim(), "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;   // UTF-8 is always there
        }
        return "intent://app.primevideo.com/search?phrase=" + phrase
                + "#Intent;scheme=https;action=android.intent.action.VIEW;package=" + PRIME_PKG + ";launchFlags=0x10008000;end";
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
                    String season = opt(x, "season");
                    list.add(new Entry(id, topLine(x.optInt("rank"), season), opt(x, "netflix_id"),
                            x.optInt("rank"), null, seasonLabel(season)));
                }
            }
            JSONArray news = novita == null ? null : novita.optJSONArray(svc);
            int cap = "netflix".equals(svc) ? list.size() + NETFLIX_NEW : OTHER_NEW;
            for (int i = 0; news != null && i < news.length() && list.size() < cap; i++) {
                JSONObject x = news.optJSONObject(i);
                long id = x == null ? 0 : x.optLong("tmdb", 0);
                if (id <= 0 || !seen.add(id)) continue;
                String line = novitaLine(x.optString("kind"), x.optInt("season", 1));
                list.add(new Entry(id, line, opt(x, "nf"), 0, line, null));
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
                            .put("image", img.getName())
                            .put("plot", Tmdb.shortPlot(d.optString("overview"), 170))
                            .put("top", en.top);
                    if (en.tag != null) item.put("tag", en.tag);
                    if (en.season != null) item.put("season", en.season);
                    item.put("meta", Tmdb.meta(d));
                    String logo = Tmdb.logo(d);
                    if (logo != null) {
                        try {
                            item.put("logo", Tmdb.image(ctx, "img", logo, "w500").getName());
                        } catch (Exception ex) {
                            Log.w(TAG, "logo " + en.tmdb + ": " + ex);   // the title shows as text instead
                        }
                    }
                    String action = netflixIntent(en.netflixId);
                    if (action == null && PRIME_PKG.equals(e.getKey())) {
                        action = primeSearchIntent(d.optString("name"));
                        if (action != null) item.put("cta", PRIME_CTA);
                    }
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
