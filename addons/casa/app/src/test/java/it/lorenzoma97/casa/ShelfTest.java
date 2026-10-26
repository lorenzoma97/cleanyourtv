package it.lorenzoma97.casa;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the top shelf rules: lines, the Netflix link and the per-app plan. */
public class ShelfTest {

    @Test
    public void seasonLabels() {
        assertEquals("Stagione 2", Shelf.seasonLabel("Real Men: Season 2"));
        assertEquals("Miniserie", Shelf.seasonLabel("Below: Limited Series"));
        assertEquals("Parte 3", Shelf.seasonLabel("Money Heist: Part 3"));
        assertEquals("Volume 2", Shelf.seasonLabel("Stranger Things: Volume 2"));
        assertNull(Shelf.seasonLabel(null));
        assertNull(Shelf.seasonLabel("null"));
        assertNull(Shelf.seasonLabel("Some Show"));
    }

    @Test
    public void lines() {
        assertEquals("Top 10 in Italia · n. 1 · Miniserie", Shelf.topLine(1, "Below: Limited Series"));
        assertEquals("Top 10 in Italia · n. 7", Shelf.topLine(7, null));
        assertEquals("Nuova serie", Shelf.novitaLine("new_series", 1));
        assertEquals("Nuova stagione 4", Shelf.novitaLine("new_season", 4));
    }

    @Test
    public void netflixLink() {
        assertEquals("intent://www.netflix.com/title/81652677#Intent;scheme=https;action=android.intent.action.VIEW;"
                + "package=com.netflix.ninja;launchFlags=0x10000000;end", Shelf.netflixIntent("81652677"));
        assertNull(Shelf.netflixIntent(null));
        assertNull(Shelf.netflixIntent("81652677;launchFlags=0"));   // never splice anything but digits
    }

    @Test
    public void planDedupesAndCaps() throws Exception {
        StringBuilder top = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            if (i > 1) top.append(',');
            long id = i == 8 ? 102 : 100 + i;   // rank 8 = same series as rank 2 (Maschi veri S1 and S2)
            top.append("{\"rank\":").append(i).append(",\"tmdb\":").append(id).append(",\"season\":null,\"netflix_id\":\"5").append(i).append("\"}");
        }
        JSONObject content = new JSONObject("{"
                + "\"services\":{\"netflix\":{\"name\":\"Netflix\",\"package\":\"com.netflix.ninja\"},"
                + "\"prime\":{\"name\":\"Prime Video\",\"package\":\"com.amazon.amazonvideo.livingroom\"},"
                + "\"infinity\":{\"name\":\"Mediaset Infinity\",\"package\":\"it.mediaset.infinitytv\"}},"
                + "\"top10_netflix\":{\"week\":\"2026-10-18\",\"items\":[" + top + "]},"
                + "\"novita\":{\"netflix\":[{\"tmdb\":101,\"kind\":\"new_series\",\"season\":1},"
                + "{\"tmdb\":900,\"kind\":\"new_season\",\"season\":3,\"nf\":\"77\"},{\"tmdb\":901,\"kind\":\"new_series\",\"season\":1},"
                + "{\"tmdb\":902,\"kind\":\"new_series\",\"season\":1},{\"tmdb\":903,\"kind\":\"new_series\",\"season\":1},"
                + "{\"tmdb\":904,\"kind\":\"new_series\",\"season\":1}],"
                + "\"prime\":[{\"tmdb\":300,\"kind\":\"new_season\",\"season\":2}],\"infinity\":[]}}");
        Map<String, List<Shelf.Entry>> plan = Shelf.plan(content);
        List<Shelf.Entry> nf = plan.get("com.netflix.ninja");
        assertEquals(Shelf.NETFLIX_TOP + Shelf.NETFLIX_NEW, nf.size());
        assertEquals(101, nf.get(0).tmdb);
        assertEquals("51", nf.get(0).netflixId);
        for (int i = 0; i < nf.size(); i++) for (int j = i + 1; j < nf.size(); j++) assertTrue(nf.get(i).tmdb != nf.get(j).tmdb);
        assertEquals(900, nf.get(Shelf.NETFLIX_TOP).tmdb);   // 101 is already in the Top 10: novita starts at 900
        assertEquals("Nuova stagione 3", nf.get(Shelf.NETFLIX_TOP).line);
        assertEquals("77", nf.get(Shelf.NETFLIX_TOP).netflixId);
        assertEquals(1, plan.get("com.amazon.amazonvideo.livingroom").size());
        assertNull(plan.get("it.mediaset.infinitytv"));       // nothing to show: no entry, the launcher keeps the app art
    }
}
