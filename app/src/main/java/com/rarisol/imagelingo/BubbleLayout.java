package com.rarisol.imagelingo;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/** Deterministic, image-space placement for translation bubbles. */
final class BubbleLayout {
    private BubbleLayout() {}

    /**
     * Moves the supplied rectangles just enough to avoid covering one another while keeping each
     * rectangle close to its source-text anchor. Rectangles are mutated in place.
     */
    static void resolve(List<RectF> bubbles, List<PointF> anchors, RectF bounds, float gap) {
        if (bubbles == null || bubbles.isEmpty() || bounds == null || bounds.isEmpty()) return;

        float safeGap = Math.max(0f, gap);
        List<RectF> placed = new ArrayList<>(bubbles.size());
        for (int i = 0; i < bubbles.size(); i++) {
            RectF bubble = bubbles.get(i);
            if (bubble == null || bubble.isEmpty()) continue;
            clampInside(bubble, bounds);

            PointF anchor = anchors != null && i < anchors.size() && anchors.get(i) != null
                    ? anchors.get(i) : new PointF(bubble.centerX(), bubble.centerY());
            RectF original = new RectF(bubble);
            Candidate bestClear = null;
            Candidate bestFallback = null;

            bestClear = chooseClear(bestClear,
                    evaluate(original, anchor, original, placed, bounds, safeGap));
            bestFallback = chooseFallback(bestFallback,
                    evaluate(original, anchor, original, placed, bounds, safeGap));

            // Exact edge-aligned candidates solve the common case of adjacent/overlapping OCR boxes.
            for (RectF other : placed) {
                float above = other.top - safeGap - original.height() * 0.5f;
                float below = other.bottom + safeGap + original.height() * 0.5f;
                float left = other.left - safeGap - original.width() * 0.5f;
                float right = other.right + safeGap + original.width() * 0.5f;
                float[] xs = {anchor.x, other.centerX(), left, right};
                float[] ys = {anchor.y, other.centerY(), above, below};

                for (float x : xs) {
                    Candidate a = evaluateAt(original, anchor, x, above, placed, bounds, safeGap);
                    Candidate b = evaluateAt(original, anchor, x, below, placed, bounds, safeGap);
                    bestClear = chooseClear(bestClear, a);
                    bestClear = chooseClear(bestClear, b);
                    bestFallback = chooseFallback(bestFallback, a);
                    bestFallback = chooseFallback(bestFallback, b);
                }
                for (float y : ys) {
                    Candidate a = evaluateAt(original, anchor, left, y, placed, bounds, safeGap);
                    Candidate b = evaluateAt(original, anchor, right, y, placed, bounds, safeGap);
                    bestClear = chooseClear(bestClear, a);
                    bestClear = chooseClear(bestClear, b);
                    bestFallback = chooseFallback(bestFallback, a);
                    bestFallback = chooseFallback(bestFallback, b);
                }
            }

            // A rectangular spiral finds nearby free space in dense manga panels without random jitter.
            float stepX = Math.max(safeGap * 2f, Math.min(original.width() * 0.42f,
                    Math.max(1f, bounds.width() * 0.12f)));
            float stepY = Math.max(safeGap * 2f, Math.min(original.height() * 0.55f,
                    Math.max(1f, bounds.height() * 0.08f)));
            for (int ring = 1; ring <= 10; ring++) {
                for (int x = -ring; x <= ring; x++) {
                    Candidate top = evaluateAt(original, anchor, anchor.x + x * stepX,
                            anchor.y - ring * stepY, placed, bounds, safeGap);
                    Candidate bottom = evaluateAt(original, anchor, anchor.x + x * stepX,
                            anchor.y + ring * stepY, placed, bounds, safeGap);
                    bestClear = chooseClear(bestClear, top);
                    bestClear = chooseClear(bestClear, bottom);
                    bestFallback = chooseFallback(bestFallback, top);
                    bestFallback = chooseFallback(bestFallback, bottom);
                }
                for (int y = -ring + 1; y < ring; y++) {
                    Candidate left = evaluateAt(original, anchor, anchor.x - ring * stepX,
                            anchor.y + y * stepY, placed, bounds, safeGap);
                    Candidate right = evaluateAt(original, anchor, anchor.x + ring * stepX,
                            anchor.y + y * stepY, placed, bounds, safeGap);
                    bestClear = chooseClear(bestClear, left);
                    bestClear = chooseClear(bestClear, right);
                    bestFallback = chooseFallback(bestFallback, left);
                    bestFallback = chooseFallback(bestFallback, right);
                }
            }

            Candidate chosen = bestClear != null ? bestClear : bestFallback;
            if (chosen != null) bubble.set(chosen.rect);
            clampInside(bubble, bounds);
            placed.add(bubble);
        }
    }

