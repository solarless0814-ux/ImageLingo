package com.rarisol.imagelingo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable, collision-resistant identity for a source image and translation settings. */
final class OutputFingerprint {
    private static final String FORMAT_VERSION = "imagelingo-output-v2";

    private OutputFingerprint() {}

    static String create(String sourceUri, String sourceName, long sourceSize,
                         long sourceLastModified, String provider, String model,
                         String endpoint, String serviceTier, String project,
                         String location, String prompt, String continuityContext) {
        return sha256(FORMAT_VERSION, sourceUri, sourceName,
                Long.toString(sourceSize), Long.toString(sourceLastModified),
                provider, model, endpoint, serviceTier, project, location,
                prompt, continuityContext);
    }

    static String sha256(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (values != null) {
                for (String value : values) {
                    byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                    // Length-prefix every field so ["ab", "c"] and ["a", "bc"] differ.
                    digest.update((byte) (bytes.length >>> 24));
                    digest.update((byte) (bytes.length >>> 16));
                    digest.update((byte) (bytes.length >>> 8));
                    digest.update((byte) bytes.length);
                    digest.update(bytes);
                }
            }
            byte[] result = digest.digest();
            StringBuilder hex = new StringBuilder(result.length * 2);
            for (byte value : result) hex.append(String.format("%02x", value & 0xFF));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
