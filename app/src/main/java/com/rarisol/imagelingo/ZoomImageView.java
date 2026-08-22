package com.rarisol.imagelingo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

public final class ZoomImageView extends ImageView {
    public interface PageSwipeListener { void onPageSwipe(int direction); }
    public interface ImageMatrixListener { void onImageMatrixChanged(Matrix matrix); }

    private final Matrix imageMatrixValue = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final int touchSlop;
    private float minScale = 1f;
    private float currentScale = 1f;
    private float touchDownX;
    private float touchDownY;
    private boolean touchMoved;
    private ValueAnimator matrixAnimator;
    private PageSwipeListener pageSwipeListener;
    private ImageMatrixListener imageMatrixListener;

    public ZoomImageView(Context context) {
        super(context);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(0xFF000000);
        setClickable(true);
        setFocusable(true);
        setHapticFeedbackEnabled(true);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

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
                    animateResetMatrix();
                } else {
                    float target = Math.min(minScale * 2.5f, minScale * 6f);
                    animateZoomTo(target, e.getX(), e.getY());
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

    public void setImageMatrixListener(ImageMatrixListener listener) {
        this.imageMatrixListener = listener;
        if (listener != null) listener.onImageMatrixChanged(new Matrix(imageMatrixValue));
    }

    public Matrix copyImageMatrix() { return new Matrix(imageMatrixValue); }

    private void notifyMatrixChanged() {
        if (imageMatrixListener != null) {
            imageMatrixListener.onImageMatrixChanged(new Matrix(imageMatrixValue));
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelMatrixAnimation();
            touchDownX = event.getX();
            touchDownY = event.getY();
            touchMoved = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !touchMoved) {
            float dx = event.getX() - touchDownX;
            float dy = event.getY() - touchDownY;
            touchMoved = dx * dx + dy * dy > touchSlop * touchSlop;
        }
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!touchMoved && !scaleDetector.isInProgress()) performClick();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetMatrix();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        cancelMatrixAnimation();
        super.setImageDrawable(drawable);
        post(this::resetMatrix);
    }

    public void resetMatrix() {
        cancelMatrixAnimation();
        applyFitMatrix();
    }

    private void applyFitMatrix() {
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
        notifyMatrixChanged();
    }

    private void animateResetMatrix() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
        int dw = drawable.getIntrinsicWidth();
        int dh = drawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float scale = Math.min((float) getWidth() / dw, (float) getHeight() / dh);
        float dx = (getWidth() - dw * scale) * 0.5f;
        float dy = (getHeight() - dh * scale) * 0.5f;
        Matrix target = new Matrix();
        target.postScale(scale, scale);
        target.postTranslate(dx, dy);
        animateMatrixTo(target, scale);
    }

    private void animateZoomTo(float targetScale, float focusX, float focusY) {
        if (getDrawable() == null) return;
        float clampedTarget = Math.max(minScale, Math.min(minScale * 6f, targetScale));
        Matrix target = new Matrix(imageMatrixValue);
        float factor = clampedTarget / currentScale;
        target.postScale(factor, factor, focusX, focusY);
        clampMatrix(target);
        animateMatrixTo(target, clampedTarget);
    }

    private void animateMatrixTo(Matrix target, float targetScale) {
        cancelMatrixAnimation();
        float[] startValues = new float[9];
        float[] endValues = new float[9];
        float[] values = new float[9];
        imageMatrixValue.getValues(startValues);
        target.getValues(endValues);
        float startScale = currentScale;

        matrixAnimator = ValueAnimator.ofFloat(0f, 1f);
        matrixAnimator.setDuration(220L);
        matrixAnimator.setInterpolator(new DecelerateInterpolator());
        matrixAnimator.addUpdateListener(animation -> {
            float progress = (float) animation.getAnimatedValue();
            for (int i = 0; i < values.length; i++) {
                values[i] = startValues[i] + (endValues[i] - startValues[i]) * progress;
            }
            imageMatrixValue.setValues(values);
            currentScale = startScale + (targetScale - startScale) * progress;
            setImageMatrix(imageMatrixValue);
            notifyMatrixChanged();
        });
        matrixAnimator.start();
    }

    private void cancelMatrixAnimation() {
        if (matrixAnimator != null) {
            matrixAnimator.cancel();
            matrixAnimator = null;
        }
    }

    private void clampAndApply() {
        clampMatrix(imageMatrixValue);
        setImageMatrix(imageMatrixValue);
        notifyMatrixChanged();
    }

    private void clampMatrix(Matrix matrix) {
        Drawable drawable = getDrawable();
        if (drawable == null) return;
        RectF rect = new RectF(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(rect);
        float dx = 0f, dy = 0f;
        if (rect.width() <= getWidth()) dx = getWidth() * 0.5f - rect.centerX();
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < getWidth()) dx = getWidth() - rect.right;
        if (rect.height() <= getHeight()) dy = getHeight() * 0.5f - rect.centerY();
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
        matrix.postTranslate(dx, dy);
    }

    @Override protected void onDetachedFromWindow() {
        cancelMatrixAnimation();
        super.onDetachedFromWindow();
    }
}
