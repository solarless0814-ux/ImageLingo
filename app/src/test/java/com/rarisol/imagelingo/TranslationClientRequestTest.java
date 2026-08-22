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
