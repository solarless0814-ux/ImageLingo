package com.rarisol.imagelingo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws translated text bubbles in normalized source-image coordinates. */
public final class TranslationOverlayView extends View {
    private final Matrix imageMatrix = new Matrix();
    private final List<TranslationClient.TranslationBlock> blocks = new ArrayList<>();
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF imageBounds = new RectF();
    private int sourceWidth;
    private int sourceHeight;

    public TranslationOverlayView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        bubblePaint.setColor(0xDFFFFFFF);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setShadowLayer(dp(7), 0f, dp(2), 0x55000000);

        borderPaint.setColor(0xB83A3F4A);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));

        textPaint.setColor(0xFF111318);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setSourceSize(int width, int height) {
        sourceWidth = Math.max(0, width);
        sourceHeight = Math.max(0, height);
        invalidate();
    }

    public void setBlocks(List<TranslationClient.TranslationBlock> values) {
        blocks.clear();
        if (values != null) blocks.addAll(values);
        setVisibility(blocks.isEmpty() ? GONE : VISIBLE);
        invalidate();
    }

    public void clearBlocks() {
        blocks.clear();
        setVisibility(GONE);
        invalidate();
    }

    public boolean hasBlocks() {
        return !blocks.isEmpty();
    }

    public void updateImageMatrix(Matrix matrix) {
        imageMatrix.set(matrix);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (blocks.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return;

        imageBounds.set(0, 0, sourceWidth, sourceHeight);
        imageMatrix.mapRect(imageBounds);

        for (TranslationClient.TranslationBlock block : blocks) {
            if (block == null || block.text == null || block.text.trim().isEmpty()) continue;
            RectF sourceRect = new RectF(
                    block.x / 1000f * sourceWidth,
                    block.y / 1000f * sourceHeight,
                    (block.x + block.width) / 1000f * sourceWidth,
                    (block.y + block.height) / 1000f * sourceHeight);
            imageMatrix.mapRect(sourceRect);
            drawBlock(canvas, block.text.trim(), sourceRect);
        }
    }

    private void drawBlock(Canvas canvas, String text, RectF detectedRect) {
        float imageWidth = Math.max(1f, imageBounds.width());
        float imageHeight = Math.max(1f, imageBounds.height());
        float outerMargin = dp(3);
        float horizontalPadding = dp(9);
        float verticalPadding = dp(7);

        int characterCount = Math.max(1, text.codePointCount(0, text.length()));
        float naturalWidth = dp(64) + (float) Math.sqrt(characterCount) * dp(19);
        float maxWidth = Math.max(dp(90), imageWidth * 0.78f);
        float bubbleWidth = Math.max(detectedRect.width() + horizontalPadding * 2f,
                Math.min(maxWidth, naturalWidth));
        bubbleWidth = Math.min(bubbleWidth, imageWidth - outerMargin * 2f);
        bubbleWidth = Math.max(dp(58), bubbleWidth);

        float bubbleHeight = Math.max(detectedRect.height() + verticalPadding * 2f, dp(38));
        bubbleHeight = Math.min(bubbleHeight, imageHeight * 0.58f);

        RectF bubble = centeredRect(detectedRect.centerX(), detectedRect.centerY(), bubbleWidth, bubbleHeight);
        clampInside(bubble, imageBounds, outerMargin);

        int textWidth = Math.max(1, Math.round(bubble.width() - horizontalPadding * 2f));
        float minTextSize = dpFloat(10f);
        float maxTextSize = Math.min(dpFloat(42f), Math.max(dpFloat(14f), detectedRect.height() * 0.48f));
        StaticLayout layout = fitLayout(text, textWidth,
                Math.max(1, Math.round(bubble.height() - verticalPadding * 2f)), minTextSize, maxTextSize);

        if (layout.getHeight() > bubble.height() - verticalPadding * 2f) {
            textPaint.setTextSize(minTextSize);
            layout = makeLayout(text, textWidth);
            float neededHeight = layout.getHeight() + verticalPadding * 2f;
            if (neededHeight > bubble.height()) {
                bubble.bottom = bubble.top + Math.min(neededHeight, imageHeight - outerMargin * 2f);
                clampInside(bubble, imageBounds, outerMargin);
            }
        }

        float radius = Math.min(dp(14), Math.min(bubble.width(), bubble.height()) * 0.22f);
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

    private StaticLayout fitLayout(String text, int width, int height, float minSize, float maxSize) {
        float low = minSize;
        float high = Math.max(minSize, maxSize);
        StaticLayout best;
        textPaint.setTextSize(low);
        best = makeLayout(text, width);
        for (int i = 0; i < 9; i++) {
            float mid = (low + high) * 0.5f;
            textPaint.setTextSize(mid);
            StaticLayout candidate = makeLayout(text, width);
            if (candidate.getHeight() <= height) {
                best = candidate;
                low = mid;
            } else {
                high = mid;
            }
        }
        textPaint.setTextSize(low);
        return makeLayout(text, width);
    }

    private StaticLayout makeLayout(String text, int width) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setLineSpacing(0f, 1.04f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
    }

    private RectF centeredRect(float centerX, float centerY, float width, float height) {
        return new RectF(centerX - width * 0.5f, centerY - height * 0.5f,
                centerX + width * 0.5f, centerY + height * 0.5f);
    }

    private void clampInside(RectF rect, RectF bounds, float margin) {
        float leftLimit = bounds.left + margin;
        float rightLimit = bounds.right - margin;
        float topLimit = bounds.top + margin;
        float bottomLimit = bounds.bottom - margin;

        if (rect.width() > rightLimit - leftLimit) {
            rect.left = leftLimit;
            rect.right = rightLimit;
        } else {
            if (rect.left < leftLimit) rect.offset(leftLimit - rect.left, 0);
            if (rect.right > rightLimit) rect.offset(rightLimit - rect.right, 0);
        }
        if (rect.height() > bottomLimit - topLimit) {
            rect.top = topLimit;
            rect.bottom = bottomLimit;
        } else {
            if (rect.top < topLimit) rect.offset(0, topLimit - rect.top);
            if (rect.bottom > bottomLimit) rect.offset(0, bottomLimit - rect.bottom);
        }
    }

    private float dpFloat(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
