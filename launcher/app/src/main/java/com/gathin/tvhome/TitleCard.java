package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Fork lorenzoma97: a series card, the picture with its title over a dark fade at its foot (the shelf's detail, the
 * "Scopri" bar, Home at rest). Child 0 is the picture, so a thumbnail that loads later can be set on it.
 */
final class TitleCard {
    private TitleCard() { }

    static FrameLayout make(Context ctx, Bitmap picture, String title, float textSp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        FrameLayout card = new FrameLayout(ctx);
        card.setBackgroundResource(R.drawable.hero_card);
        int pad = Math.round(2 * density);
        card.setPadding(pad, pad, pad, pad);
        card.setClipToOutline(true);
        ImageView image = new ImageView(ctx);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(picture);
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView name = new TextView(ctx);
        name.setText(title);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        name.setTypeface(ctx.getResources().getFont(R.font.outfit_medium));
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLineSpacing(0f, 0.95f);
        int side = Math.round(textSp * 0.6f * density);
        name.setPadding(side, Math.round(textSp * density), side, Math.round(textSp * 0.4f * density));
        name.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xD9000000}));
        card.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        return card;
    }
}
