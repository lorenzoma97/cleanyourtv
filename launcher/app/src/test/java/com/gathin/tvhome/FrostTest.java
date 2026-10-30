package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrostTest {
    @Test
    public void aBrightDotSpreadsEvenlyAndKeepsItsLight() {
        int w = 15, h = 15;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF000000);
        px[7 * w + 7] = 0xFFFFFFFF;
        Frost.boxBlur(px, w, h, 2);
        int total = 0;
        for (int c : px) {
            assertEquals(0xFF, c >>> 24);   // still opaque
            total += c & 0xFF;
        }
        // 255 spread over 5x5 pixels (integer division per pass loses a little).
        assertTrue(total >= 25 * 10 && total <= 255);
        assertEquals(px[7 * w + 5], px[7 * w + 9]);   // left = right
        assertEquals(px[5 * w + 7], px[9 * w + 7]);   // up = down
        assertEquals(0xFF000000, px[7 * w + 4]);       // nothing beyond the radius
    }

    @Test
    public void edgesAreClampedNotDarkened() {
        int[] px = new int[8 * 4];
        java.util.Arrays.fill(px, 0xFF808080);
        Frost.boxBlur(px, 8, 4, 2);
        for (int c : px) assertEquals(0xFF808080, c);
    }
}
