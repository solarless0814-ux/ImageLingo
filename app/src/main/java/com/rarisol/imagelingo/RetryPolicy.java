package com.rarisol.imagelingo;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java parsing for HTTP and Google retry hints. */
final class RetryPolicy {
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile(
            "(?:retryDelay|retry_delay)[^0-9]{0,40}([0-9]+(?:\\.[0-9]+)?)s",
            Pattern.CASE_INSENSITIVE);

    private RetryPolicy() {}

    static long parseRetryAfterMillis(String value, long nowMillis) {
        if (value == null || value.trim().isEmpty()) return 0L;
        String clean = value.trim();
        try {
            double seconds = Double.parseDouble(clean);
            return Math.max(0L, Math.round(seconds * 1000d));
        } catch (Exception ignored) {
            try {
                long target = ZonedDateTime.parse(clean, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
                return Math.max(0L, target - nowMillis);
            } catch (Exception ignoredDate) {
                return 0L;
            }
        }
    }

    static long parseRetryDelayFromBody(String body) {
        if (body == null || body.isEmpty()) return 0L;
        Matcher matcher = RETRY_DELAY_PATTERN.matcher(body);
        if (!matcher.find()) return 0L;
        try {
            double seconds = Double.parseDouble(matcher.group(1));
            return Math.max(0L, Math.round(seconds * 1000d));
        } catch (Exception ignored) {
            return 0L;
        }
    }
}
