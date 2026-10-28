package it.lorenzoma97.casa;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                + "package=com.netflix.ninja;launchFlags=0x10008000;S.source=30;end", Shelf.netflixIntent("81652677"));
        assertNull(Shelf.netflixIntent(null));
        assertNull(Shelf.netflixIntent("81652677;launchFlags=0"));   // never splice anything but digits
    }

    @Test
    public void primeSearch() {
        assertEquals("intent://app.primevideo.com/search?phrase=Gli%20anelli%20del%20potere#Intent;scheme=https;"
                        + "action=android.intent.action.VIEW;package=com.amazon.amazonvideo.livingroom;launchFlags=0x10008000;end",
                Shelf.primeSearchIntent(" Gli anelli del potere "));
        // ; and # would end the intent URI early: they must arrive encoded
        assertEquals("intent://app.primevideo.com/search?phrase=A%3BB%23C%26D#Intent;scheme=https;"
                        + "action=android.intent.action.VIEW;package=com.amazon.amazonvideo.livingroom;launchFlags=0x10008000;end",
                Shelf.primeSearchIntent("A;B#C&D"));
        assertNull(Shelf.primeSearchIntent(" "));
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
        assertEquals(1, nf.get(0).top);                       // badges for the redesigned shelf
        assertNull(nf.get(0).tag);
        assertEquals(0, nf.get(Shelf.NETFLIX_TOP).top);
        assertEquals("Nuova stagione 3", nf.get(Shelf.NETFLIX_TOP).tag);
    }

    @Test
    public void logoAndPlot() throws Exception {
        JSONObject d = new JSONObject("{\"images\":{\"logos\":["
                + "{\"file_path\":\"/en.png\",\"iso_639_1\":\"en\",\"vote_average\":5.4},"
                + "{\"file_path\":\"/it.svg\",\"iso_639_1\":\"it\",\"vote_average\":9},"      // SVG: the TV cannot draw it
                + "{\"file_path\":\"/it.png\",\"iso_639_1\":\"it\",\"vote_average\":5.1}]}}");
        assertEquals("/it.png", Tmdb.logo(d));
        assertNull(Tmdb.logo(new JSONObject("{\"images\":{\"logos\":[]}}")));
        assertEquals("Breve.", Tmdb.shortPlot("Breve.", 170));
        String plot = Tmdb.shortPlot("Prima frase abbastanza lunga da contare. Seconda frase che non entra nel limite dei caratteri.", 60);
        assertEquals("Prima frase abbastanza lunga da contare.", plot);
        assertTrue(Tmdb.shortPlot("parola ".repeat(40), 50).endsWith("…"));
    }

    @Test
    public void meta() throws Exception {
        assertEquals("2024 · Crime · 2 stagioni · ★ 7,8", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2024-03-01\","
                + "\"genres\":[{\"name\":\"Crime\"},{\"name\":\"Dramma\"}],\"number_of_seasons\":2,"
                + "\"vote_average\":7.84,\"vote_count\":312}")));
        assertEquals("2025 · Miniserie", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2025-01-09\","
                + "\"type\":\"Miniseries\",\"number_of_seasons\":1,\"vote_average\":9.5,\"vote_count\":4}")));
        assertNull(Tmdb.meta(new JSONObject("{\"first_air_date\":null,\"genres\":[]}")));
    }

    @Test
    public void darkLogo() {
        int clear = 0x00000000, black = 0xFF101010, red = 0xFFE50914, yellow = 0xFFF5C518;
        assertTrue(Tmdb.darkLogo(new int[]{black, black, black, yellow, clear, clear, clear, clear, clear, clear}));
        assertFalse(Tmdb.darkLogo(new int[]{red, red, red, clear, clear, clear, clear, clear, clear, clear}));
        assertFalse(Tmdb.darkLogo(new int[]{black, black, black, black, black, black, black, black, black, clear}));   // a plate
    }

    @Test
    public void weatherWords() {
        assertEquals("Sereno", Weather.describe(0, true)[0]);
        assertEquals("moon", Weather.describe(0, false)[1]);
        assertEquals("Poco nuvoloso", Weather.describe(2, true)[0]);
        assertEquals("fog", Weather.describe(45, true)[1]);
        assertEquals("Pioggia forte", Weather.describe(65, true)[0]);
        assertEquals("storm", Weather.describe(95, true)[1]);
        assertEquals("Aria buona", Weather.air(18));
        assertEquals("Aria discreta", Weather.air(40));
        assertEquals("Aria scadente", Weather.air(61));
        assertNull(Weather.air(-1));
    }
}
