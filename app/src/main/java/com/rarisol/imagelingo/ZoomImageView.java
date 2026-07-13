package com.rarisol.imagelingo;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public final class ZoomImageView extends ImageView {
    public interface PageSwipeListener { void onPageSwipe(int direction); }

    private final Matrix imageMatrixValue = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float minScale = 1f;
    private float currentScale = 1f;
    private PageSwipeListener pageSwipeListener;

    public ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(0xFF000000);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                if (getDrawable() == null) return false;
                float target = Math.max(minScale, Math.min(minScale * 6f, currentScale * detector.getScaleFactor()));
                float factor = target / currentScale;
                imageMatrixValue.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                currentScale = target;
                clampAndApply();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (currentScale > minScale * 1.01f) {
                    imageMatrixValue.postTranslate(-distanceX, -distanceY);
                    clampAndApply();
                    return true;
                }
                return false;
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (currentScale > minScale * 1.2f) {
                    resetMatrix();
                } else {
                    float target = Math.min(minScale * 2.5f, minScale * 6f);
                    float factor = target / currentScale;
                    imageMatrixValue.postScale(factor, factor, e.getX(), e.getY());
                    currentScale = target;
                    clampAndApply();
                }
                return true;
            }

            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (currentScale <= minScale * 1.05f && Math.abs(velocityX) > Math.abs(velocityY)
                        && Math.abs(velocityX) > 700f && pageSwipeListener != null) {
                    pageSwipeListener.onPageSwipe(velocityX < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setPageSwipeListener(PageSwipeListener listener) { this.pageSwipeListener = listener; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetMatrix();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        post(this::resetMatrix);
    }

    public void resetMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        int dw = drawable.getIntrinsicWidth();
        int dh = drawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float scale = Math.min((float) getWidth() / dw, (float) getHeight() / dh);
        float dx = (getWidth() - dw * scale) * 0.5f;
        float dy = (getHeight() - dh * scale) * 0.5f;
        imageMatrixValue.reset();
        imageMatrixValue.postScale(scale, scale);
        imageMatrixValue.postTranslate(dx, dy);
        minScale = scale;
        currentScale = scale;
        setImageMatrix(imageMatrixValue);
    }

    private void clampAndApply() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        imageMatrixValue.mapRect(rect);
        float dx = 0f, dy = 0f;
        if (rect.width() <= getWidth()) dx = getWidth() * 0.5f - rect.centerX();
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < getWidth()) dx = getWidth() - rect.right;
        if (rect.height() <= getHeight()) dy = getHeight() * 0.5f - rect.centerY();
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
        imageMatrixValue.postTranslate(dx, dy);
        setImageMatrix(imageMatrixValue);
    }
}
