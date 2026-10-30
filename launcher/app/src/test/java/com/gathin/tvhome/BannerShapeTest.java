package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BannerShapeTest {
    @Test
    public void wideBannersAreKept() {
        assertTrue(AppAdapter.isBannerShaped(320, 180));
        assertTrue(AppAdapter.isBannerShaped(1920, 1080));
    }

    @Test
    public void squareAndTallPicturesAreNotBanners() {
        assertFalse(AppAdapter.isBannerShaped(64, 64));
        assertFalse(AppAdapter.isBannerShaped(256, 256));
        assertFalse(AppAdapter.isBannerShaped(100, 160));
    }

    @Test
    public void drawablesWithoutAnIntrinsicSizeStillFillTheTile() {
        assertTrue(AppAdapter.isBannerShaped(-1, -1));
        assertTrue(AppAdapter.isBannerShaped(0, 0));
    }

    @Test
    public void sharpBannersCoverTheTileLikeCenterCrop() {
        assertArrayEquals(new int[] {118, 66}, AppAdapter.coverSize(320, 180, 118, 66));   // 16:9 into 16:9
        assertArrayEquals(new int[] {132, 66}, AppAdapter.coverSize(320, 160, 118, 66));   // wider: crop the sides
        assertArrayEquals(new int[] {118, 74}, AppAdapter.coverSize(320, 200, 118, 66));   // taller: crop top and bottom
    }
}
