package com.rarisol.imagelingo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class TranslationClientRequestTest {
    @Test public void vertexGemini3UsesVertexV1ResponseFormatArray() throws Exception {
        JSONObject config = TranslationClient.buildVertexGenerationConfig("gemini-3.7-flash");

        assertFalse(config.has("responseMimeType"));
        assertFalse(config.has("responseJsonSchema"));
        JSONArray formats = config.getJSONArray("responseFormat");
        assertEquals(1, formats.length());
        JSONObject text = formats.getJSONObject(0).getJSONObject("text");
        assertEquals("APPLICATION_JSON", text.getString("mimeType"));
        assertEquals("object", text.getJSONObject("schema").getString("type"));
        assertFalse(text.getJSONObject("schema").toString().contains("maxItems"));
        assertFalse(text.getJSONObject("schema").toString().contains("maxLength"));
        assertFalse(text.getJSONObject("schema").toString().contains("exclusiveMinimum"));
    }

    @Test public void developerApiGemini3KeepsItsDifferentResponseFormatShape() throws Exception {
        JSONObject config = TranslationClient.buildGeminiGenerationConfig("models/gemini-3.7-flash");

        JSONObject text = config.getJSONObject("responseFormat").getJSONObject("text");
        assertEquals("application/json", text.getString("mimeType"));
    }

    @Test public void legacyVertexModelKeepsLegacyStructuredOutputFields() throws Exception {
        JSONObject config = TranslationClient.buildVertexGenerationConfig("gemini-2.5-flash");

        assertEquals("application/json", config.getString("responseMimeType"));
        assertTrue(config.has("responseJsonSchema"));
        assertFalse(config.has("responseFormat"));
    }

    @Test public void normalizesModelAndSelectsCorrectVertexHosts() {
        assertEquals("gemini-3.7-flash", TranslationClient.normalizeModelId(
                "projects/p/locations/global/publishers/google/models/Gemini-3.7-Flash"));
        assertEquals("aiplatform.googleapis.com",
                TranslationClient.vertexEndpointHost("GLOBAL"));
        assertEquals("aiplatform.us.rep.googleapis.com",
                TranslationClient.vertexEndpointHost("us"));
        assertEquals("aiplatform.eu.rep.googleapis.com",
                TranslationClient.vertexEndpointHost("eu"));
        assertEquals("asia-northeast3-aiplatform.googleapis.com",
                TranslationClient.vertexEndpointHost("asia-northeast3"));
    }

    @Test public void rejectsUnsupportedGemini37RegionBeforeUploading() {
        TranslationClient.validateVertexModelLocation("gemini-3.7-flash", "global");
        TranslationClient.validateVertexModelLocation("gemini-3.7-flash", "us");
        TranslationClient.validateVertexModelLocation("gemini-3.7-flash", "eu");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TranslationClient.validateVertexModelLocation(
                        "gemini-3.7-flash", "us-central1"));
        assertTrue(error.getMessage().contains("global, us 또는 eu"));
    }

    @Test public void vertexArgonUsesResponseFormatAndRoomForThinking() throws Exception {
        JSONObject config = TranslationClient.buildVertexGenerationConfig("gemini-4-argon");

        assertTrue(config.has("responseFormat"));
        assertFalse(config.has("responseMimeType"));
        assertEquals(32_768, config.getInt("maxOutputTokens"));
        assertEquals(8192, TranslationClient.buildVertexGenerationConfig("gemini-2.5-flash")
                .getInt("maxOutputTokens"));
        assertTrue(TranslationClient.readTimeoutFor("publishers/google/models/gemini-4-argon")
                > TranslationClient.readTimeoutFor("gemini-2.5-flash"));
    }

    @Test public void argonSendsChosenThinkingLevelOnBothApis() throws Exception {
        assertEquals("high", TranslationClient.buildVertexGenerationConfig("gemini-4-argon", "high")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals("low", TranslationClient.buildGeminiGenerationConfig("models/gemini-4-argon", "LOW")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals("medium", TranslationClient.buildVertexGenerationConfig("gemini-4-argon", "bogus")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
    }

    @Test public void flash38SendsThinkingLevelWithHeadroom() throws Exception {
        JSONObject vertex = TranslationClient.buildVertexGenerationConfig("gemini-3.8-flash", "high");
        assertEquals("high", vertex.getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals(32_768, vertex.getInt("maxOutputTokens"));
        assertTrue(vertex.has("responseFormat"));
        assertEquals("low", TranslationClient.buildGeminiGenerationConfig("models/gemini-3.8-flash", "low")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertTrue(TranslationClient.readTimeoutFor("gemini-3.8-flash")
                > TranslationClient.readTimeoutFor("gemini-2.5-flash"));
    }

    @Test public void geminiEndpointUsesOfficialHostOrReverseProxy() throws Exception {
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                TranslationClient.geminiEndpoint("", "gemini-3.8-flash"));
        assertEquals("https://p.example/google/v1beta/models/gemini-3.8-flash:generateContent",
                TranslationClient.geminiEndpoint("https://p.example/google/", "gemini-3.8-flash"));
        assertEquals("https://p.example/proxy/v1beta/models/gemini-3.8-flash:generateContent",
                TranslationClient.geminiEndpoint("https://p.example/proxy/v1beta", "gemini-3.8-flash"));
        assertEquals("https://p.example/v1/models/gemini-3.8-flash:generateContent",
                TranslationClient.geminiEndpoint("https://p.example/v1/models", "gemini-3.8-flash"));
        assertThrows(IllegalArgumentException.class,
                () -> TranslationClient.geminiEndpoint("http://p.example", "gemini-3.8-flash"));
        assertThrows(IllegalArgumentException.class,
                () -> TranslationClient.geminiEndpoint("https://p.example/?key=x", "gemini-3.8-flash"));
        assertFalse(TranslationClient.usesGeminiProxy("  "));
    }

    @Test public void flexAndPriorityApplyOnlyToFlash38() {
        assertEquals("flex", TranslationClient.geminiTierValue("gemini-3.8-flash", "FLEX"));
        assertEquals("priority", TranslationClient.geminiTierValue("models/gemini-3.8-flash", "priority"));
        assertEquals(null, TranslationClient.geminiTierValue("gemini-3.8-flash", "STANDARD"));
        assertEquals(null, TranslationClient.geminiTierValue("gemini-4-argon", "FLEX"));
        assertEquals(null, TranslationClient.geminiTierValue("gemini-2.5-flash", "PRIORITY"));
        assertEquals("priority", TranslationClient.geminiTierValue("gemini-3.7-flash", "PRIORITY"));
        assertEquals("flex", TranslationClient.geminiTierValue("gemini-3.6-flash", "FLEX"));
        assertTrue(TranslationClient.readTimeoutFor("gemini-3.8-flash", "FLEX") >= 10 * 60 * 1000);
        assertEquals(TranslationClient.readTimeoutFor("gemini-3.8-flash"),
                TranslationClient.readTimeoutFor("gemini-3.8-flash", "PRIORITY"));
    }

    @Test public void vertexFlexOrPriorityRequiresGlobal() {
        TranslationClient.validateVertexTierLocation("gemini-3.8-flash", "FLEX", "global");
        TranslationClient.validateVertexTierLocation("gemini-3.8-flash", "STANDARD", "us");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TranslationClient.validateVertexTierLocation("gemini-3.8-flash", "PRIORITY", "eu"));
        assertTrue(error.getMessage().contains("global"));
    }

    @Test public void flash36And37TakeThinkingLevelsAndOnly36KeepsMinimal() throws Exception {
        assertEquals("minimal", TranslationClient.buildGeminiGenerationConfig("gemini-3.6-flash", "minimal")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals("high", TranslationClient.buildVertexGenerationConfig("gemini-3.7-flash", "high")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        // Argon / 3.7 / 3.8 reject minimal, so it is sent as low.
        assertEquals("low", TranslationClient.buildVertexGenerationConfig("gemini-3.8-flash", "minimal")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals("low", TranslationClient.buildGeminiGenerationConfig("gemini-4-argon", "minimal")
                .getJSONObject("thinkingConfig").getString("thinkingLevel"));
        assertEquals(32_768, TranslationClient.buildVertexGenerationConfig("gemini-3.6-flash", "low")
                .getInt("maxOutputTokens"));
    }

    @Test public void thinkingFingerprintKeepsOldResultsForLegacyMediumOnly() {
        // 3.6 / 3.7 used to send no thinking setting (= medium): existing results stay current.
        assertEquals("", TranslationClient.thinkingFingerprint("gemini-3.7-flash", "medium"));
        assertEquals("", TranslationClient.thinkingFingerprint("gemini-3.6-flash", "medium"));
        assertEquals("thinking:high", TranslationClient.thinkingFingerprint("gemini-3.7-flash", "high"));
        assertEquals("thinking:minimal", TranslationClient.thinkingFingerprint("gemini-3.6-flash", "minimal"));
        // 3.8 / Argon already recorded medium explicitly; minimal is stored as what is sent (low).
        assertEquals("thinking:medium", TranslationClient.thinkingFingerprint("gemini-3.8-flash", "medium"));
        assertEquals("thinking:low", TranslationClient.thinkingFingerprint("gemini-4-argon", "minimal"));
        assertEquals("", TranslationClient.thinkingFingerprint("gemini-2.5-flash", "high"));
    }

    @Test public void olderModelsNeverGetThinkingConfig() throws Exception {
        assertFalse(TranslationClient.buildVertexGenerationConfig("gemini-3.5-flash-lite", "high")
                .has("thinkingConfig"));
        assertFalse(TranslationClient.buildGeminiGenerationConfig("gemini-2.5-flash", "low")
                .has("thinkingConfig"));
        assertFalse(TranslationClient.buildVertexGenerationConfig("gemini-4-argon")
                .has("thinkingConfig"));
    }

    @Test public void rejectsUnsupportedArgonRegionBeforeUploading() {
        TranslationClient.validateVertexModelLocation("gemini-4-argon", "global");
        TranslationClient.validateVertexModelLocation("gemini-4-argon", "eu");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TranslationClient.validateVertexModelLocation(
                        "gemini-4-argon", "asia-northeast3"));
        assertTrue(error.getMessage().contains("global, us 또는 eu"));
    }

    @Test public void vertex403HintNamesProjectMismatchAndArgonAccess() {
        String hint = TranslationClient.vertexPermissionHint(
                "HTTP 403: Permission denied", "typed-project", "json-project",
                "bot@json-project.iam.gserviceaccount.com", "gemini-4-argon", "global");

        assertTrue(hint.startsWith("Permission denied"));
        assertTrue(hint.contains("typed-project"));
        assertTrue(hint.contains("json-project"));
        assertTrue(hint.contains("roles/aiplatform.user"));
        assertTrue(hint.contains("Argon"));
    }

    @Test public void includesGoogleFieldViolationsInHttpError() {
        String body = "{\"error\":{\"message\":\"Request contains an invalid argument.\","
                + "\"details\":[{\"fieldViolations\":[{\"field\":\"generationConfig.responseFormat\","
                + "\"description\":\"Expected an array.\"}]}]}}";

        String message = TranslationClient.extractError(body);

        assertTrue(message.contains("Request contains an invalid argument"));
        assertTrue(message.contains("generationConfig.responseFormat"));
        assertTrue(message.contains("Expected an array"));
    }
}
