package it.lorenzoma97.casa;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The "Scopri una nuova serie" pool from content.json (rules in tv-casa-data/content.py): "all" = the best 100, by_genre = the
 * best up to 100 of each genre. Already watched titles never come back; skipped ones only once everything else has
 * been shown.
 */
final class Pool {
    static final int MIN_GENRE = 5;

    static final class Genre {
        final String code, label;
        final int count;

        Genre(String code, String label, int count) {
            this.code = code;
            this.label = label;
            this.count = count;
        }
    }

    private Pool() { }

    /** Genres with at least MIN_GENRE series, the richest first. */
    static List<Genre> genres(JSONObject pool) {
        List<Genre> out = new ArrayList<>();
        JSONObject g = pool == null ? null : pool.optJSONObject("genres");
        if (g == null) return out;
        JSONArray names = g.names();
        for (int i = 0; names != null && i < names.length(); i++) {
            String code = names.optString(i);
            JSONObject x = g.optJSONObject(code);
            if (x != null && x.optInt("count") >= MIN_GENRE) out.add(new Genre(code, x.optString("label", code), x.optInt("count")));
        }
        Collections.sort(out, (a, b) -> b.count != a.count ? Integer.compare(b.count, a.count) : a.label.compareTo(b.label));
        return out;
    }

    /** genre null = all. Order kept (best first), already watched and skipped removed. */
    static List<Long> candidates(JSONObject pool, String genre, Set<Long> seen, Set<Long> skipped) {
        List<Long> out = new ArrayList<>();
        if (pool == null) return out;
        JSONArray ids = genre == null ? pool.optJSONArray("all")
                : (pool.optJSONObject("by_genre") == null ? null : pool.optJSONObject("by_genre").optJSONArray(genre));
        for (int i = 0; ids != null && i < ids.length(); i++) {
            long id = ids.optLong(i);
            if (id > 0 && !seen.contains(id) && !skipped.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * Next title to propose, or -1 when every title of the genre has been watched. When only skipped ones are left
     * the skip list is emptied (they may come back, as agreed: "Un'altra" = skip for now).
     */
    static long next(JSONObject pool, String genre, Set<Long> seen, Set<Long> skipped, Random random) {
        List<Long> c = candidates(pool, genre, seen, skipped);
        if (c.isEmpty() && !skipped.isEmpty()) {
            skipped.clear();
            c = candidates(pool, genre, seen, skipped);
        }
        return c.isEmpty() ? -1 : c.get(random.nextInt(c.size()));
    }

    /** n different titles of the genre, never one already watched, in random order (fewer when the genre runs out). */
    static List<Long> pick(JSONObject pool, String genre, Set<Long> seen, Set<Long> avoid, int n, Random random) {
        List<Long> c = candidates(pool, genre, seen, avoid);
        if (c.size() < n) c = candidates(pool, genre, seen, java.util.Collections.emptySet());   // repeats over gaps
        Collections.shuffle(c, random);
        return new ArrayList<>(c.subList(0, Math.min(n, c.size())));
    }

    /**
     * Tonight's picks for "Tutte": the saved ones while they are from today and none was watched since, else n new.
     * Pure: the caller loads and saves (Store.tonight / Store.saveTonight).
     */
    static List<Long> tonight(JSONObject pool, List<Long> saved, Set<Long> seen, int n, Random random) {
        boolean ok = saved.size() == n;
        for (Long id : saved) ok &= !seen.contains(id);
        return ok ? new ArrayList<>(saved) : pick(pool, null, seen, java.util.Collections.emptySet(), n, random);
    }

    /** The pool entry for an id: services "s", genres "g", Netflix id "nf". */
    static JSONObject item(JSONObject pool, long id) {
        JSONArray items = pool == null ? null : pool.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject x = items.optJSONObject(i);
            if (x != null && x.optLong("tmdb") == id) return x;
        }
        return null;
    }

    /** Which service "Guarda" opens: Netflix first when available (it is the one with a direct link). */
    static String service(JSONObject item) {
        JSONArray s = item == null ? null : item.optJSONArray("s");
        if (s == null || s.length() == 0) return null;
        for (int i = 0; i < s.length(); i++) if ("netflix".equals(s.optString(i))) return "netflix";
        return s.optString(0);
    }
}
