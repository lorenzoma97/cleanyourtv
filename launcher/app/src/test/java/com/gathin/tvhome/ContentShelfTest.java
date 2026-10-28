package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plain JVM test for the "Riprendi" line on the content shelf (fork lorenzoma97). */
public class ContentShelfTest {
    @Test
    public void resumeLine() {
        assertEquals("Riprendi · S2E5 · 40%", ContentShelf.resumeLine("2", "5", "Il ritorno", 40, 100));
        assertEquals("Riprendi · Il ritorno", ContentShelf.resumeLine(null, " ", "Il ritorno", 0, 0));
        assertEquals("Riprendi · E3", ContentShelf.resumeLine("", "3", null, 10, 0));
        assertEquals("Riprendi · 99%", ContentShelf.resumeLine(null, null, null, 100, 100));   // never "100%"
        assertEquals("Riprendi", ContentShelf.resumeLine(null, null, null, 0, 0));
    }

    @Test
    public void darkLogo() {
        int clear = 0x00000000, black = 0xFF101010, darkRed = 0xFF500808, red = 0xFFE50914, yellow = 0xFFF5C518;
        assertTrue(ContentShelf.darkLogo(new int[]{black, black, black, clear, clear, clear, clear, clear, clear, clear}));
        assertTrue(ContentShelf.darkLogo(new int[]{darkRed, darkRed, clear, clear, clear}));
        assertTrue(ContentShelf.darkLogo(new int[]{black, black, black, yellow, clear, clear, clear, clear, clear, clear}));
        assertFalse(ContentShelf.darkLogo(new int[]{red, red, red, clear, clear, clear, clear, clear, clear, clear}));
        assertFalse(ContentShelf.darkLogo(new int[]{black, yellow, clear, clear, clear, clear}));     // half bright
        assertFalse(ContentShelf.darkLogo(new int[]{black, black, black, black, black, black, black, black, black, clear}));   // a plate
        assertFalse(ContentShelf.darkLogo(new int[]{clear, clear}));
    }
}
