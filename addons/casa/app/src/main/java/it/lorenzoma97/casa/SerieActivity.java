package it.lorenzoma97.casa;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Scopri una nuova serie", the lower section of Home: the launcher opens it when focus goes down from the last row
 * of apps, and Up from the genres slides back. Five series drawn from the best of the chosen genre on our services,
 * shown as cards; the focused one fills the screen with its logo, badges and plot. OK plays it (Netflix opens the
 * title itself), holding OK offers the trailer, "Già vista" (never proposed again) or "Non oggi" (not again today),
 * "Pesca altre cinque" draws five new, "Tutte le serie" lists the whole genre in a grid (Back returns to the five).
 */
public class SerieActivity extends Activity {
    private static final int PICKS = 5;
    /** The grid of "Tutte le serie": as many cards a row as the five and their two buttons. */
    private static final int GRID_COLUMNS = 6;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<TextView> pills = new ArrayList<>();
    private final List<View> cards = new ArrayList<>();
    private final Map<Long, JSONObject> details = new HashMap<>();
    /** Names of the cards on screen, for the Prime search and the menu before a card's details are in memory. */
    private final Map<Long, String> names = new HashMap<>();
    private List<Long> picks = new ArrayList<>();
    private JSONObject content, pool;
    private String genre;              // null = Tutte
    private String genreLabel;
    private long shown = -1;           // the series in the head and behind it
    private int token;
    private boolean landing;           // the next head update plays the "thrown die" entry

    /** "Tutte le serie": the grid is open, its titles, and a token that drops the pictures of a closed grid. */
    private boolean gridOpen;
    private List<Long> gridIds = new ArrayList<>();
    private int gridToken;
    private long gridFocus = -1, shownBeforeGrid = -1;
    /** The head follows the grid only where focus rests: a run along the rows does not decode every backdrop. */
    private final Runnable gridHead = () -> {
        if (gridOpen && gridFocus > 0) showHead(gridFocus);
    };

