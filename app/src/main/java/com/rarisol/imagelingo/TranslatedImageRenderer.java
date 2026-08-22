package com.rarisol.imagelingo;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;
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
        boolean completed = false;
        try {
            if (blocks == null || blocks.isEmpty()) {
                completed = true;
                return output;
            }

            Canvas canvas = new Canvas(output);
            float width = output.getWidth();
            float height = output.getHeight();
            float minDim = Math.max(1f, Math.min(width, height));

            Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        // Dark glass keeps the artwork visible instead of replacing it with an opaque white card.
            bubblePaint.setColor(0x82111827);
            bubblePaint.setStyle(Paint.Style.FILL);
            bubblePaint.setShadowLayer(minDim * 0.010f, 0f, minDim * 0.003f, 0x52000000);

            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setColor(0x8AE6EEFF);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(Math.max(1.2f, minDim * 0.0022f));

            Paint connectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            connectorPaint.setColor(0xA8E6EEFF);
            connectorPaint.setStyle(Paint.Style.STROKE);
            connectorPaint.setStrokeCap(Paint.Cap.ROUND);
            connectorPaint.setStrokeWidth(Math.max(1.4f, minDim * 0.0026f));

            TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            textPaint.setColor(0xFFF8FAFF);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            textPaint.setShadowLayer(Math.max(1f, minDim * 0.002f), 0f,
                    Math.max(0.5f, minDim * 0.001f), 0xD0000000);

            RectF imageBounds = new RectF(0f, 0f, width, height);
            List<PreparedBlock> prepared = new ArrayList<>();
            for (TranslationClient.TranslationBlock block : blocks) {
                if (block == null || block.text == null || block.text.trim().isEmpty()) continue;
                RectF sourceRect = new RectF(
                        block.x / 1000f * width,
                        block.y / 1000f * height,
                        (block.x + block.width) / 1000f * width,
                        (block.y + block.height) / 1000f * height);
                PreparedBlock value = prepareBlock(imageBounds, sourceRect, block.text.trim(), textPaint);
                if (value != null) prepared.add(value);
            }

            List<RectF> bubbleRects = new ArrayList<>(prepared.size());
            List<PointF> anchors = new ArrayList<>(prepared.size());
            for (PreparedBlock block : prepared) {
                bubbleRects.add(block.bubble);
                anchors.add(block.anchor);
            }
            BubbleLayout.resolve(bubbleRects, anchors, imageBounds, Math.max(3f, minDim * 0.007f));

        // Bubbles that had to move retain an unobtrusive pointer to their source text.
            for (PreparedBlock block : prepared) {
                drawConnector(canvas, block, connectorPaint, minDim);
            }

        // Draw every background first. Together with collision placement, text is never covered by
        // a later bubble and semi-transparent fills do not stack into an opaque patch.
            for (PreparedBlock block : prepared) {
                canvas.drawRoundRect(block.bubble, block.radius, block.radius, bubblePaint);
                canvas.drawRoundRect(block.bubble, block.radius, block.radius, borderPaint);
            }
            for (PreparedBlock block : prepared) {
                textPaint.setTextSize(block.textSize);
                StaticLayout layout = makeLayout(block.text, textPaint, block.textWidth);
                canvas.save();
                canvas.clipRect(block.bubble);
                canvas.translate(block.bubble.left + block.horizontalPadding, block.textTop(layout));
                layout.draw(canvas);
                canvas.restore();
            }
            completed = true;
            return output;
        } finally {
            if (!completed && output != null && !output.isRecycled()) output.recycle();
        }
    }

    private static PreparedBlock prepareBlock(RectF imageBounds, RectF detectedRect, String text,
                                              TextPaint textPaint) {
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
        float maxTextSize = Math.min(minDim * 0.085f,
                Math.max(minDim * 0.028f, detectedRect.height() * 0.50f));
        float textSize = fitTextSize(text, textPaint, textWidth,
                Math.max(1, Math.round(bubble.height() - verticalPadding * 2f)), minTextSize, maxTextSize);
        textPaint.setTextSize(textSize);
        StaticLayout layout = makeLayout(text, textPaint, textWidth);

        if (layout.getHeight() > bubble.height() - verticalPadding * 2f) {
            textSize = minTextSize;
            textPaint.setTextSize(textSize);
            layout = makeLayout(text, textPaint, textWidth);
            float neededHeight = layout.getHeight() + verticalPadding * 2f;
            if (neededHeight > bubble.height()) {
                bubble.bottom = bubble.top + Math.min(neededHeight, height - outerMargin * 2f);
                clampInside(bubble, imageBounds, outerMargin);
            }
        }

        float radius = Math.min(minDim * 0.028f, Math.min(bubble.width(), bubble.height()) * 0.22f);
        return new PreparedBlock(text, bubble,
                new PointF(detectedRect.centerX(), detectedRect.centerY()), textWidth, textSize,
                horizontalPadding, verticalPadding, radius);
    }

    private static void drawConnector(Canvas canvas, PreparedBlock block, Paint paint, float minDim) {
        PointF anchor = block.anchor;
        RectF bubble = block.bubble;
        if (bubble.contains(anchor.x, anchor.y)) return;

        float endX = Math.max(bubble.left, Math.min(anchor.x, bubble.right));
        float endY = Math.max(bubble.top, Math.min(anchor.y, bubble.bottom));
        float dx = anchor.x - endX;
        float dy = anchor.y - endY;
        if (dx * dx + dy * dy < minDim * minDim * 0.00009f) return;
        canvas.drawLine(anchor.x, anchor.y, endX, endY, paint);
        Paint.Style oldStyle = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(anchor.x, anchor.y, Math.max(1.8f, minDim * 0.0032f), paint);
        paint.setStyle(oldStyle);
    }

    private static float fitTextSize(String text, TextPaint textPaint, int width, int height,
                                     float minSize, float maxSize) {
        float low = minSize;
        float high = Math.max(minSize, maxSize);
        for (int i = 0; i < 10; i++) {
            float mid = (low + high) * 0.5f;
            textPaint.setTextSize(mid);
            StaticLayout candidate = makeLayout(text, textPaint, width);
            if (candidate.getHeight() <= height) low = mid;
            else high = mid;
        }
        return low;
    }

    @SuppressLint("WrongConstant")
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

    private static final class PreparedBlock {
        final String text;
        final RectF bubble;
        final PointF anchor;
        final int textWidth;
        final float textSize;
        final float horizontalPadding;
        final float verticalPadding;
        final float radius;

        PreparedBlock(String text, RectF bubble, PointF anchor, int textWidth, float textSize,
                      float horizontalPadding, float verticalPadding, float radius) {
            this.text = text;
            this.bubble = bubble;
            this.anchor = anchor;
            this.textWidth = textWidth;
            this.textSize = textSize;
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
            this.radius = radius;
        }

        float textTop(StaticLayout layout) {
            return bubble.top + Math.max(verticalPadding,
                    (bubble.height() - layout.getHeight()) * 0.5f);
        }
    }
}