    private static Candidate evaluateAt(RectF shape, PointF anchor, float centerX, float centerY,
                                        List<RectF> placed, RectF bounds, float gap) {
        RectF candidate = new RectF(centerX - shape.width() * 0.5f,
                centerY - shape.height() * 0.5f,
                centerX + shape.width() * 0.5f,
                centerY + shape.height() * 0.5f);
        return evaluate(candidate, anchor, shape, placed, bounds, gap);
    }

    private static Candidate evaluate(RectF value, PointF anchor, RectF shape,
                                      List<RectF> placed, RectF bounds, float gap) {
        RectF candidate = new RectF(value);
        clampInside(candidate, bounds);
        float overlap = 0f;
        for (RectF other : placed) overlap += overlapArea(candidate, other, gap);

        float dx = candidate.centerX() - anchor.x;
        float dy = candidate.centerY() - anchor.y;
        float scaleX = Math.max(1f, shape.width());
        float scaleY = Math.max(1f, shape.height());
        float movement = dx * dx / (scaleX * scaleX) + dy * dy / (scaleY * scaleY);
        // A tiny edge penalty favors balanced placement when two locations are otherwise equivalent.
        float edge = Math.min(candidate.left - bounds.left, bounds.right - candidate.right)
                + Math.min(candidate.top - bounds.top, bounds.bottom - candidate.bottom);
        float score = movement - Math.max(0f, edge) / Math.max(1f, bounds.width() + bounds.height()) * 0.02f;
        return new Candidate(candidate, overlap, score);
    }

    private static Candidate chooseClear(Candidate current, Candidate candidate) {
        if (candidate == null || candidate.overlap > 0.01f) return current;
        return current == null || candidate.score < current.score ? candidate : current;
    }

    private static Candidate chooseFallback(Candidate current, Candidate candidate) {
        if (candidate == null) return current;
        if (current == null || candidate.overlap < current.overlap - 0.01f) return candidate;
        if (Math.abs(candidate.overlap - current.overlap) <= 0.01f && candidate.score < current.score) {
            return candidate;
        }
        return current;
    }

    private static float overlapArea(RectF first, RectF second, float gap) {
        float halfGap = gap * 0.5f;
        float left = Math.max(first.left - halfGap, second.left - halfGap);
        float top = Math.max(first.top - halfGap, second.top - halfGap);
        float right = Math.min(first.right + halfGap, second.right + halfGap);
        float bottom = Math.min(first.bottom + halfGap, second.bottom + halfGap);
        return right > left && bottom > top ? (right - left) * (bottom - top) : 0f;
    }

    private static void clampInside(RectF rect, RectF bounds) {
        if (rect.width() >= bounds.width()) {
            rect.left = bounds.left;
            rect.right = bounds.right;
        } else {
            if (rect.left < bounds.left) rect.offset(bounds.left - rect.left, 0f);
            if (rect.right > bounds.right) rect.offset(bounds.right - rect.right, 0f);
        }
        if (rect.height() >= bounds.height()) {
            rect.top = bounds.top;
            rect.bottom = bounds.bottom;
        } else {
            if (rect.top < bounds.top) rect.offset(0f, bounds.top - rect.top);
            if (rect.bottom > bounds.bottom) rect.offset(0f, bounds.bottom - rect.bottom);
        }
    }

    private static final class Candidate {
        final RectF rect;
        final float overlap;
        final float score;

        Candidate(RectF rect, float overlap, float score) {
            this.rect = rect;
            this.overlap = overlap;
            this.score = score;
        }
    }
}
