package com.rarisol.imagelingo;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import org.junit.Test;

public final class RetryPolicyTest {
    @Test public void parsesSecondsAndGoogleRetryDelay() {
        assertEquals(1500L, RetryPolicy.parseRetryAfterMillis("1.5", 0L));
        assertEquals(12_250L, RetryPolicy.parseRetryDelayFromBody(
                "{\"retryDelay\":\"12.25s\"}"));
    }

    @Test public void parsesHttpDateAndIgnoresPastOrInvalidValues() {
        long now = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli();
        assertEquals(60_000L, RetryPolicy.parseRetryAfterMillis(
                "Sat, 22 Aug 2026 00:01:00 GMT", now));
        assertEquals(0L, RetryPolicy.parseRetryAfterMillis(
                "Fri, 21 Aug 2026 23:59:00 GMT", now));
        assertEquals(0L, RetryPolicy.parseRetryAfterMillis("later", now));
    }
}
