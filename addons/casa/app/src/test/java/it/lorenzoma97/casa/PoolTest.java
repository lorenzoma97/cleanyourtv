package it.lorenzoma97.casa;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for "Serie a caso": genres, watched titles never back, skipped ones only at the end. */
public class PoolTest {
    private static JSONObject pool() throws Exception {
        return new JSONObject("{"
                + "\"all\":[1,2,3,4],"
                + "\"by_genre\":{\"thriller\":[3,5],\"commedia\":[2]},"
                + "\"genres\":{\"thriller\":{\"label\":\"Thriller\",\"count\":28},\"commedia\":{\"label\":\"Commedia\",\"count\":52},"
                + "\"western\":{\"label\":\"Western\",\"count\":2}},"
                + "\"items\":[{\"tmdb\":3,\"s\":[\"prime\",\"netflix\"],\"g\":[\"thriller\"],\"nf\":\"9\"},{\"tmdb\":5,\"s\":[\"prime\"],\"g\":[\"thriller\"]}]}");
    }

    @Test
    public void genresRichestFirstAndSmallOnesHidden() throws Exception {
        List<Pool.Genre> g = Pool.genres(pool());
        assertEquals(2, g.size());
        assertEquals("commedia", g.get(0).code);
        assertEquals("thriller", g.get(1).code);
    }

    @Test
    public void watchedNeverComeBackSkippedOnlyAtTheEnd() throws Exception {
        JSONObject p = pool();
        Set<Long> seen = new HashSet<>(Arrays.asList(1L, 2L));
        Set<Long> skipped = new HashSet<>(Arrays.asList(3L));
        assertEquals(Arrays.asList(4L), Pool.candidates(p, null, seen, skipped));
        assertEquals(Arrays.asList(5L), Pool.candidates(p, "thriller", seen, skipped));
        // thriller: 5 watched too, 3 skipped -> the skip list empties and 3 comes back
        seen.add(5L);
        assertEquals(3L, Pool.next(p, "thriller", seen, skipped, new Random(1)));
        assertTrue(skipped.isEmpty());
        // everything watched: -1
        seen.add(3L);
        assertEquals(-1L, Pool.next(p, "thriller", seen, skipped, new Random(1)));
    }

    @Test
    public void guardaPrefersNetflix() throws Exception {
        JSONObject p = pool();
        assertEquals("netflix", Pool.service(Pool.item(p, 3)));
        assertEquals("prime", Pool.service(Pool.item(p, 5)));
        assertEquals(null, Pool.service(Pool.item(p, 4)));   // not in items: nothing to open
    }
}
