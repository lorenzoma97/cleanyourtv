package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fork lorenzoma97: Home at rest (home_idle_clock_minutes on the cinematic shelf), in place of the clock screensaver.
 * A still picture with the time, and under it tonight's five from "Scopri una nuova serie" as cards: Left/Right move
 * along them, OK opens that series in Scopri, any other key goes back to Home. Without Casa: the time alone.
 */
public class IdleActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout cards;
    /** Leaving for Scopri: its slide-up, not the fade back to Home. */
    private boolean toScopri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_idle);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        cards = findViewById(R.id.idleCards);
        ImageView art = findViewById(R.id.idleArt);
        float density = getResources().getDisplayMetrics().density;
        int w = Math.round(152 * density), h = Math.round(86 * density);
        int screen = Math.max(960, Math.min(1280, getResources().getDisplayMetrics().widthPixels));
        io.execute(() -> {
            List<ContentShelf.Pick> picks = ContentShelf.picks(getApplicationContext(), w);
            Bitmap still = picks == null || picks.isEmpty() || picks.get(0).backdrop == null ? null
                    : ContentShelf.decode(getApplicationContext(), picks.get(0).backdrop, screen);
            main.post(() -> {
                if (isDestroyed()) return;
                if (still != null) {
                    art.setImageBitmap(still);
                    art.animate().alpha(0.8f).setDuration(900).start();
                }
                if (picks == null || picks.isEmpty()) return;
                for (ContentShelf.Pick pick : picks) {
                    FrameLayout card = TitleCard.make(this, pick.image, pick.title, 12f);
                    card.setFocusable(true);
                    card.setAlpha(0.75f);
                    card.setOnFocusChangeListener((v, focused) -> v.animate().scaleX(focused ? 1.08f : 1f)
                            .scaleY(focused ? 1.08f : 1f).alpha(focused ? 1f : 0.75f).setDuration(160).start());
                    card.setOnClickListener(v -> openScopri(pick.tmdb));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
                    lp.setMarginEnd(Math.round(14 * density));
                    cards.addView(card, lp);
                }
                findViewById(R.id.idleLabel).setVisibility(View.VISIBLE);
                cards.getChildAt(0).requestFocus();
            });
        });
    }

    private void openScopri(long tmdb) {
        toScopri = true;
        try {
            startActivity(new Intent().setClassName("it.lorenzoma97.casa", "it.lorenzoma97.casa.SerieActivity")
                    .putExtra("tmdb", tmdb).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            overridePendingTransition(R.anim.slide_up_in, R.anim.hold_still);
        } catch (ActivityNotFoundException | SecurityException e) {
            toScopri = false;   // no Casa: back to Home
        }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** Left/Right and OK act on the cards; volume keys work as usual; anything else wakes Home, and only that. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean onCards = cards.getChildCount() > 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (onCards) return super.dispatchKeyEvent(event);
                break;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return super.dispatchKeyEvent(event);
            default:
                break;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        if (!toScopri) overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
