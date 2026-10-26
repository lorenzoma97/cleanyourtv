package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
