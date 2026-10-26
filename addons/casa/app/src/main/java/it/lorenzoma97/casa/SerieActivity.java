package it.lorenzoma97.casa;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Serie a caso": pick a genre, get one series drawn from the best of that genre on our services.
 * Guarda opens it (the Netflix title directly, the other services' app), Gia vista removes it for good,
 * Un'altra skips it for now. Holding Guarda opens the trailer in YouTube.
 */
public class SerieActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final Set<Long> skipped = new HashSet<>();
    private final List<TextView> pills = new ArrayList<>();
    private JSONObject content, pool;
    private String genre;          // null = Tutte
    private long current = -1;
    private JSONObject currentDetails;
    private int drawToken;

    private ImageView backdrop;
    private TextView title, meta, overview, hint;
    private View buttons;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_serie);
        backdrop = findViewById(R.id.backdrop);
        title = findViewById(R.id.title);
        meta = findViewById(R.id.meta);
        overview = findViewById(R.id.overview);
        hint = findViewById(R.id.hint);
        buttons = findViewById(R.id.buttons);
        title.setText(R.string.loading);
        findViewById(R.id.watch).setOnClickListener(v -> watch());
        findViewById(R.id.watch).setOnLongClickListener(v -> {
            trailer();
            return true;
        });
        findViewById(R.id.seen).setOnClickListener(v -> {
            if (current > 0) Store.markSeen(this, current);
            draw();
        });
        findViewById(R.id.another).setOnClickListener(v -> {
            if (current > 0) skipped.add(current);
            draw();
        });
        RefreshJob.schedule(this);
        io.execute(() -> {
            JSONObject c = Shelf.content(this, false);
            main.post(() -> onContent(c));
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void onContent(JSONObject c) {
        if (isDestroyed()) return;
        content = c;
        pool = c == null ? null : c.optJSONObject("pool");
        if (pool == null) {
            title.setText(R.string.no_data);
            return;
        }
        LinearLayout row = findViewById(R.id.genres);
        addPill(row, getString(R.string.all_genres), null);
        for (Pool.Genre g : Pool.genres(pool)) addPill(row, g.label + "  " + g.count, g.code);
        pills.get(0).requestFocus();
        draw();
    }

    private void addPill(LinearLayout row, String label, String code) {
        TextView p = new TextView(this, null, 0, R.style.CasaPill);   // defStyleRes: background, padding, colours
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int) (10 * getResources().getDisplayMetrics().density));
        p.setLayoutParams(lp);
        p.setText(label);
        p.setSelected(code == null);
        p.setOnClickListener(v -> {
            genre = code;
            for (TextView x : pills) x.setSelected(x == p);
            draw();
            findViewById(R.id.watch).requestFocus();
        });
        pills.add(p);
        row.addView(p);
    }

    /** Draws the next series of the current genre and shows it once its details and picture are in. */
    private void draw() {
        if (pool == null) return;
        long id = Pool.next(pool, genre, Store.seen(this), skipped, random);
        int token = ++drawToken;
        if (id < 0) {
            current = -1;
            backdrop.animate().alpha(0f).setDuration(300).start();
            title.setText(R.string.all_seen);
            meta.setText("");
            overview.setText("");
            buttons.setVisibility(View.INVISIBLE);
            hint.setVisibility(View.INVISIBLE);
            return;
        }
        io.execute(() -> {
            JSONObject d = Tmdb.details(this, id);
            Bitmap bmp = null;
            String path = d == null ? null : Tmdb.backdrop(d);
            if (path != null) {
                try {
                    File f = Tmdb.image(this, "pool", path, "w1280");
                    Store.trim(Store.dir(this, "pool"), 20L * 1024 * 1024);
                    bmp = decode(f);
                } catch (Exception ignored) {
                }
            }
            Bitmap picture = bmp;
            main.post(() -> {
                if (token != drawToken || isDestroyed()) return;
                show(id, d, picture);
            });
        });
    }

    private void show(long id, JSONObject d, Bitmap picture) {
        current = id;
        currentDetails = d;
        JSONObject item = Pool.item(pool, id);
        title.setText(d == null ? "#" + id : d.optString("name"));
        meta.setText(metaLine(d, item));
        overview.setText(d == null ? "" : d.optString("overview"));
        buttons.setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        backdrop.animate().cancel();
        backdrop.setImageBitmap(picture);
        backdrop.setScaleX(1f);
        backdrop.setScaleY(1f);
        backdrop.setAlpha(0f);
        // Slow push-in over the picture instead of a video: the "cinema" feel without the RAM.
        backdrop.animate().alpha(picture == null ? 0f : 0.9f).setDuration(600).withEndAction(() ->
                backdrop.animate().scaleX(1.07f).scaleY(1.07f).setDuration(20000).setInterpolator(new LinearInterpolator()).start()
        ).start();
    }

    /** "2021 · 2 stagioni · Netflix · Thriller, Crime · TMDB 8,4" */
    private String metaLine(JSONObject d, JSONObject item) {
        List<String> parts = new ArrayList<>();
        if (d != null) {
            String year = d.optString("first_air_date", "");
            if (year.length() >= 4) parts.add(year.substring(0, 4));
            int seasons = d.optInt("number_of_seasons", 0);
            if (seasons > 0) parts.add(seasons == 1 ? "1 stagione" : seasons + " stagioni");
        }
        JSONObject services = content == null ? null : content.optJSONObject("services");
        JSONArray s = item == null ? null : item.optJSONArray("s");
        List<String> names = new ArrayList<>();
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject svc = services == null ? null : services.optJSONObject(s.optString(i));
            names.add(svc == null ? s.optString(i) : svc.optString("name"));
        }
        if (!names.isEmpty()) parts.add(String.join(" e ", names));
        JSONArray g = item == null ? null : item.optJSONArray("g");
        JSONObject labels = pool.optJSONObject("genres");
        List<String> gl = new ArrayList<>();
        for (int i = 0; g != null && i < g.length() && gl.size() < 2; i++) {
            JSONObject x = labels == null ? null : labels.optJSONObject(g.optString(i));
            gl.add(x == null ? g.optString(i) : x.optString("label"));
        }
        if (!gl.isEmpty()) parts.add(String.join(", ", gl));
        if (d != null && d.optDouble("vote_average", 0) > 0) {
            parts.add(String.format(java.util.Locale.ITALY, "TMDB %.1f", d.optDouble("vote_average")));
        }
        return String.join("  ·  ", parts);
    }

    private void watch() {
        JSONObject item = Pool.item(pool, current);
        String svc = Pool.service(item);
        JSONObject services = content == null ? null : content.optJSONObject("services");
        JSONObject s = services == null || svc == null ? null : services.optJSONObject(svc);
        if (s == null) return;
        String pkg = s.optString("package");
        String nf = item.isNull("nf") ? null : item.optString("nf", null);
        if ("netflix".equals(svc) && nf != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/" + nf))
                        .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // Netflix without the title link: open the app instead
            }
        }
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Toast.makeText(this, getString(R.string.no_app, s.optString("name")), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void trailer() {
        String key = currentDetails == null ? null : Tmdb.trailer(currentDetails);
        if (key == null) {
            Toast.makeText(this, R.string.no_trailer, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent yt = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(yt);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_trailer, Toast.LENGTH_SHORT).show();
        }
    }

    /** RGB_565 at about screen width: a 1280 px backdrop is ~1.8 MB in memory instead of ~3.7 MB. */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
