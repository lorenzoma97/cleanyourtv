package it.lorenzoma97.casa;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Where Casa keeps things: the TMDB key, the cached JSON, the images and the "already watched" list. */
final class Store {
    static final String CONTENT_URL = "https://lorenzoma97.github.io/tv-casa-data/content.json";
    private static final String PREFS = "casa";
    private static final String SEEN = "viste";

    private Store() { }

    /**
     * The TMDB key, pushed once with adb into the app's external files dir (never in the repo or the APK):
     * adb push tmdb.txt /sdcard/Android/data/it.lorenzoma97.casa/files/tmdb.txt
     */
    static String tmdbKey(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, "tmdb.txt");
        if (!f.isFile()) return null;
        String key = read(f);
        if (key == null) return null;
        key = key.replace("﻿", "").trim();
        return key.matches("[0-9a-f]{32}") ? key : null;
    }

    static File file(Context ctx, String name) {
        return new File(ctx.getFilesDir(), name);
    }

    static File dir(Context ctx, String name) {
        File d = new File(ctx.getFilesDir(), name);
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static void write(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed: " + f);
    }

    static boolean fresh(File f, long maxAgeMs) {
        return f.isFile() && System.currentTimeMillis() - f.lastModified() < maxAgeMs;
    }

    static Set<Long> seen(Context ctx) {
        Set<Long> out = new HashSet<>();
        for (String s : prefs(ctx).getStringSet(SEEN, Collections.emptySet())) {
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void markSeen(Context ctx, long tmdbId) {
        Set<String> s = new HashSet<>(prefs(ctx).getStringSet(SEEN, Collections.emptySet()));
        s.add(Long.toString(tmdbId));
        prefs(ctx).edit().putStringSet(SEEN, s).apply();
    }

    /** Tonight's five ("Tutte"): the same in Home's bar and in the section, for the day or until "Pesca altre". */
    static java.util.List<Long> tonight(Context ctx) {
        SharedPreferences p = prefs(ctx);
        java.util.List<Long> out = new java.util.ArrayList<>();
        if (!java.time.LocalDate.now().toString().equals(p.getString("tonight_day", ""))) return out;
        for (String s : p.getString("tonight", "").split(",")) {
            try {
                out.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    static void saveTonight(Context ctx, java.util.List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) sb.append(sb.length() == 0 ? "" : ",").append(id);
        prefs(ctx).edit().putString("tonight", sb.toString()).putString("tonight_day", java.time.LocalDate.now().toString()).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Keeps the newest files in dir up to maxBytes; images are re-downloaded when needed again. */
    static void trim(File dir, long maxBytes) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        long total = 0;
        for (File f : files) {
            total += f.length();
            if (total > maxBytes) f.delete();
        }
    }
}
