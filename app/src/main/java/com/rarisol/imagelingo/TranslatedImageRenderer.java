package com.rarisol.imagelingo;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.List;

/** Flattens translated text blocks into a single bitmap. */
public final class TranslatedImageRenderer {
    private TranslatedImageRenderer() {}

    public static Bitmap compose(Bitmap source, List<TranslationClient.TranslationBlock> blocks) {
        if (source == null) throw new IllegalArgumentException("source == null");
        Bitmap output = source.copy(Bitmap.Config.ARGB_8888, true);
        if (output == null) {
            output = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas copyCanvas = new Canvas(output);
            copyCanvas.drawBitmap(source, 0f, 0f, null);
        }
        if (blocks == null || blocks.isEmpty()) return output;

        Canvas canvas = new Canvas(output);
        float width = output.getWidth();
        float height = output.getHeight();
        float minDim = Math.max(1f, Math.min(width, height));

        Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bubblePaint.setColor(0xE8FFFFFF);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setShadowLayer(minDim * 0.012f, 0f, minDim * 0.0035f, 0x55000000);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(0x9A2E333D);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1.5f, minDim * 0.0024f));

        TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setColor(0xFF0F1217);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        RectF imageBounds = new RectF(0f, 0f, width, height);
        for (TranslationClient.TranslationBlock block : blocks) {
            if (block == null || block.text == null || block.text.trim().isEmpty()) continue;
            RectF sourceRect = new RectF(
                    block.x / 1000f * width,
                    block.y / 1000f * height,
                    (block.x + block.width) / 1000f * width,
                    (block.y + block.height) / 1000f * height);
            drawBlock(canvas, imageBounds, sourceRect, block.text.trim(), bubblePaint, borderPaint, textPaint);
        }
        return output;
    }

    private static void drawBlock(Canvas canvas, RectF imageBounds, RectF detectedRect, String text,
                                  Paint bubblePaint, Paint borderPaint, TextPaint textPaint) {
        float width = imageBounds.width();
        float height = imageBounds.height();
        float minDim = Math.max(1f, Math.min(width, height));
        float outerMargin = Math.max(4f, minDim * 0.006f);
        float horizontalPadding = Math.max(8f, minDim * 0.013f);
        float verticalPadding = Math.max(6f, minDim * 0.0105f);

        int characterCount = Math.max(1, text.codePointCount(0, text.length()));
        float naturalWidth = minDim * 0.12f + (float) Math.sqrt(characterCount) * minDim * 0.035f;
        float maxWidth = Math.max(minDim * 0.22f, width * 0.82f);
        float bubbleWidth = Math.max(detectedRect.width() + horizontalPadding * 2f,
                Math.min(maxWidth, naturalWidth));
        bubbleWidth = Math.min(bubbleWidth, width - outerMargin * 2f);
        bubbleWidth = Math.max(minDim * 0.12f, bubbleWidth);

        float bubbleHeight = Math.max(detectedRect.height() + verticalPadding * 2f, minDim * 0.06f);
        bubbleHeight = Math.min(bubbleHeight, height * 0.60f);

        RectF bubble = centeredRect(detectedRect.centerX(), detectedRect.centerY(), bubbleWidth, bubbleHeight);
        clampInside(bubble, imageBounds, outerMargin);

        int textWidth = Math.max(1, Math.round(bubble.width() - horizontalPadding * 2f));
        float minTextSize = Math.max(14f, minDim * 0.018f);
        float maxTextSize = Math.min(minDim * 0.085f, Math.max(minDim * 0.028f, detectedRect.height() * 0.50f));
        StaticLayout layout = fitLayout(text, textPaint, textWidth,
                Math.max(1, Math.round(bubble.height() - verticalPadding * 2f)), minTextSize, maxTextSize);

        if (layout.getHeight() > bubble.height() - verticalPadding * 2f) {
            textPaint.setTextSize(minTextSize);
            layout = makeLayout(text, textPaint, textWidth);
            float neededHeight = layout.getHeight() + verticalPadding * 2f;
            if (neededHeight > bubble.height()) {
                bubble.bottom = bubble.top + Math.min(neededHeight, height - outerMargin * 2f);
                clampInside(bubble, imageBounds, outerMargin);
            }
        }

        float radius = Math.min(minDim * 0.028f, Math.min(bubble.width(), bubble.height()) * 0.22f);
        canvas.drawRoundRect(bubble, radius, radius, bubblePaint);
        canvas.drawRoundRect(bubble, radius, radius, borderPaint);

        float textTop = bubble.top + Math.max(verticalPadding,
                (bubble.height() - layout.getHeight()) * 0.5f);
        canvas.save();
        canvas.clipRect(bubble);
        canvas.translate(bubble.left + horizontalPadding, textTop);
        layout.draw(canvas);
        canvas.restore();
    }

    private static StaticLayout fitLayout(String text, TextPaint textPaint, int width, int height,
                                          float minSize, float maxSize) {
        float low = minSize;
        float high = Math.max(minSize, maxSize);
        StaticLayout best;
        textPaint.setTextSize(low);
        best = makeLayout(text, textPaint, width);
        for (int i = 0; i < 10; i++) {
            float mid = (low + high) * 0.5f;
            textPaint.setTextSize(mid);
            StaticLayout candidate = makeLayout(text, textPaint, width);
            if (candidate.getHeight() <= height) {
                best = candidate;
                low = mid;
            } else {
                high = mid;
            }
        }
        textPaint.setTextSize(low);
        return best;
    }

    private static StaticLayout makeLayout(String text, TextPaint paint, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.03f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private static RectF centeredRect(float centerX, float centerY, float width, float height) {
        return new RectF(centerX - width * 0.5f, centerY - height * 0.5f,
                centerX + width * 0.5f, centerY + height * 0.5f);
    }

    private static void clampInside(RectF rect, RectF bounds, float margin) {
        float leftLimit = bounds.left + margin;
        float rightLimit = bounds.right - margin;
        float topLimit = bounds.top + margin;
        float bottomLimit = bounds.bottom - margin;

        if (rect.width() > rightLimit - leftLimit) {
            rect.left = leftLimit;
            rect.right = rightLimit;
        } else {
            if (rect.left < leftLimit) rect.offset(leftLimit - rect.left, 0f);
            if (rect.right > rightLimit) rect.offset(rightLimit - rect.right, 0f);
        }
        if (rect.height() > bottomLimit - topLimit) {
            rect.top = topLimit;
            rect.bottom = bottomLimit;
        } else {
            if (rect.top < topLimit) rect.offset(0f, topLimit - rect.top);
            if (rect.bottom > bottomLimit) rect.offset(0f, bottomLimit - rect.bottom);
        }
    }
}
