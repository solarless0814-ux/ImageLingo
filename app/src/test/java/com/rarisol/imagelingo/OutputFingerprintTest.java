package com.rarisol.imagelingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class OutputFingerprintTest {
    @Test public void isStableAndUsesFixedLengthHex() {
        String first = OutputFingerprint.sha256("source", "model", "prompt");
        assertEquals(first, OutputFingerprint.sha256("source", "model", "prompt"));
        assertEquals(64, first.length());
    }

    @Test public void lengthPrefixPreventsFieldBoundaryCollision() {
        assertNotEquals(OutputFingerprint.sha256("ab", "c"),
                OutputFingerprint.sha256("a", "bc"));
    }

    @Test public void changesWhenSourceOrSettingsChange() {
        String baseline = fingerprint(10L, 20L, "model-a", "prompt-a");
        assertNotEquals(baseline, fingerprint(11L, 20L, "model-a", "prompt-a"));
        assertNotEquals(baseline, fingerprint(10L, 21L, "model-a", "prompt-a"));
        assertNotEquals(baseline, fingerprint(10L, 20L, "model-b", "prompt-a"));
        assertNotEquals(baseline, fingerprint(10L, 20L, "model-a", "prompt-b"));
    }

    private static String fingerprint(long size, long modified, String model, String prompt) {
        return OutputFingerprint.create("content://source/1", "page.png", size, modified,
                "openai", model, "https://api.openai.com/v1/responses", "standard",
                "", "", prompt, "context");
    }
}
