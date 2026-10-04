package com.example.myapplication.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.myapplication.R;

/**
 * 无封面书籍的占位封面：水墨底 + 书名题在上方留白处。
 *
 * <p>底图是 3:4，全站封面位也都是 3:4，所以 ImageView 的 Matrix / centerCrop 只会整体等比
 * 缩放而不会裁掉画面。题名按 bounds 宽度比例排版，随封面一起缩放，小图上图形化后仍可辨。
 * 文字走 Canvas 直绘，不额外分配位图 —— 列表滚动时每次 bind 只重建一个 StaticLayout。
 */
public class InkBookCover extends Drawable {

    /** 墨色：比纯墨浅一档，避免在宣纸底上过硬。 */
    private static final int INK_COLOR = 0xFF2A2724;
    /** 字号 = 封面宽 × 0.14，对应 72dp 封面约 10sp。 */
    private static final float FONT_RATIO = 0.14f;
    private static final float SIDE_RATIO = 0.08f;
    private static final float TOP_RATIO = 0.07f;
    private static final float LINE_SPACING_RATIO = 0.32f;
    private static final int MAX_LINES = 3;

    private final Drawable art;
    private final String title;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private StaticLayout layout;
    private int layoutWidth;

    private InkBookCover(@NonNull Context context, @Nullable String text) {
        art = context.getDrawable(R.drawable.default_book_cover);
        title = text == null ? "" : text.trim();
        paint.setColor(INK_COLOR);
        paint.setTypeface(Typeface.SERIF);
        paint.setFakeBoldText(true);
        paint.setLetterSpacing(0.04f);
    }

    /** 每次返回独立实例：Glide 的 placeholder 和多个 ImageView 不能共用同一个 Drawable。 */
    @NonNull
    public static Drawable of(@NonNull Context context, @Nullable String bookTitle) {
        return new InkBookCover(context, bookTitle);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        art.setBounds(0, 0, bounds.width(), bounds.height());
        art.draw(canvas);

        ensureLayout(bounds.width());
        if (layout == null) {
            return;
        }
        int side = Math.round(bounds.width() * SIDE_RATIO);
        int top = Math.round(bounds.height() * TOP_RATIO);
        int saved = canvas.save();
        canvas.translate(side, top);
        layout.draw(canvas);
        canvas.restoreToCount(saved);
    }

    private void ensureLayout(int width) {
        if (title.isEmpty() || width <= 0 || layout != null && width == layoutWidth) {
            return;
        }
        int fontSize = Math.max(1, Math.round(width * FONT_RATIO));
        int side = Math.round(width * SIDE_RATIO);
        int avail = Math.max(1, width - 2 * side);
        paint.setTextSize(fontSize);
        // 先按「三行总宽」整体省略，再交给 StaticLayout 折行，长书名尾部出省略号而不是整行消失
        CharSequence fitted = TextUtils.ellipsize(title, paint, avail * MAX_LINES,
                TextUtils.TruncateAt.END);
        layout = buildLayout(fitted, avail, fontSize);
        layoutWidth = width;
    }

    private StaticLayout buildLayout(CharSequence text, int width, int fontSize) {
        float add = fontSize * LINE_SPACING_RATIO;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(add, 1f)
                    .setIncludePad(false)
                    .build();
        }
        return new StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1f, add, false);
    }

    @Override
    public void setAlpha(int alpha) {
        art.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        art.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    @Override
    public int getIntrinsicWidth() {
        return art.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return art.getIntrinsicHeight();
    }
}
