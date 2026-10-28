package it.lorenzoma97.casa;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Updates for the launcher and Casa from the fork's rolling release (apk-latest), installed only when the owner says
 * yes on Home. Casa downloads what differs from what is installed and checks it against the release's SHA256SUMS;
 * the launcher asks (HeroProvider "update"); UpdateActivity hands the files to the system installer, which asks once
 * more and refuses anything not signed with the key already installed.
 */
final class Updater {
    private static final String TAG = "Casa";
    static final String RELEASE = "https://github.com/lorenzoma97/cleanyourtv/releases/download/apk-latest/";
    /** Release file, package, name on screen; in install order: Casa updating itself ends its own process, so last. */
    static final String[][] APPS = {
            {"tvhome-release.apk", "com.gathin.tvhome", "Home"},
            {"casa-release.apk", "it.lorenzoma97.casa", "Casa"},
    };
    private static final long STALE_MS = 2L * 3600 * 1000;
    private static final String PREFS = "update", CHECKED = "checked";

    private Updater() { }

    /** SHA256SUMS ("<sha256>  <file>" per line, sha256sum's format) as file -> lower-case hash. */
    static Map<String, String> sums(String text) {
        Map<String, String> out = new HashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+\\*?", 2);
            if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) out.put(parts[1].trim(), parts[0].toLowerCase());
        }
        return out;
    }

    /** At most every 2 hours (Home asks each time it comes back); blocking, off the main thread. */
    static void checkIfStale(Context ctx) {
        if (System.currentTimeMillis() - prefs(ctx).getLong(CHECKED, 0) < STALE_MS) return;
        try {
            check(ctx);
        } catch (IOException e) {
            Log.w(TAG, "update check: " + e);
        }
    }

    /** Downloads each release file that differs from the installed app into files/update, verified; drops the rest. */
    static void check(Context ctx) throws IOException {
        Map<String, String> sums = sums(new String(Net.get(RELEASE + "SHA256SUMS"), StandardCharsets.UTF_8));
        File dir = Store.dir(ctx, "update");
        SharedPreferences.Editor edit = prefs(ctx).edit();
        for (String[] app : APPS) {
            String want = sums.get(app[0]);
            File f = new File(dir, app[0]);
            if (want == null || want.equals(installed(ctx, app[1]))) {
                f.delete();
                edit.remove(app[0]);
                continue;
            }
            if (!(f.isFile() && want.equals(sha256(f)))) {
                Net.download(RELEASE + app[0], f);
                if (!want.equals(sha256(f))) {
                    f.delete();
                    throw new IOException(app[0] + ": checksum mismatch");
                }
            }
            edit.putString(app[0], want);
        }
        edit.putLong(CHECKED, System.currentTimeMillis()).apply();
    }

    /** The downloaded files that still differ from what is installed, in install order. */
    static List<File> ready(Context ctx) {
        List<File> out = new ArrayList<>();
        SharedPreferences p = prefs(ctx);
        for (String[] app : APPS) {
            String want = p.getString(app[0], null);
            File f = new File(Store.dir(ctx, "update"), app[0]);
            if (want != null && f.isFile() && !want.equals(installed(ctx, app[1]))) out.add(f);
        }
        return out;
    }

    /** "Home e Casa", "Home" or "Casa" for these files. */
    static String names(List<File> files) {
        List<String> names = new ArrayList<>();
        for (File f : files) for (String[] app : APPS) if (app[0].equals(f.getName())) names.add(app[2]);
        return String.join(" e ", names);
    }

    static String packageOf(File f) {
        for (String[] app : APPS) if (app[0].equals(f.getName())) return app[1];
        return null;
    }

    /** SHA-256 of the installed APK, remembered until the app is updated again; null when it is not installed. */
    private static String installed(Context ctx, String pkg) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(pkg, 0);
            String key = "installed:" + pkg, stamp = info.lastUpdateTime + ":";
            String kept = prefs(ctx).getString(key, "");
            if (kept.startsWith(stamp)) return kept.substring(stamp.length());
            String sha = sha256(new File(info.applicationInfo.sourceDir));
            prefs(ctx).edit().putString(key, stamp + sha).apply();
            return sha;
        } catch (PackageManager.NameNotFoundException | IOException e) {
            return null;
        }
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