    private ImageView backdrop, logo;
    private TextView title, overview, hint;
    private LinearLayout chips;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_serie);
        backdrop = findViewById(R.id.backdrop);
        logo = findViewById(R.id.logo);
        title = findViewById(R.id.title);
        overview = findViewById(R.id.overview);
        hint = findViewById(R.id.hint);
        chips = findViewById(R.id.chips);
        title.setText(R.string.loading);
        buildCards();
        RefreshJob.schedule(this);
        io.execute(() -> {
            JSONObject c = Shelf.content(this, false);
            main.post(() -> onContent(c));
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        main.removeCallbacks(gridHead);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.hold, R.anim.slide_down_out);
    }

    /** Up from the genres goes back to the apps, like scrolling back to the top of Home; Back closes the grid first. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (gridOpen && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) closeAll();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) {
            View f = getCurrentFocus();
            if (f != null && pills.contains(f)) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
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
        for (Pool.Genre g : Pool.genres(pool)) addPill(row, g.label, g.code);
        draw(null);
        // Home at rest opens Scopri on the series chosen there (tonight's five, the same as here).
        int at = picks.indexOf(getIntent().getLongExtra("tmdb", -1L));
        cards.get(Math.max(0, at)).requestFocus();
    }

    private void addPill(LinearLayout row, String label, String code) {
        TextView p = new TextView(this, null, 0, R.style.CasaPill);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        p.setLayoutParams(lp);
        p.setText(label);
        p.setSelected(code == null);
        p.setNextFocusDownId(View.NO_ID);
        p.setOnClickListener(v -> {
            genre = code;
            genreLabel = code == null ? null : label;
            for (TextView x : pills) x.setSelected(x == p);
            draw(null);
            cards.get(0).requestFocus();
        });
        pills.add(p);
        row.addView(p);
    }

    /** The five picks, then "Pesca altre cinque" and "Tutte le serie", the two narrower. */
    private void buildCards() {
        LinearLayout row = findViewById(R.id.cards);
        for (int i = 0; i <= PICKS + 1; i++) {
            View card = LayoutInflater.from(this).inflate(R.layout.card, row, false);
            final int slot = i;
            if (i >= PICKS) {
                boolean all = i == PICKS + 1;
                card.findViewById(R.id.draw).setVisibility(View.VISIBLE);
                card.findViewById(R.id.svc).setVisibility(View.GONE);
                ((LinearLayout.LayoutParams) card.getLayoutParams()).weight = 0.75f;
                if (all) {
                    ImageView icon = card.findViewById(R.id.drawIcon);
                    icon.setImageResource(R.drawable.ic_grid);
                    icon.setImageTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
                    TextView label = card.findViewById(R.id.drawText);
                    label.setText(R.string.all_series);
                    label.setTextColor(0xFFFFFFFF);
                }
                card.setBackgroundResource(all ? R.drawable.card_all : R.drawable.card_draw);
                card.setOnClickListener(v -> {
                    if (all) openAll(0);
                    else draw(null);
                });
            } else {
                card.setOnClickListener(v -> {
                    if (slot < picks.size()) watch(picks.get(slot));
                });
                card.setOnLongClickListener(v -> {
                    if (slot < picks.size()) menu(picks.get(slot), slot);
                    return true;
                });
            }
            card.setOnFocusChangeListener((v, focused) -> {
                grow(v, focused);
                if (focused && slot < picks.size()) showHead(picks.get(slot));
            });
            cards.add(card);
            row.addView(card);
        }
    }

    private void grow(View v, boolean focused) {
        v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(160).start();
        v.setElevation(focused ? dp(8) : 0);
    }

    /** Five new picks for the genre (slot: replace only that one, after "Già vista" or "Non oggi"). */
    private void draw(Integer slot) {
        if (pool == null) return;
        HashSet<Long> avoid = new HashSet<>(picks);
        if (slot == null) {
            if (genre == null && picks.isEmpty()) {
                // First open: tonight's five, the same Home's bar shows.
                picks = Pool.tonight(pool, Store.tonight(this), Store.hidden(this), PICKS, random);
            } else {
                picks = Pool.pick(pool, genre, Store.hidden(this), avoid, PICKS, random);
            }
            if (genre == null) Store.saveTonight(this, picks);
            landing = true;
        } else {
            List<Long> one = Pool.pick(pool, genre, Store.hidden(this), avoid, 1, random);
            if (one.isEmpty()) picks.remove((int) slot);
            else picks.set(slot, one.get(0));
            if (genre == null) Store.saveTonight(this, picks);
        }
        int t = ++token;
        for (int i = 0; i < PICKS; i++) {
            View card = cards.get(i);
            card.setVisibility(i < picks.size() ? View.VISIBLE : View.INVISIBLE);
            card.setFocusable(i < picks.size());
            if (i >= picks.size()) continue;
            if (slot != null && i != slot) continue;
            fillCard(card, picks.get(i), t, false);
        }
        if (picks.isEmpty()) {
            shown = -1;
            title.setText(R.string.all_seen);
            logo.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            chips.removeAllViews();
            overview.setText("");
            backdrop.animate().alpha(0f).setDuration(300).start();
            return;
        }
        View focus = getCurrentFocus();
        int focused = cards.indexOf(focus);
        showHead(picks.get(focused >= 0 && focused < picks.size() ? focused : 0));
    }

    /**
     * The card's picture and name, loaded off the main thread. grid: a small picture (w300, the grid can hold a
     * hundred) and details not kept in memory; the five keep theirs for the head.
     */
    private void fillCard(View card, long id, int t, boolean grid) {
        ((TextView) card.findViewById(R.id.name)).setText(names.containsKey(id) ? names.get(id) : "");
        ((ImageView) card.findViewById(R.id.img)).setImageDrawable(null);
        JSONObject item = Pool.item(pool, id);
        ((TextView) card.findViewById(R.id.svc)).setText(serviceName(Pool.service(item)));
        int width = dp(grid ? 140 : 260);
        io.execute(() -> {
            JSONObject d = grid ? Tmdb.details(this, id) : details(id);
            Bitmap thumb = null;
            String path = d == null ? null : Tmdb.backdrop(d);
            if (path != null) {
                try {
                    thumb = decode(Tmdb.image(this, "pool", path, grid ? "w300" : "w780"), width);
                } catch (Exception ignored) {
                }
            }
            Bitmap img = thumb;
            String name = d == null ? null : d.optString("name");
            main.post(() -> {
                if (isDestroyed() || t != (grid ? gridToken : token)) return;
                if (name != null) names.put(id, name);
                ((TextView) card.findViewById(R.id.name)).setText(name == null ? "" : name);
                ((ImageView) card.findViewById(R.id.img)).setImageBitmap(img);
            });
        });
    }

    // ---- Tutte le serie ---------------------------------------------------------------------------

    /**
     * The whole pool of the chosen genre (already watched left out), best first, six a row in a scrolling grid in
     * place of the genres and the five. Pictures load a few rows around the focus. Back returns to the five.
     */
    private void openAll(int focusIndex) {
        if (pool == null) return;
        gridIds = Pool.candidates(pool, genre, Store.seen(this), Collections.emptySet());
        if (gridIds.isEmpty()) {
            Toast.makeText(this, R.string.all_seen, Toast.LENGTH_SHORT).show();
            closeAll();   // the last one was just marked as watched
            return;
        }
        if (!gridOpen) shownBeforeGrid = shown;
        gridOpen = true;
        int t = ++gridToken;
        LinearLayout rows = findViewById(R.id.allRows);
        rows.removeAllViews();
        View first = null;
        for (int r = 0; r * GRID_COLUMNS < gridIds.size(); r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(14);
            for (int c = 0; c < GRID_COLUMNS; c++) {
                int index = r * GRID_COLUMNS + c;
                View card = LayoutInflater.from(this).inflate(R.layout.card, row, false);
                row.addView(card);
                if (index >= gridIds.size()) {
                    card.setVisibility(View.INVISIBLE);   // keeps the last row's cards as wide as the others
                    card.setFocusable(false);
                    continue;
                }
                long id = gridIds.get(index);
                int rowIndex = r;
                card.setOnClickListener(v -> watch(id));
                card.setOnLongClickListener(v -> {
                    menu(id, -1);
                    return true;
                });
                card.setOnFocusChangeListener((v, focused) -> {
                    grow(v, focused);
                    if (!focused) return;
                    fillRows(rowIndex, t);
                    gridFocus = id;
                    main.removeCallbacks(gridHead);
                    main.postDelayed(gridHead, 180);
                });
                if (index == Math.min(focusIndex, gridIds.size() - 1)) first = card;
            }
            rows.addView(row, rlp);
        }
        TextView heading = findViewById(R.id.allTitle);
        heading.setText(getString(R.string.all_title, genreLabel == null ? getString(R.string.all_genres_long) : genreLabel,
                gridIds.size()));
        // Room for two rows under the head: the hint goes and the plot keeps two lines.
        hint.setVisibility(View.GONE);
        overview.setMaxLines(2);
        title.setMaxLines(1);
        findViewById(R.id.all).setVisibility(View.VISIBLE);
        fillRows(0, t);
        if (first != null) first.requestFocus();   // before the five go, so focus never falls on a stray view
        findViewById(R.id.genresScroll).setVisibility(View.GONE);
        findViewById(R.id.cards).setVisibility(View.GONE);
    }

    /** Fills the cards from the row above the focus to two rows below it, once each. */
    private void fillRows(int around, int t) {
        LinearLayout rows = findViewById(R.id.allRows);
        for (int r = Math.max(0, around - 1); r <= around + 2 && r < rows.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) rows.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                int index = r * GRID_COLUMNS + c;
                View card = row.getChildAt(c);
                if (index >= gridIds.size() || card.getTag() != null) continue;
                card.setTag(Boolean.TRUE);
                fillCard(card, gridIds.get(index), t, true);
            }
        }
    }

    private void closeAll() {
        if (!gridOpen) return;
        gridOpen = false;
        gridToken++;
        main.removeCallbacks(gridHead);
        findViewById(R.id.genresScroll).setVisibility(View.VISIBLE);
        findViewById(R.id.cards).setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        overview.setMaxLines(3);
        title.setMaxLines(2);
        cards.get(PICKS + 1).requestFocus();
        findViewById(R.id.all).setVisibility(View.GONE);
        ((LinearLayout) findViewById(R.id.allRows)).removeAllViews();   // the pictures go with the cards
        if (shownBeforeGrid > 0) showHead(shownBeforeGrid);
    }

    // ---- head ------------------------------------------------------------------------------------

    /** The focused series behind everything, with its logo (or name), badges and plot. */
    private void showHead(long id) {
        if (id == shown && !landing) return;
        shown = id;
        boolean spin = landing;
        landing = false;
        int w = getResources().getDisplayMetrics().widthPixels;
        int logoW = dp(440);
        io.execute(() -> {
            JSONObject d = details(id);
            Bitmap big = null, lg = null;
            if (d != null) {
                try {
                    String path = Tmdb.backdrop(d);
                    if (path != null) big = decode(Tmdb.image(this, "pool", path, "w1280"), w);
                    String lp = Tmdb.logo(d);
                    if (lp != null) lg = Tmdb.lightLogo(decode(Tmdb.image(this, "pool", lp, "w500"), logoW));
                } catch (Exception ignored) {
                }
            }
            Store.trim(Store.dir(this, "pool"), 40L * 1024 * 1024);
            Bitmap picture = big, logoBmp = lg;
            main.post(() -> {
                if (id != shown || isDestroyed()) return;
                head(d, Pool.item(pool, id), picture, logoBmp, spin);
            });
        });
    }

    private void head(JSONObject d, JSONObject item, Bitmap picture, Bitmap logoBmp, boolean spin) {
        if (logoBmp != null) {
            logo.setImageBitmap(logoBmp);
            logo.setVisibility(View.VISIBLE);
            title.setVisibility(View.GONE);
        } else {
            logo.setVisibility(View.GONE);
            title.setVisibility(View.VISIBLE);
            title.setText(d == null ? "" : d.optString("name"));
        }
        fillChips(d, item);
        overview.setText(d == null ? "" : d.optString("overview"));
        View hero = logoBmp != null ? logo : title;
        if (spin) {
            // The draw "lands" like a thrown die: a short spin and grow, then still.
            hero.setPivotX(0f);
            hero.setPivotY(hero.getHeight() / 2f);
            hero.setRotation(-14f);
            hero.setScaleX(0.6f);
            hero.setScaleY(0.6f);
            hero.setAlpha(0.2f);
            hero.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(600)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        backdrop.animate().cancel();
        backdrop.animate().alpha(0f).setDuration(backdrop.getAlpha() > 0 ? 220 : 0).withEndAction(() -> {
            backdrop.setImageBitmap(picture);
            backdrop.setScaleX(1f);
            backdrop.setScaleY(1f);
            backdrop.animate().alpha(picture == null ? 0f : 0.92f).setDuration(600).withEndAction(() ->
                    backdrop.animate().scaleX(1.06f).scaleY(1.06f).setDuration(20000).setInterpolator(new LinearInterpolator()).start()
            ).start();
        }).start();
    }

    /** Year, seasons, two genres, the rating (amber) and the services (outlined), as small boxes. */
    private void fillChips(JSONObject d, JSONObject item) {
        chips.removeAllViews();
        if (d != null) {
            String year = d.optString("first_air_date", "");
            if (year.length() >= 4) addChip(year.substring(0, 4), R.drawable.chip, 0xFFFFFFFF);
            int seasons = d.optInt("number_of_seasons", 0);
            if (seasons > 0) addChip(seasons == 1 ? "1 stagione" : seasons + " stagioni", R.drawable.chip, 0xFFFFFFFF);
        }
        JSONArray g = item == null ? null : item.optJSONArray("g");
        JSONObject labels = pool.optJSONObject("genres");
        for (int i = 0; g != null && i < g.length() && i < 2; i++) {
            JSONObject x = labels == null ? null : labels.optJSONObject(g.optString(i));
            addChip(x == null ? g.optString(i) : x.optString("label"), R.drawable.chip, 0xFFFFFFFF);
        }
        if (d != null && d.optDouble("vote_average", 0) > 0) {
            addChip(String.format(java.util.Locale.ITALY, "★ %.1f", d.optDouble("vote_average")), R.drawable.chip_amber, 0xFF160F02);
        }
        JSONArray s = item == null ? null : item.optJSONArray("s");
        for (int i = 0; s != null && i < s.length(); i++) addChip(serviceName(s.optString(i)), R.drawable.chip_outline, 0xFFFFFFFF);
    }

    private void addChip(String text, int background, int color) {
        TextView c = new TextView(this, null, 0, R.style.CasaChip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        c.setLayoutParams(lp);
        c.setBackgroundResource(background);
        c.setTextColor(color);
        c.setText(text);
        chips.addView(c);
    }

    // ---- actions ---------------------------------------------------------------------------------

    /** Holding OK: the trailer, "Già vista" and, on one of the five, "Non oggi" (slot -1: a card of the grid). */
    private void menu(long id, int slot) {
        boolean grid = slot < 0;
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.menu_trailer));
        items.add(getString(R.string.menu_seen));
        if (!grid) items.add(getString(R.string.menu_not_today));
        items.add(getString(R.string.menu_cancel));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name(id))
                .setItems(items.toArray(new String[0]), (dlg, which) -> {
                    if (which == 0) {
                        trailer(id);
                    } else if (which == 1 && grid) {
                        Store.markSeen(this, id);
                        openAll(Math.max(0, gridIds.indexOf(id)));
                    } else if (which == 1 || (which == 2 && !grid)) {
                        if (which == 1) Store.markSeen(this, id);
                        else Store.skipToday(this, id);
                        draw(slot);
                        cards.get(Math.min(slot, Math.max(0, picks.size() - 1))).requestFocus();
                    }
                })
                .show();
    }

    private void watch(long id) {
        JSONObject item = Pool.item(pool, id);
        String svc = Pool.service(item);
        JSONObject services = content == null ? null : content.optJSONObject("services");
        JSONObject s = services == null || svc == null ? null : services.optJSONObject(svc);
        if (s == null) return;
        String pkg = s.optString("package");
        String nf = item.isNull("nf") ? null : item.optString("nf", null);
        if ("netflix".equals(svc) && nf != null) {
            try {
                // A clean task: a Netflix already running ignores the link and resumes the last series.
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/" + nf))
                        .setPackage(pkg).putExtra("source", "30")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                return;
            } catch (ActivityNotFoundException ignored) {
                // Netflix without the title link: open the app instead
            }
        }
        String name = name(id);
        String search = "prime".equals(svc) && !name.isEmpty() ? Shelf.primeSearchIntent(name) : null;
        if (search != null) {
            try {
                startActivity(Intent.parseUri(search, Intent.URI_INTENT_SCHEME));
                return;
            } catch (ActivityNotFoundException | java.net.URISyntaxException ignored) {
                // Prime without its search link: open the app instead
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

    /** Off the main thread: a card of the grid may not have its details in memory yet. */
    private void trailer(long id) {
        io.execute(() -> {
            JSONObject d = details(id);
            String key = d == null ? null : Tmdb.trailer(d);
            main.post(() -> {
                if (isDestroyed()) return;
                if (key == null) {
                    Toast.makeText(this, R.string.no_trailer, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + key))
                            .setPackage("com.google.android.youtube.tv").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.no_trailer, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /** The series' Italian name from its details or its card; "" before either has loaded. */
    private String name(long id) {
        JSONObject d;
        synchronized (details) {
            d = details.get(id);
        }
        if (d != null) return d.optString("name");
        String n = names.get(id);
        return n == null ? "" : n;
    }

    /** Details from memory, else from Tmdb's week-long cache or the network (off the main thread). */
    private JSONObject details(long id) {
        synchronized (details) {
            if (details.containsKey(id)) return details.get(id);
        }
        JSONObject d = Tmdb.details(this, id);
        synchronized (details) {
            details.put(id, d);
        }
        return d;
    }

    private String serviceName(String svc) {
        JSONObject services = content == null ? null : content.optJSONObject("services");
        JSONObject s = services == null || svc == null ? null : services.optJSONObject(svc);
        return s == null ? (svc == null ? "" : svc) : s.optString("name");
    }

    /** Full colour (no banding on the dark gradients), sampled down to about maxWidth. */
    private Bitmap decode(File f, int maxWidth) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (b.outWidth / (o.inSampleSize * 2) >= maxWidth) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
