package com.rarisol.imagelingo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Draws collision-aware translated text bubbles in normalized source-image coordinates. */
public final class TranslationOverlayView extends View {
    private final Matrix imageMatrix = new Matrix();
    private final List<TranslationClient.TranslationBlock> blocks = new ArrayList<>();
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint connectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF imageBounds = new RectF();
    private final List<PreparedBlock> preparedBlocks = new ArrayList<>();
    private final List<RectF> preparedRects = new ArrayList<>();
    private final List<PointF> preparedAnchors = new ArrayList<>();
    private boolean layoutDirty = true;
    private int sourceWidth;
    private int sourceHeight;

    public TranslationOverlayView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        bubblePaint.setColor(0x82111827);
        bubblePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setShadowLayer(dp(6), 0f, dp(2), 0x52000000);

        borderPaint.setColor(0x8AE6EEFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));

        connectorPaint.setColor(0xA8E6EEFF);
        connectorPaint.setStyle(Paint.Style.STROKE);
        connectorPaint.setStrokeCap(Paint.Cap.ROUND);
        connectorPaint.setStrokeWidth(dp(1.25f));

        textPaint.setColor(0xFFF8FAFF);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setShadowLayer(dp(1.4f), 0f, dp(0.7f), 0xD0000000);
    }

    public void setSourceSize(int width, int height) {
        sourceWidth = Math.max(0, width);
        sourceHeight = Math.max(0, height);
        layoutDirty = true;
        invalidate();
    }

    public void setBlocks(List<TranslationClient.TranslationBlock> values) {
        blocks.clear();
        if (values != null) blocks.addAll(values);
        layoutDirty = true;
        setVisibility(blocks.isEmpty() ? GONE : VISIBLE);
        invalidate();
    }

    public void clearBlocks() {
        blocks.clear();
        preparedBlocks.clear();
        preparedRects.clear();
        preparedAnchors.clear();
        layoutDirty = true;
        setVisibility(GONE);
        invalidate();
    }

    public boolean hasBlocks() {
        return !blocks.isEmpty();
    }

    public void updateImageMatrix(Matrix matrix) {
        if (matrix == null) imageMatrix.reset();
        else imageMatrix.set(matrix);
        layoutDirty = true;
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layoutDirty = true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (blocks.isEmpty() || sourceWidth <= 0 || sourceHeight <= 0) return;

        if (layoutDirty) rebuildPreparedBlocks();
        if (imageBounds.isEmpty()) return;
        for (PreparedBlock block : preparedBlocks) drawConnector(canvas, block);
        for (PreparedBlock block : preparedBlocks) {
            canvas.drawRoundRect(block.bubble, block.radius, block.radius, bubblePaint);
            canvas.drawRoundRect(block.bubble, block.radius, block.radius, borderPaint);
        }
        for (PreparedBlock block : preparedBlocks) drawText(canvas, block);
    }

    /** Rebuilds only when content or the image transform changes, not on every draw pass. */
    private void rebuildPreparedBlocks() {
        layoutDirty = false;
        preparedBlocks.clear();
        preparedRects.clear();
        preparedAnchors.clear();
        imageBounds.set(0f, 0f, sourceWidth, sourceHeight);
        imageMatrix.mapRect(imageBounds);
        if (imageBounds.isEmpty()) return;

        for (TranslationClient.TranslationBlock block : blocks) {
            if (block == null || block.text == null || block.text.trim().isEmpty()) continue;
            RectF sourceRect = new RectF(
                    block.x / 1000f * sourceWidth,
                    block.y / 1000f * sourceHeight,
                    (block.x + block.width) / 1000f * sourceWidth,
                    (block.y + block.height) / 1000f * sourceHeight);
            imageMatrix.mapRect(sourceRect);
            PreparedBlock value = prepareBlock(block.text.trim(), sourceRect);
            if (value != null) preparedBlocks.add(value);
        }

        for (PreparedBlock block : preparedBlocks) {
            preparedRects.add(block.bubble);
            preparedAnchors.add(block.anchor);
        }
        BubbleLayout.resolve(preparedRects, preparedAnchors, imageBounds, dp(5));
    }

    private PreparedBlock prepareBlock(String text, RectF detectedRect) {
        float imageWidth = Math.max(1f, imageBounds.width());
        float imageHeight = Math.max(1f, imageBounds.height());
        float outerMargin = dp(3);
        float horizontalPadding = dp(9);
        float verticalPadding = dp(7);

        int characterCount = Math.max(1, text.codePointCount(0, text.length()));
        float naturalWidth = dp(64) + (float) Math.sqrt(characterCount) * dp(19);
        float maxWidth = Math.max(dp(90), imageWidth * 0.78f);
        float availableWidth = Math.max(1f, imageWidth - outerMargin * 2f);
        float bubbleWidth = Math.max(detectedRect.width() + horizontalPadding * 2f,
                Math.min(maxWidth, naturalWidth));
        bubbleWidth = Math.min(availableWidth, Math.max(Math.min(dp(58), availableWidth), bubbleWidth));

        float availableHeight = Math.max(1f, imageHeight - outerMargin * 2f);
        float bubbleHeight = Math.max(detectedRect.height() + verticalPadding * 2f, dp(38));
        bubbleHeight = Math.min(availableHeight, Math.min(bubbleHeight, imageHeight * 0.58f));

        RectF bubble = centeredRect(detectedRect.centerX(), detectedRect.centerY(), bubbleWidth, bubbleHeight);
        clampInside(bubble, imageBounds, outerMargin);

        int textWidth = Math.max(1, Math.round(bubble.width() - horizontalPadding * 2f));
        float minTextSize = dpFloat(10f);
        float maxTextSize = Math.min(dpFloat(42f), Math.max(dpFloat(14f), detectedRect.height() * 0.48f));
        StaticLayout layout = fitLayout(text, textWidth,
                Math.max(1, Math.round(bubble.height() - verticalPadding * 2f)), minTextSize, maxTextSize);
        float textSize = textPaint.getTextSize();

        if (layout.getHeight() > bubble.height() - verticalPadding * 2f) {
            textSize = minTextSize;
            textPaint.setTextSize(textSize);
            layout = makeLayout(text, textWidth);
            float neededHeight = Math.min(availableHeight, layout.getHeight() + verticalPadding * 2f);
            if (neededHeight > bubble.height()) {
                bubble.bottom = bubble.top + neededHeight;
                clampInside(bubble, imageBounds, outerMargin);
            }
        }

        float radius = Math.min(dp(14), Math.min(bubble.width(), bubble.height()) * 0.22f);
        textPaint.setTextSize(textSize);
        TextPaint blockPaint = new TextPaint(textPaint);
        StaticLayout finalLayout = makeLayout(text, textWidth, blockPaint);
        return new PreparedBlock(text, bubble,
                new PointF(detectedRect.centerX(), detectedRect.centerY()), textWidth, textSize,
                horizontalPadding, verticalPadding, radius, finalLayout);
    }

    private void drawConnector(Canvas canvas, PreparedBlock block) {
        PointF anchor = block.anchor;
        RectF bubble = block.bubble;
        if (bubble.contains(anchor.x, anchor.y)) return;
        float endX = Math.max(bubble.left, Math.min(anchor.x, bubble.right));
        float endY = Math.max(bubble.top, Math.min(anchor.y, bubble.bottom));
        float dx = anchor.x - endX;
        float dy = anchor.y - endY;
        if (dx * dx + dy * dy < dp(5) * dp(5)) return;
        canvas.drawLine(anchor.x, anchor.y, endX, endY, connectorPaint);
        Paint.Style oldStyle = connectorPaint.getStyle();
        connectorPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(anchor.x, anchor.y, dp(1.8f), connectorPaint);
        connectorPaint.setStyle(oldStyle);
    }

    private void drawText(Canvas canvas, PreparedBlock block) {
        float textTop = block.bubble.top + Math.max(block.verticalPadding,
                (block.bubble.height() - block.layout.getHeight()) * 0.5f);
        canvas.save();
        canvas.clipRect(block.bubble);
        canvas.translate(block.bubble.left + block.horizontalPadding, textTop);
        block.layout.draw(canvas);
        canvas.restore();
    }

    private StaticLayout fitLayout(String text, int width, int height, float minSize, float maxSize) {
        float low = minSize;
        float high = Math.max(minSize, maxSize);
        textPaint.setTextSize(low);
        for (int i = 0; i < 9; i++) {
            float mid = (low + high) * 0.5f;
            textPaint.setTextSize(mid);
            StaticLayout candidate = makeLayout(text, width);
            if (candidate.getHeight() <= height) low = mid;
            else high = mid;
        }
        textPaint.setTextSize(low);
        return makeLayout(text, width);
    }

    @SuppressLint("WrongConstant")
    private StaticLayout makeLayout(String text, int width) {
        return makeLayout(text, width, textPaint);
    }

    @SuppressLint("WrongConstant")
    private StaticLayout makeLayout(String text, int width, TextPaint paint) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
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

    private float dpFloat(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
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
        final StaticLayout layout;

        PreparedBlock(String text, RectF bubble, PointF anchor, int textWidth, float textSize,
                      float horizontalPadding, float verticalPadding, float radius,
                      StaticLayout layout) {
            this.text = text;
            this.bubble = bubble;
            this.anchor = anchor;
            this.textWidth = textWidth;
            this.textSize = textSize;
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
            this.radius = radius;
            this.layout = layout;
        }
    }
}
