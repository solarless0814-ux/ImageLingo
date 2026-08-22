package com.rarisol.imagelingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NetworkPolicyTest {
    @Test public void acceptsHttpsAndNormalizesOfficialHostCheck() {
        assertEquals("example.com", NetworkPolicy.requireHttps(" https://example.com/v1 ").getHost());
        assertTrue(NetworkPolicy.isOfficialOpenAiEndpoint("https://api.openai.com/v1/responses"));
        assertFalse(NetworkPolicy.isOfficialOpenAiEndpoint("https://api.openai.com.example/v1/responses"));
        assertEquals("oauth2.googleapis.com", NetworkPolicy.requireGoogleTokenEndpoint(
                "https://oauth2.googleapis.com/token").getHost());
    }

    @Test public void rejectsCleartextCredentialsAndAmbiguousUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> NetworkPolicy.requireHttps("http://example.com/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkPolicy.requireHttps("https://user@example.com/v1"));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkPolicy.requireHttps("https://example.com/v1#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkPolicy.requireGoogleTokenEndpoint("https://example.com/token"));
    }
}
