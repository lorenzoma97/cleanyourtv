package it.lorenzoma97.casa;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plain HTTPS GET with timeouts and a size cap. Blocking: call off the main thread. */
final class Net {
    private static final String UA = "Casa/1.0 (TV home, personal)";
    static final int MAX_BYTES = 6 * 1024 * 1024;

    private Net() { }

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " " + url.replaceAll("api_key=[^&]+", "api_key=..."));
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > MAX_BYTES) throw new IOException("too large: " + url);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Downloads to a temp file and renames, so a half-written image is never served. */
    static void download(String url, File dest) throws IOException {
        byte[] data = get(url);
        File tmp = new File(dest.getPath() + ".part");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(data);
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("rename failed: " + dest);
        }
    }
}
