package it.lorenzoma97.casa;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
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
        assertEquals("Novità", Shelf.novitaLine("new_series", 1));
        assertEquals("Nuova stagione 4", Shelf.novitaLine("new_season", 4));
    }

    @Test
    public void seasonTagSaysWhen() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 30);
        JSONObject d = new JSONObject("{\"seasons\":[{\"season_number\":2,\"air_date\":\"2025-03-01\"},"
                + "{\"season_number\":3,\"air_date\":\"2026-10-09\"},{\"season_number\":4,\"air_date\":null}]}");
        assertEquals("Nuova stagione 3 · dal 9 ottobre", Shelf.seasonTag("Nuova stagione 3", d, 3, today));
        assertEquals("Nuova stagione 2 · dal 1° marzo 2025", Shelf.seasonTag("Nuova stagione 2", d, 2, today));
        assertEquals("Nuova stagione 4", Shelf.seasonTag("Nuova stagione 4", d, 4, today));   // no date yet
        assertEquals("Nuova stagione 5", Shelf.seasonTag("Nuova stagione 5", d, 5, today));   // not in TMDB yet
        assertEquals("Novità", Shelf.seasonTag("Novità", d, 0, today));
        assertNull(Shelf.seasonTag(null, d, 3, today));
    }

    @Test
    public void nextEpisodeWithinTwoWeeks() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 30);
        assertEquals("Nuovo episodio ven 6 nov", Shelf.nextEpisode(next("2026-11-06"), today));
        assertEquals("Nuovo episodio ven 30 ott", Shelf.nextEpisode(next("2026-10-30"), today));
        assertEquals("Nuovo episodio ven 13 nov", Shelf.nextEpisode(next("2026-11-13"), today));   // day 14
        assertNull(Shelf.nextEpisode(next("2026-11-14"), today));
        assertNull(Shelf.nextEpisode(next("2026-10-29"), today));                                  // already out
        assertNull(Shelf.nextEpisode(new JSONObject("{\"next_episode_to_air\":null}"), today));
        assertNull(Shelf.nextEpisode(new JSONObject("{\"next_episode_to_air\":{\"air_date\":null}}"), today));
    }

    private static JSONObject next(String date) throws Exception {
        return new JSONObject("{\"next_episode_to_air\":{\"air_date\":\"" + date + "\",\"episode_number\":4}}");
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
        assertEquals(3, nf.get(Shelf.NETFLIX_TOP).newSeason);   // its start date goes on the tag
        assertEquals(0, nf.get(Shelf.NETFLIX_TOP + 1).newSeason);
        assertEquals(0, nf.get(0).newSeason);
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
    public void plotCut() {
        // A sentence end from 60 characters on wins over a longer cut at a word.
        String first = "Una detective di Oslo torna nella città natale per un caso che riapre vecchie ferite.";
        String plot = first + " " + "Intanto il fratello sparisce e lei deve scegliere tra il lavoro e la famiglia, mentre la neve cade.";
        assertEquals(first, Tmdb.shortPlot(plot, 170));
        // No dangling punctuation before the ellipsis.
        assertEquals("Alfa beta gamma delta…", Tmdb.shortPlot("Alfa beta gamma delta, epsilon zeta eta theta", 24));
        assertEquals("Alfa beta gamma…", Tmdb.shortPlot("Alfa beta gamma – delta epsilon zeta", 17));
        assertEquals("Alfa beta gamma…", Tmdb.shortPlot("Alfa beta gamma: delta epsilon zeta", 17));
        assertEquals("Alfa beta gamma…", Tmdb.shortPlot("Alfa beta gamma; delta epsilon zeta", 17));
    }

    @Test
    public void genresInItalian() throws Exception {
        assertEquals("Azione e avventura", Tmdb.genreIt("Action & Adventure"));
        assertEquals("Fantascienza e fantasy", Tmdb.genreIt("Sci-Fi & Fantasy"));
        assertEquals("Guerra e politica", Tmdb.genreIt("War & Politics"));
        assertEquals("Per ragazzi", Tmdb.genreIt("Kids"));
        assertEquals("Attualità", Tmdb.genreIt("News"));
        assertEquals("Talk show", Tmdb.genreIt("Talk"));
        assertEquals("Soap opera", Tmdb.genreIt("Soap"));
        assertEquals("Dramma", Tmdb.genreIt(" Dramma "));
        assertEquals("2023 · Fantascienza e fantasy · 1 stagione", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2023-05-02\","
                + "\"genres\":[{\"name\":\"Sci-Fi & Fantasy\"}],\"number_of_seasons\":1}")));
    }

    @Test
    public void meta() throws Exception {
        assertEquals("2024 · Crime · 2 stagioni · ★ 7,8", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2024-03-01\","
                + "\"genres\":[{\"name\":\"Crime\"},{\"name\":\"Dramma\"}],\"number_of_seasons\":2,"
                + "\"vote_average\":7.84,\"vote_count\":312}")));
        assertEquals("2025 · Miniserie", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2025-01-09\","
                + "\"type\":\"Miniseries\",\"number_of_seasons\":1,\"vote_average\":9.5,\"vote_count\":4}")));
        assertNull(Tmdb.meta(new JSONObject("{\"first_air_date\":null,\"genres\":[]}")));
        // Next to a season badge: no year, no season count ("NUOVA STAGIONE 4 · Crime · ★ 8,3").
        assertEquals("Crime · ★ 8,3", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2019-03-01\","
                + "\"genres\":[{\"name\":\"Crime\"}],\"number_of_seasons\":4,\"vote_average\":8.27,\"vote_count\":900}"), true));
        assertEquals("Thriller", Tmdb.meta(new JSONObject("{\"first_air_date\":\"2026-10-01\",\"type\":\"Miniseries\","
                + "\"genres\":[{\"name\":\"Thriller\"}],\"number_of_seasons\":1}"), true));
    }

    @Test
    public void releaseSums() {
        String a = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        java.util.Map<String, String> s = Updater.sums(a + "  tvhome-release.apk\n" + a.toUpperCase() + " *casa-release.apk\n\nbroken line\n");
        assertEquals(2, s.size());
        assertEquals(a, s.get("tvhome-release.apk"));
        assertEquals(a, s.get("casa-release.apk"));   // binary marker and upper case as sha256sum may write them
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
