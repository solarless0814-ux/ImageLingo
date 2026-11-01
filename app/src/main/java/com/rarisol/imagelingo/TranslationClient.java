package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.util.Base64;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TranslationClient {
    private static final String DEFAULT_SYSTEM_PROMPT =
            "당신은 이미지 번역 전문 번역가입니다. 이미지에 보이는 한국어가 아닌 모든 글자를 자연스러운 한국어로 번역하세요. " +
            "만화, 게임, 비주얼노벨 대사라면 화자 순서, 말투, 존댓말, 감정과 문맥을 최대한 유지하세요. " +
            "효과음, 작은 글씨, UI 문구도 가능한 한 빠뜨리지 마세요. 고유명사는 문맥에 맞게 일관되게 표기하세요.";

    private static final String STRUCTURED_OUTPUT_INSTRUCTION =
            "반드시 마크다운 없이 유효한 JSON 객체 하나만 출력하세요. " +
            "형식은 {\"blocks\":[{\"text\":\"한국어 번역\",\"x\":0,\"y\":0,\"width\":100,\"height\":100}]," +
            "\"memory_updates\":{\"characters\":{\"원문 이름\":{\"korean_name\":\"통일 표기\",\"speech_style\":\"말투와 높임체\",\"role\":\"관계/역할\"}}," +
            "\"terms\":{\"원문 고유명사\":\"통일 한국어 표기\"}," +
            "\"honorifics\":{\"화자→상대\":\"사용할 호칭\"}}} 입니다. " +
            "x, y, width, height는 원본 이미지 전체를 가로·세로 각각 0~1000으로 본 정수 좌표입니다. " +
            "말풍선이나 문단별로 blocks를 나누고 읽는 순서를 유지하세요. text에는 번역문만 넣으세요. " +
            "확정된 이름, 고유명사, 높임체, 호칭은 반드시 유지하고 memory_updates에는 새로 확실해진 정보만 넣으세요. " +
            "번역할 외국어가 없으면 blocks는 빈 배열로 반환하세요.";

    private static final String USER_REQUEST =
            "이미지 속 외국어를 한국어로 번역하고 원문 좌표와 필요한 문맥 업데이트를 지정된 JSON 형식으로 반환하세요.";

    // Balanced for OCR readability while reducing encode + upload time versus the 0.x defaults.
    private static final int MAX_IMAGE_EDGE = 1440;
    private static final int JPEG_QUALITY = 80;
    private static final int MAX_OUTPUT_TOKENS = 8192;
    /**
     * Gemini 4 Argon always thinks, and thinking tokens count against maxOutputTokens.
     * 32,768 is the non-streaming REST ceiling; a smaller cap can end in an empty answer.
     */
    private static final int ARGON_MAX_OUTPUT_TOKENS = 32_768;
    private static final int DEFAULT_READ_TIMEOUT_MS = 90_000;
    /** Argon can reason for several minutes on a dense page. */
    private static final int ARGON_READ_TIMEOUT_MS = 10 * 60 * 1000;
    /** Gemini 3.8 Flash on high thinking is slower than the old 90s budget allows. */
    private static final int FLASH_38_READ_TIMEOUT_MS = 5 * 60 * 1000;
    /** Flex targets 1-15 minutes; Google recommends client timeouts of at least 10 minutes. */
    private static final int FLEX_READ_TIMEOUT_MS = 15 * 60 * 1000;
    private static final String OFFICIAL_GEMINI_BASE = "https://generativelanguage.googleapis.com";
    static final String VERTEX_SHARED_REQUEST_TYPE_HEADER = "X-Vertex-AI-LLM-Shared-Request-Type";
    private static final int MAX_RESPONSE_CHARACTERS = 2_000_000;
    private static final int MAX_TRANSLATION_BLOCKS = 200;
    private static final int MAX_BLOCK_TEXT_CHARACTERS = 2_000;
    private static final int MAX_RATE_LIMIT_RETRIES = 5;
    private static final long[] RATE_LIMIT_BACKOFF_MS = {5_000L, 10_000L, 20_000L, 40_000L, 60_000L};

    private static final Map<String, CachedToken> TOKEN_CACHE = new HashMap<>();

    public static String defaultSystemPrompt() {
        return DEFAULT_SYSTEM_PROMPT;
    }

    public static synchronized void clearCachedCredentials() {
        TOKEN_CACHE.clear();
    }

    public static void validateConfiguration(ApiSettings settings, SecureStore secrets) throws Exception {
        if (settings == null || secrets == null) {
            throw new IllegalArgumentException("API 설정을 불러오지 못했습니다.");
        }
        switch (settings.provider()) {
            case ApiSettings.OPENAI:
                require(secrets.get("openai_key"), "OpenAI API 키가 설정되지 않았습니다.");
                require(settings.openAiModel(), "OpenAI 모델명이 비어 있습니다.");
                NetworkPolicy.requireHttps(settings.openAiEndpoint());
                break;
            case ApiSettings.VERTEX:
                String vertexModel = normalizeModelId(settings.vertexModel());
                require(vertexModel, "Vertex AI 모델명이 비어 있습니다.");
                String jsonText = secrets.get("vertex_json");
                require(jsonText, "Vertex AI 서비스 계정 JSON을 불러오지 않았습니다.");
                JSONObject service = new JSONObject(jsonText);
                require(service.optString("client_email"), "Vertex client_email이 없습니다.");
                require(service.optString("private_key"), "Vertex private_key가 없습니다.");
                parsePrivateKey(service.getString("private_key"));
                NetworkPolicy.requireGoogleTokenEndpoint(service.optString(
                        "token_uri", "https://oauth2.googleapis.com/token"));
                String project = settings.vertexProject().trim();
                if (project.isEmpty()) project = service.optString("project_id", "");
                require(project, "Vertex AI 프로젝트 ID가 없습니다.");
                String location = normalizeVertexLocation(settings.vertexLocation());
                if (!location.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                    throw new IllegalArgumentException("Vertex AI 리전 형식이 올바르지 않습니다.");
                }
                validateVertexModelLocation(vertexModel, location);
                validateVertexTierLocation(vertexModel, settings.geminiServiceTier(), location);
                break;
            case ApiSettings.GEMINI:
            default:
                require(secrets.get("gemini_key"), "Gemini API 키가 설정되지 않았습니다.");
                require(settings.geminiModel(), "Gemini 모델명이 비어 있습니다.");
                geminiEndpoint(settings.geminiProxyUrl(), normalizeModelId(settings.geminiModel()));
                break;
        }
    }

    public static TranslationResult translate(ContentResolver resolver, Uri imageUri,
                                              ApiSettings settings, SecureStore secrets) throws Exception {
        return translate(resolver, imageUri, settings, secrets, "", null);
    }

    public static TranslationResult translate(ContentResolver resolver, Uri imageUri,
                                              ApiSettings settings, SecureStore secrets,
                                              String continuityContext) throws Exception {
        return translate(resolver, imageUri, settings, secrets, continuityContext, null);
    }

    public static TranslationResult translate(ContentResolver resolver, Uri imageUri,
                                              ApiSettings settings, SecureStore secrets,
                                              String continuityContext, RetryListener retryListener) throws Exception {
        validateConfiguration(settings, secrets);
        ImagePayload image = encodeImage(resolver, imageUri);
        String configuredPrompt = settings.systemPrompt();
        configuredPrompt = configuredPrompt == null ? "" : configuredPrompt.trim();
        String systemPrompt = configuredPrompt.isEmpty() ? DEFAULT_SYSTEM_PROMPT : configuredPrompt;
        StringBuilder structuredPrompt = new StringBuilder(systemPrompt)
                .append("\n\n").append(STRUCTURED_OUTPUT_INSTRUCTION);
        if (continuityContext != null && !continuityContext.trim().isEmpty()) {
            structuredPrompt.append("\n\n").append(continuityContext.trim());
        }
        switch (settings.provider()) {
            case ApiSettings.OPENAI:
                return translateOpenAi(image, structuredPrompt.toString(), settings,
                        secrets.get("openai_key"), retryListener);
            case ApiSettings.VERTEX:
                return translateVertex(image, structuredPrompt.toString(), settings,
                        secrets.get("vertex_json"), retryListener);
            case ApiSettings.GEMINI:
            default:
                return translateGemini(image, structuredPrompt.toString(), settings,
                        secrets.get("gemini_key"), retryListener);
        }
    }

    private static TranslationResult translateOpenAi(ImagePayload image, String systemPrompt,
                                                     ApiSettings settings, String apiKey,
                                                     RetryListener retryListener) throws Exception {
        require(apiKey, "OpenAI API 키가 설정되지 않았습니다.");
        require(settings.openAiModel(), "OpenAI 모델명이 비어 있습니다.");
        require(settings.openAiEndpoint(), "OpenAI 엔드포인트가 비어 있습니다.");
        NetworkPolicy.requireHttps(settings.openAiEndpoint());

        JSONObject body = new JSONObject();
        body.put("model", settings.openAiModel());
        body.put("instructions", systemPrompt);
        body.put("max_output_tokens", MAX_OUTPUT_TOKENS);
        body.put("store", false);
        body.put("text", new JSONObject().put("format", openAiTranslationFormat()));
        if (settings.usesFlexTier()) {
            body.put("service_tier", "flex");
        } else if (NetworkPolicy.isOfficialOpenAiEndpoint(settings.openAiEndpoint())) {
            body.put("service_tier", "default");
        }

        JSONArray input = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text", USER_REQUEST));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("detail", "auto")
                .put("image_url", "data:" + image.mimeType + ";base64," + image.base64));
        message.put("content", content);
        input.put(message);
        body.put("input", input);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey.trim());
        JSONObject response = postJson(settings.openAiEndpoint(), body, headers, retryListener);

        String status = response.optString("status", "completed");
        if (!status.isEmpty() && !"completed".equalsIgnoreCase(status)) {
            JSONObject details = response.optJSONObject("incomplete_details");
            String reason = details == null ? status : details.optString("reason", status);
            throw new IllegalStateException("OpenAI 응답이 완료되지 않았습니다: " + reason);
        }

        String rawText = parseOpenAiText(response);
        TokenUsage usage = parseOpenAiUsage(response);
        ParsedTranslation parsed = parseStructuredTranslation(rawText);
        return new TranslationResult(parsed.text, usage, parsed.blocks, parsed.memoryUpdates);
    }

    private static JSONObject openAiTranslationFormat() throws Exception {
        return new JSONObject()
                .put("type", "json_schema")
                .put("name", "imagelingo_translation")
                .put("strict", false)
                .put("schema", translationJsonSchema());
    }

    private static JSONObject translationJsonSchema() throws Exception {
        JSONObject coordinate = new JSONObject()
                .put("type", "number")
                .put("minimum", 0)
                .put("maximum", 1000);
        JSONObject dimension = new JSONObject()
                .put("type", "number")
                .put("minimum", 1)
                .put("maximum", 1000);
        JSONObject block = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("text", new JSONObject().put("type", "string"))
                        .put("x", coordinate)
                        .put("y", coordinate)
                        .put("width", dimension)
                        .put("height", dimension))
                .put("required", new JSONArray()
                        .put("text").put("x").put("y").put("width").put("height"))
                .put("additionalProperties", false);
        JSONObject stringValue = new JSONObject().put("type", "string");
        JSONObject characterValue = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("korean_name", new JSONObject(stringValue.toString()))
                        .put("speech_style", new JSONObject(stringValue.toString()))
                        .put("role", new JSONObject(stringValue.toString())))
                .put("additionalProperties", false);
        JSONObject characterMap = new JSONObject()
                .put("type", "object")
                .put("additionalProperties", characterValue);
        JSONObject stringMap = new JSONObject()
                .put("type", "object")
                .put("additionalProperties", stringValue);
        JSONObject memory = new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("characters", characterMap)
                        .put("terms", stringMap)
                        .put("honorifics", new JSONObject(stringMap.toString())))
                .put("additionalProperties", false);
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("blocks", new JSONObject()
                                .put("type", "array")
                                .put("items", block))
                        .put("memory_updates", memory))
                .put("required", new JSONArray().put("blocks").put("memory_updates"))
                .put("additionalProperties", false);
    }

    static boolean isArgonModel(String model) {
        return normalizeModelId(model).startsWith("gemini-4-argon");
    }

    /** Gemini 3 and later use the responseFormat structured-output shape. */
    static boolean usesResponseFormat(String model) {
        String id = normalizeModelId(model);
        return id.startsWith("gemini-3") || id.startsWith("gemini-4");
    }

    /**
     * Gemini 3.6 / 3.7 / 3.8 Flash. All take the low/medium/high thinking level (3.6 also minimal)
     * and the Flex/Priority tiers. 3.7 is deprecated and Google routes its requests to 3.8.
     */
    static boolean isModernFlashModel(String model) {
        String id = normalizeModelId(model);
        return id.startsWith("gemini-3.8-flash") || isFlash37Model(id) || isFlash36Model(id);
    }

    static boolean isFlash36Model(String model) {
        return normalizeModelId(model).startsWith("gemini-3.6-flash");
    }

    /** Only 3.6 Flash accepts "minimal"; Argon, 3.7 and 3.8 reject it with an error. */
    static boolean supportsMinimalThinking(String model) {
        return isFlash36Model(model);
    }

    /** The level actually sent: minimal falls back to low on models that reject it. */
    static String effectiveThinkingLevel(String model, String thinkingLevel) {
        String level = ApiSettings.normalizeThinkingLevel(thinkingLevel);
        if (ApiSettings.THINKING_MINIMAL.equals(level) && !supportsMinimalThinking(model)) {
            return ApiSettings.THINKING_LOW;
        }
        return level;
    }

    static boolean isFlash37Model(String model) {
        return normalizeModelId(model).startsWith("gemini-3.7-flash");
    }

    /**
     * Part of a page's output fingerprint that reflects the thinking level. Changing the level
     * marks earlier results stale. 3.6 / 3.7 Flash requests used to carry no thinking setting (Google's
     * default, medium), so medium keeps their existing fingerprints and avoids paid re-translation.
     */
    static String thinkingFingerprint(String model, String thinkingLevel) {
        if (!supportsThinkingLevel(model)) return "";
        String level = effectiveThinkingLevel(model, thinkingLevel);
        // 3.6 / 3.7 requests used to carry no thinking setting, i.e. Google's default (medium).
        if ((isFlash37Model(model) || isFlash36Model(model))
                && ApiSettings.THINKING_MEDIUM.equals(level)) return "";
        return "thinking:" + level;
    }

    /** Thinking tokens count against the cap, so thinking-level models get headroom. */
    static int maxOutputTokensFor(String model) {
        return supportsThinkingLevel(model) ? ARGON_MAX_OUTPUT_TOKENS : MAX_OUTPUT_TOKENS;
    }

    static int readTimeoutFor(String model) {
        if (isArgonModel(model)) return ARGON_READ_TIMEOUT_MS;
        if (isModernFlashModel(model)) return FLASH_38_READ_TIMEOUT_MS;
        return DEFAULT_READ_TIMEOUT_MS;
    }

    static int readTimeoutFor(String model, String geminiTier) {
        int base = readTimeoutFor(model);
        if ("flex".equals(geminiTierValue(model, geminiTier))) return Math.max(base, FLEX_READ_TIMEOUT_MS);
        return base;
    }

    /** Flex / Priority is offered for Gemini 3.6 / 3.7 / 3.8 Flash; other models stay on Standard. */
    static boolean supportsGeminiServiceTier(String model) {
        return isModernFlashModel(model);
    }

    /** "flex" / "priority" when it applies to this model, otherwise null (Standard: send nothing). */
    static String geminiTierValue(String model, String tier) {
        if (!supportsGeminiServiceTier(model)) return null;
        String clean = ApiSettings.normalizeGeminiServiceTier(tier);
        if (ApiSettings.GEMINI_TIER_FLEX.equals(clean)) return "flex";
        if (ApiSettings.GEMINI_TIER_PRIORITY.equals(clean)) return "priority";
        return null;
    }

    /** Vertex Flex / Priority PayGo is served only from the global endpoint. */
    static void validateVertexTierLocation(String model, String tier, String location) {
        String value = geminiTierValue(model, tier);
        if (value != null && !"global".equals(normalizeVertexLocation(location))) {
            throw new IllegalArgumentException("Vertex AI의 " + (value.equals("flex") ? "Flex" : "Priority")
                    + " 티어는 global 리전에서만 쓸 수 있습니다. 리전을 global로 바꾸거나 Standard를 선택하세요.");
        }
    }

    /**
     * Builds the generateContent URL. An empty proxy uses Google's official host. A reverse-proxy
     * base may end at its root ("https://p.example/google") or at a version ("…/v1beta").
     */
    static String geminiEndpoint(String proxyBase, String model) throws Exception {
        String base = proxyBase == null ? "" : proxyBase.trim();
        if (base.isEmpty()) base = OFFICIAL_GEMINI_BASE;
        URL url = NetworkPolicy.requireHttps(base);
        if (url.getQuery() != null) {
            throw new IllegalArgumentException("프록시 URL에 ?로 시작하는 쿼리를 넣을 수 없습니다. 키는 Gemini API 키 칸에 넣으세요.");
        }
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/models")) {
            base = base.substring(0, base.length() - "/models".length());
            lower = base.toLowerCase(Locale.ROOT);
        }
        boolean hasVersion = lower.matches(".*/v1(alpha|beta)?\\d*$");
        return base + (hasVersion ? "" : "/v1beta") + "/models/" + urlPath(model) + ":generateContent";
    }

    static boolean usesGeminiProxy(String proxyBase) {
        return proxyBase != null && !proxyBase.trim().isEmpty();
    }

    /**
     * Models that take the low/medium/high thinking level from settings: Gemini 4 and
     * Gemini 3.6 / 3.7 / 3.8 Flash. Older models never receive thinkingConfig, so their requests are unchanged.
     */
    static boolean supportsThinkingLevel(String model) {
        String id = normalizeModelId(model);
        return id.startsWith("gemini-4") || isModernFlashModel(id);
    }

    private static void putThinkingLevel(JSONObject config, String model, String thinkingLevel)
            throws Exception {
        if (thinkingLevel == null || !supportsThinkingLevel(model)) return;
        config.put("thinkingConfig", new JSONObject()
                .put("thinkingLevel", effectiveThinkingLevel(model, thinkingLevel)));
    }

    static JSONObject buildGeminiGenerationConfig(String model) throws Exception {
        return buildGeminiGenerationConfig(model, null);
    }

    static JSONObject buildVertexGenerationConfig(String model) throws Exception {
        return buildVertexGenerationConfig(model, null);
    }

    static JSONObject buildGeminiGenerationConfig(String model, String thinkingLevel) throws Exception {
        JSONObject config = new JSONObject().put("maxOutputTokens", maxOutputTokensFor(model));
        putThinkingLevel(config, model, thinkingLevel);
        if (usesResponseFormat(model)) {
            // The Gemini Developer API uses one responseFormat object and an IANA MIME string.
            config.put("responseFormat", new JSONObject()
                    .put("text", new JSONObject()
                            .put("mimeType", "application/json")
                            .put("schema", translationJsonSchema())));
        } else {
            config.put("responseMimeType", "application/json")
                    .put("responseJsonSchema", translationJsonSchema());
        }
        return config;
    }

    static JSONObject buildVertexGenerationConfig(String model, String thinkingLevel) throws Exception {
        JSONObject config = new JSONObject().put("maxOutputTokens", maxOutputTokensFor(model));
        putThinkingLevel(config, model, thinkingLevel);
        if (usesResponseFormat(model)) {
            // Vertex AI v1 differs from the Developer API: responseFormat is an array and
            // TextResponseFormat.mimeType is the APPLICATION_JSON enum value.
            config.put("responseFormat", new JSONArray().put(new JSONObject()
                    .put("text", new JSONObject()
                            .put("mimeType", "APPLICATION_JSON")
                            .put("schema", translationJsonSchema()))));
        } else {
            config.put("responseMimeType", "application/json")
                    .put("responseJsonSchema", translationJsonSchema());
        }
        return config;
    }

    static String normalizeModelId(String value) {
        String model = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        while (model.endsWith("/")) model = model.substring(0, model.length() - 1);
        int slash = model.lastIndexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    static String normalizeVertexLocation(String value) {
        String location = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return location.isEmpty() ? "global" : location;
    }

    static String vertexEndpointHost(String locationValue) {
        String location = normalizeVertexLocation(locationValue);
        if ("global".equals(location)) return "aiplatform.googleapis.com";
        if ("us".equals(location) || "eu".equals(location)) {
            return "aiplatform." + location + ".rep.googleapis.com";
        }
        return location + "-aiplatform.googleapis.com";
    }

    static void validateVertexModelLocation(String modelValue, String locationValue) {
        String model = normalizeModelId(modelValue);
        String location = normalizeVertexLocation(locationValue);
        boolean multiRegionOnly = model.startsWith("gemini-3.7-flash") || isArgonModel(model);
        if (multiRegionOnly
                && !"global".equals(location)
                && !"us".equals(location)
                && !"eu".equals(location)) {
            throw new IllegalArgumentException(
                    model + "의 Vertex AI 리전은 global, us 또는 eu만 지원합니다. global을 권장합니다.");
        }
    }

    private static String parseOpenAiText(JSONObject response) {
        String direct = response.optString("output_text", "").trim();
        if (!direct.isEmpty()) return direct;

        StringBuilder text = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONObject outputItem = output.optJSONObject(i);
                JSONArray parts = outputItem == null ? null : outputItem.optJSONArray("content");
                if (parts == null) continue;
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part == null) continue;
                    String value = part.optString("text", "");
                    if (!value.isEmpty()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(value);
                    }
                }
            }
        }
        if (text.length() == 0) {
            throw new IllegalStateException("OpenAI 응답에서 번역문을 찾지 못했습니다.");
        }
        return text.toString().trim();
    }

    private static TokenUsage parseOpenAiUsage(JSONObject response) {
        JSONObject usage = response.optJSONObject("usage");
        if (usage == null) return TokenUsage.unavailable();
        int input = usage.optInt("input_tokens", -1);
        int output = usage.optInt("output_tokens", -1);
        int total = usage.optInt("total_tokens", deriveTotal(input, output));
        int reasoning = -1;
        JSONObject details = usage.optJSONObject("output_tokens_details");
        if (details != null) reasoning = details.optInt("reasoning_tokens", -1);
        return new TokenUsage(input, output, total, reasoning);
    }

    private static TranslationResult translateGemini(ImagePayload image, String systemPrompt,
                                                     ApiSettings settings, String apiKey,
                                                     RetryListener retryListener) throws Exception {
        require(apiKey, "Gemini API 키가 설정되지 않았습니다.");
        require(settings.geminiModel(), "Gemini 모델명이 비어 있습니다.");
        String model = normalizeModelId(settings.geminiModel());
        String proxyBase = settings.geminiProxyUrl();
        String endpoint = geminiEndpoint(proxyBase, model);
        String tierValue = geminiTierValue(model, settings.geminiServiceTier());

        JSONObject inline = new JSONObject()
                .put("mime_type", image.mimeType)
                .put("data", image.base64);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", USER_REQUEST))
                .put(new JSONObject().put("inline_data", inline));
        JSONObject systemInstruction = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        JSONObject generationConfig = buildGeminiGenerationConfig(model, settings.thinkingLevel());
        JSONObject body = new JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", generationConfig);
        if (tierValue != null) body.put("service_tier", tierValue);
        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", apiKey.trim());
        // Reverse proxies differ: some read x-goog-api-key, others a Bearer token. Never sent to Google.
        if (usesGeminiProxy(proxyBase)) headers.put("Authorization", "Bearer " + apiKey.trim());
        JSONObject response = postJson(endpoint, body, headers, retryListener,
                readTimeoutFor(model, settings.geminiServiceTier()));
        return parseGeminiResult(response, "Gemini");
    }

    private static TranslationResult translateVertex(ImagePayload image, String systemPrompt,
                                                     ApiSettings settings, String serviceJsonText,
                                                     RetryListener retryListener) throws Exception {
        require(serviceJsonText, "Vertex AI 서비스 계정 JSON을 불러오지 않았습니다.");
        String model = normalizeModelId(settings.vertexModel());
        require(model, "Vertex AI 모델명이 비어 있습니다.");
        JSONObject service = new JSONObject(serviceJsonText);
        String clientEmail = service.getString("client_email");
        String privateKey = service.getString("private_key");
        String tokenUri = service.optString("token_uri", "https://oauth2.googleapis.com/token");
        NetworkPolicy.requireGoogleTokenEndpoint(tokenUri);
        String project = settings.vertexProject().trim();
        if (project.isEmpty()) project = service.optString("project_id", "");
        require(project, "Vertex AI 프로젝트 ID가 없습니다.");
        String location = normalizeVertexLocation(settings.vertexLocation());
        if (!location.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
            throw new IllegalArgumentException("Vertex AI 리전 형식이 올바르지 않습니다.");
        }
        validateVertexModelLocation(model, location);
        validateVertexTierLocation(model, settings.geminiServiceTier(), location);
        String tierValue = geminiTierValue(model, settings.geminiServiceTier());
        String token = getAccessToken(clientEmail, privateKey, tokenUri);
        String host = vertexEndpointHost(location);
        String endpoint = "https://" + host + "/v1/projects/" + urlPath(project)
                + "/locations/" + urlPath(location)
                + "/publishers/google/models/" + urlPath(model) + ":generateContent";

        JSONObject inline = new JSONObject()
                .put("mimeType", image.mimeType)
                .put("data", image.base64);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", USER_REQUEST))
                .put(new JSONObject().put("inlineData", inline));
        JSONObject systemInstruction = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        JSONObject body = new JSONObject()
                .put("systemInstruction", systemInstruction)
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", buildVertexGenerationConfig(model, settings.thinkingLevel()));
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        if (tierValue != null) headers.put(VERTEX_SHARED_REQUEST_TYPE_HEADER, tierValue);
        int readTimeoutMs = readTimeoutFor(model, settings.geminiServiceTier());
        JSONObject response;
        try {
            try {
                response = postJson(endpoint, body, headers, retryListener, readTimeoutMs);
            } catch (HttpStatusException authError) {
                if (authError.statusCode != 401) throw authError;
                synchronized (TranslationClient.class) {
                    TOKEN_CACHE.remove(clientEmail);
                }
                headers.put("Authorization", "Bearer "
                        + getAccessToken(clientEmail, privateKey, tokenUri));
                response = postJson(endpoint, body, headers, retryListener, readTimeoutMs);
            }
        } catch (HttpStatusException forbidden) {
            if (forbidden.statusCode != 403) throw forbidden;
            throw new HttpStatusException(403, vertexPermissionHint(
                    forbidden.getMessage(), project, service.optString("project_id", ""),
                    clientEmail, model, location));
        }
        return parseGeminiResult(response, "Vertex AI");
    }

    private static TranslationResult parseGeminiResult(JSONObject response, String provider) {
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException(provider + " 응답에 번역 후보가 없습니다.");
        }
        JSONObject candidate = candidates.optJSONObject(0);
        String finishReason = candidate == null ? "" : candidate.optString("finishReason", "");
        if (!finishReason.isEmpty() && !"STOP".equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException(provider + " 응답이 완료되지 않았습니다: " + finishReason);
        }
        JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder result = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                String text = part == null ? "" : part.optString("text", "");
                if (!text.isEmpty()) {
                    if (result.length() > 0) result.append('\n');
                    result.append(text);
                }
            }
        }
        if (result.length() == 0) {
            throw new IllegalStateException(provider + " 응답에서 번역문을 찾지 못했습니다.");
        }

        JSONObject usage = response.optJSONObject("usageMetadata");
        TokenUsage tokenUsage = TokenUsage.unavailable();
        if (usage != null) {
            int input = usage.optInt("promptTokenCount", -1);
            int output = usage.optInt("candidatesTokenCount", -1);
            int total = usage.optInt("totalTokenCount", deriveTotal(input, output));
            int reasoning = usage.optInt("thoughtsTokenCount", -1);
            tokenUsage = new TokenUsage(input, output, total, reasoning);
        }
        ParsedTranslation parsed = parseStructuredTranslation(result.toString().trim());
        return new TranslationResult(parsed.text, tokenUsage, parsed.blocks, parsed.memoryUpdates);
    }

    private static ParsedTranslation parseStructuredTranslation(String rawText) {
        String clean = rawText == null ? "" : rawText.trim();
        if (clean.isEmpty()) {
            throw new IllegalStateException("API 응답에 번역 JSON이 없습니다.");
        }
        if (clean.length() > MAX_RESPONSE_CHARACTERS) {
            throw new IllegalStateException("API 번역 응답이 허용 크기를 초과했습니다.");
        }
        List<TranslationBlock> blocks = new ArrayList<>();
        JSONObject memoryUpdates = new JSONObject();
        try {
            if (!clean.startsWith("{") || !clean.endsWith("}")) {
                throw new IllegalArgumentException("JSON 객체 없음");
            }
            JSONObject rootObject = new JSONObject(clean);
            JSONObject updates = rootObject.optJSONObject("memory_updates");
            if (updates != null) memoryUpdates = updates;
            JSONArray values = rootObject.optJSONArray("blocks");
            if (values == null) throw new IllegalArgumentException("blocks 배열 없음");
            if (values.length() > MAX_TRANSLATION_BLOCKS) {
                throw new IllegalArgumentException("번역 블록이 너무 많습니다.");
            }
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) throw new IllegalArgumentException("번역 블록이 객체가 아닙니다.");
                String text = item.optString("text", "").trim();
                if (text.isEmpty()) throw new IllegalArgumentException("번역 블록 text가 비어 있습니다.");
                if (text.length() > MAX_BLOCK_TEXT_CHARACTERS) {
                    throw new IllegalArgumentException("번역 블록 텍스트가 너무 깁니다.");
                }

                double x = item.optDouble("x", Double.NaN);
                double y = item.optDouble("y", Double.NaN);
                double width = item.optDouble("width", Double.NaN);
                double height = item.optDouble("height", Double.NaN);
                if (!isFinitePositive(width) || !isFinitePositive(height)
                        || Double.isNaN(x) || Double.isInfinite(x)
                        || Double.isNaN(y) || Double.isInfinite(y)) {
                    throw new IllegalArgumentException("번역 블록 좌표가 없거나 잘못되었습니다.");
                }

                double maxValue = Math.max(Math.max(Math.abs(x), Math.abs(y)),
                        Math.max(Math.abs(width), Math.abs(height)));
                if (maxValue <= 1.5d) {
                    x *= 1000d;
                    y *= 1000d;
                    width *= 1000d;
                    height *= 1000d;
                }
                float safeX = clamp((float) x, 0f, 999f);
                float safeY = clamp((float) y, 0f, 999f);
                float safeWidth = clamp((float) width, 1f, 1000f - safeX);
                float safeHeight = clamp((float) height, 1f, 1000f - safeY);
                blocks.add(new TranslationBlock(text, safeX, safeY, safeWidth, safeHeight));
            }
        } catch (Exception malformed) {
            throw new IllegalStateException("구조화된 번역 JSON을 해석하지 못했습니다: "
                    + malformed.getMessage(), malformed);
        }

        StringBuilder combined = new StringBuilder();
        for (TranslationBlock block : blocks) {
            if (combined.length() > 0) combined.append("\n\n");
            combined.append(block.text);
        }
        String text = combined.length() == 0 ? "[번역할 텍스트 없음]" : combined.toString();
        return new ParsedTranslation(text, blocks, memoryUpdates);
    }

    private static boolean isFinitePositive(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0d;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class ParsedTranslation {
        final String text;
        final List<TranslationBlock> blocks;
        final JSONObject memoryUpdates;
        ParsedTranslation(String text, List<TranslationBlock> blocks, JSONObject memoryUpdates) {
            this.text = text;
            this.blocks = blocks;
            this.memoryUpdates = memoryUpdates == null ? new JSONObject() : memoryUpdates;
        }
    }

    private static int deriveTotal(int input, int output) {
        return input >= 0 && output >= 0 ? input + output : -1;
    }

    private static synchronized String getAccessToken(String clientEmail, String pem,
                                                       String tokenUri) throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        CachedToken cached = TOKEN_CACHE.get(clientEmail);
        if (cached != null && cached.expiryEpochSeconds - 120 > now) return cached.value;

        JSONObject header = new JSONObject().put("alg", "RS256").put("typ", "JWT");
        JSONObject claims = new JSONObject()
                .put("iss", clientEmail)
                .put("scope", "https://www.googleapis.com/auth/cloud-platform")
                .put("aud", tokenUri)
                .put("iat", now)
                .put("exp", now + 3600);
        String unsigned = base64Url(header.toString().getBytes(StandardCharsets.UTF_8)) + "."
                + base64Url(claims.toString().getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(parsePrivateKey(pem));
        signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
        String assertion = unsigned + "." + base64Url(signer.sign());
        String form = "grant_type=" + URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8.name())
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8.name());
        JSONObject response = postForm(tokenUri, form);
        String accessToken = response.getString("access_token");
        long expiresIn = response.optLong("expires_in", 3600);
        TOKEN_CACHE.put(clientEmail, new CachedToken(accessToken, now + expiresIn));
        return accessToken;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String clean = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
    }

    private static ImagePayload encodeImage(ContentResolver resolver, Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
        Bitmap decoded = ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            Size size = info.getSize();
            int max = Math.max(size.getWidth(), size.getHeight());
            if (max > MAX_IMAGE_EDGE) {
                float ratio = (float) MAX_IMAGE_EDGE / max;
                decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * ratio)),
                        Math.max(1, Math.round(size.getHeight() * ratio)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        Bitmap rgb = decoded;
        try {
            if (decoded.hasAlpha()) {
                rgb = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(rgb);
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(decoded, 0, 0, null);
            }
            int initialCapacity = Math.min(1_500_000,
                    Math.max(64 * 1024, (rgb.getWidth() * rgb.getHeight()) / 3));
            ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
            if (!rgb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                throw new IllegalStateException("이미지를 JPEG로 변환하지 못했습니다.");
            }
            return new ImagePayload("image/jpeg",
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
        } finally {
            if (rgb != null && rgb != decoded && !rgb.isRecycled()) rgb.recycle();
            if (decoded != null && !decoded.isRecycled()) decoded.recycle();
        }
    }

    /**
     * Turns Google's bare 403 into a checklist naming the exact project, account and model,
     * so the user can see which side (IAM, API, model access, project mismatch) to fix.
     */
    static String vertexPermissionHint(String original, String project, String jsonProject,
                                       String clientEmail, String model, String location) {
        String detail = original == null ? "" : original.replaceFirst("^HTTP 403:\\s*", "").trim();
        StringBuilder hint = new StringBuilder(detail.isEmpty() ? "권한 거부(403)" : detail);
        hint.append("\n\n요청: 프로젝트 ").append(project)
                .append(" · 리전 ").append(location)
                .append(" · 모델 ").append(model)
                .append("\n서비스 계정: ").append(clientEmail)
                .append("\n\n확인할 것:");
        int step = 1;
        if (jsonProject != null && !jsonProject.isEmpty() && !jsonProject.equals(project)) {
            hint.append("\n").append(step++).append(". 설정의 프로젝트 ID(").append(project)
                    .append(")가 JSON의 project_id(").append(jsonProject)
                    .append(")와 다릅니다. 다른 프로젝트를 쓰려면 그 프로젝트에도 이 서비스 계정 권한을 주세요.");
        }
        hint.append("\n").append(step++).append(". 프로젝트에서 Vertex AI API(aiplatform.googleapis.com)가 사용 설정되어 있는지");
        hint.append("\n").append(step++).append(". IAM에서 이 서비스 계정에 'Vertex AI 사용자'(roles/aiplatform.user) 역할이 있는지");
        hint.append("\n").append(step++).append(". 프로젝트에 결제 계정이 연결되어 있는지");
        if (isArgonModel(model)) {
            hint.append("\n").append(step).append(". Gemini 4 Argon은 단계적으로 공개 중인 모델입니다. "
                    + "Model Garden에서 이 프로젝트가 Argon을 쓸 수 있는지(사용 설정·약관 동의) 확인하세요. "
                    + "같은 계정으로 gemini-3.7-flash가 되면 Argon 접근 권한 문제입니다.");
        }
        return hint.toString();
    }

    private static JSONObject postJson(String endpoint, JSONObject body,
                                       Map<String, String> headers, RetryListener retryListener) throws Exception {
        return postJson(endpoint, body, headers, retryListener, DEFAULT_READ_TIMEOUT_MS);
    }

    private static JSONObject postJson(String endpoint, JSONObject body, Map<String, String> headers,
                                       RetryListener retryListener, int readTimeoutMs) throws Exception {
        return request(endpoint, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8), headers, retryListener, true,
                readTimeoutMs);
    }

    private static JSONObject postForm(String endpoint, String body) throws Exception {
        return request(endpoint, "application/x-www-form-urlencoded; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8), new HashMap<>(), null, false,
                DEFAULT_READ_TIMEOUT_MS);
    }

    private static JSONObject request(String endpoint, String contentType, byte[] body,
                                       Map<String, String> headers, RetryListener retryListener,
                                       boolean retryRateLimit, int readTimeoutMs) throws Exception {
        URL url = NetworkPolicy.requireHttps(endpoint);
        int retryAttempt = 0;
        while (true) {
            if (retryListener != null && retryListener.isCancelled()) {
                throw new TranslationCancelledException();
            }
            HttpURLConnection connection = null;
            int code;
            String retryAfterHeader;
            String responseText;
            try {
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(25_000);
                connection.setReadTimeout(readTimeoutMs);
                connection.setDoOutput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", contentType);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "ImageLingo/1.0 Android");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                code = connection.getResponseCode();
                retryAfterHeader = connection.getHeaderField("Retry-After");
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_RESPONSE_CHARACTERS * 4L) {
                    throw new IOException("API 응답이 허용 크기를 초과했습니다.");
                }
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                responseText = readAll(stream, MAX_RESPONSE_CHARACTERS);
            } finally {
                if (connection != null) connection.disconnect();
            }

            if (code >= 200 && code < 300) return new JSONObject(responseText);
            String errorMessage = extractError(responseText);
            // 503 = Google at capacity (Flex has no automatic fallback); back off like a 429.
            if ((code != 429 && code != 503) || !retryRateLimit) {
                throw new HttpStatusException(code, errorMessage);
            }
            if (isNonRecoverableQuotaError(errorMessage + " " + responseText)) {
                throw new RateLimitException(errorMessage, retryAttempt);
            }
            if (retryAttempt >= MAX_RATE_LIMIT_RETRIES) {
                throw new RateLimitException(errorMessage, retryAttempt);
            }

            long backoff = RATE_LIMIT_BACKOFF_MS[Math.min(retryAttempt, RATE_LIMIT_BACKOFF_MS.length - 1)];
            long retryAfter = Math.max(
                    RetryPolicy.parseRetryAfterMillis(retryAfterHeader, System.currentTimeMillis()),
                    RetryPolicy.parseRetryDelayFromBody(responseText));
            long delayMs = Math.max(backoff, retryAfter);
            retryAttempt++;
            waitForRetry(delayMs, retryAttempt, retryListener, errorMessage);
        }
    }

    private static void waitForRetry(long delayMs, int attempt, RetryListener listener,
                                     String message) throws Exception {
        // Honor server Retry-After guidance while retaining a finite ceiling. The UI remains
        // cancellable once per second during a long provider-requested pause.
        long remaining = Math.max(1_000L, Math.min(delayMs, 15L * 60L * 1000L));
        while (remaining > 0L) {
            if (listener != null && listener.isCancelled()) throw new TranslationCancelledException();
            long seconds = Math.max(1L, (remaining + 999L) / 1000L);
            if (listener != null) {
                listener.onRateLimitRetry(attempt, MAX_RATE_LIMIT_RETRIES, seconds, message);
            }
            long sleep = Math.min(1_000L, remaining);
            try { Thread.sleep(sleep); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TranslationCancelledException();
            }
            remaining -= sleep;
        }
    }

    private static boolean isNonRecoverableQuotaError(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("insufficient_quota")
                || lower.contains("check your plan")
                || lower.contains("billing details")
                || lower.contains("credit balance")
                || lower.contains("payment required");
    }

    private static String readAll(InputStream stream, int maxCharacters) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, read);
                if (result.length() > maxCharacters) {
                    throw new IOException("API 응답이 허용 크기를 초과했습니다.");
                }
            }
        }
        return result.toString();
    }

    static String extractError(String body) {
        try {
            JSONObject json = new JSONObject(body);
            Object error = json.opt("error");
            if (error instanceof JSONObject) {
                JSONObject errorObject = (JSONObject) error;
                StringBuilder message = new StringBuilder(errorObject.optString("message", "").trim());
                JSONArray details = errorObject.optJSONArray("details");
                if (details != null) {
                    for (int i = 0; i < details.length(); i++) {
                        JSONObject detail = details.optJSONObject(i);
                        JSONArray violations = detail == null
                                ? null : detail.optJSONArray("fieldViolations");
                        if (violations == null) continue;
                        for (int j = 0; j < violations.length(); j++) {
                            JSONObject violation = violations.optJSONObject(j);
                            if (violation == null) continue;
                            String field = violation.optString("field", "").trim();
                            String description = violation.optString("description", "").trim();
                            if (field.isEmpty() && description.isEmpty()) continue;
                            if (message.length() > 0) message.append(" · ");
                            if (!field.isEmpty()) message.append(field);
                            if (!field.isEmpty() && !description.isEmpty()) message.append(": ");
                            message.append(description);
                            if (message.length() >= 700) break;
                        }
                        if (message.length() >= 700) break;
                    }
                }
                if (message.length() > 0) return limitErrorMessage(message.toString());
            }
            if (error instanceof String) return limitErrorMessage((String) error);
            String message = json.optString("message", "");
            if (!message.isEmpty()) return limitErrorMessage(message);
        } catch (Exception ignored) {}
        String trimmed = body == null ? "" : body.trim();
        return limitErrorMessage(trimmed);
    }

    private static String limitErrorMessage(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() > 700 ? trimmed.substring(0, 700) + "…" : trimmed;
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String urlPath(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static void require(String value, String message) {
        if (value == null || value.trim().isEmpty()) throw new IllegalStateException(message);
    }

    public interface RetryListener {
        void onRateLimitRetry(int attempt, int maxRetries, long remainingSeconds, String message);
        boolean isCancelled();
    }

    public static final class RateLimitException extends IOException {
        public final int retryCount;
        RateLimitException(String message, int retryCount) {
            super(message == null || message.trim().isEmpty()
                    ? "API 사용량 제한(429)에 도달했습니다." : message);
            this.retryCount = retryCount;
        }
    }

    public static final class HttpStatusException extends IOException {
        public final int statusCode;
        HttpStatusException(int statusCode, String message) {
            super("HTTP " + statusCode + ": "
                    + (message == null || message.trim().isEmpty() ? "요청 실패" : message));
            this.statusCode = statusCode;
        }
    }

    public static final class TranslationCancelledException extends IOException {
        TranslationCancelledException() { super("번역이 중지되었습니다."); }
    }

    public static final class TranslationResult {
        public final String text;
        public final TokenUsage usage;
        public final List<TranslationBlock> blocks;
        public final JSONObject memoryUpdates;

        TranslationResult(String text, TokenUsage usage, List<TranslationBlock> blocks,
                          JSONObject memoryUpdates) {
            this.text = text == null ? "" : text;
            this.usage = usage == null ? TokenUsage.unavailable() : usage;
            this.blocks = blocks == null ? new ArrayList<>() : new ArrayList<>(blocks);
            try {
                this.memoryUpdates = memoryUpdates == null
                        ? new JSONObject() : new JSONObject(memoryUpdates.toString());
            } catch (Exception ignored) {
                throw new IllegalArgumentException("문맥 업데이트 JSON을 복사하지 못했습니다.");
            }
        }
    }

    public static final class TranslationBlock {
        public final String text;
        public final float x;
        public final float y;
        public final float width;
        public final float height;

        TranslationBlock(String text, float x, float y, float width, float height) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    public static final class TokenUsage {
        public final int inputTokens;
        public final int outputTokens;
        public final int totalTokens;
        public final int reasoningTokens;

        TokenUsage(int inputTokens, int outputTokens, int totalTokens, int reasoningTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
            this.totalTokens = totalTokens;
            this.reasoningTokens = reasoningTokens;
        }

        static TokenUsage unavailable() {
            return new TokenUsage(-1, -1, -1, -1);
        }

        public boolean isAvailable() {
            return totalTokens >= 0 || inputTokens >= 0 || outputTokens >= 0;
        }
    }

    private static final class ImagePayload {
        final String mimeType;
        final String base64;
        ImagePayload(String mimeType, String base64) { this.mimeType = mimeType; this.base64 = base64; }
    }

    private static final class CachedToken {
        final String value;
        final long expiryEpochSeconds;
        CachedToken(String value, long expiryEpochSeconds) {
            this.value = value;
            this.expiryEpochSeconds = expiryEpochSeconds;
        }
    }

    private TranslationClient() {}
}
