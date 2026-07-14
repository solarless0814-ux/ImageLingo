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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final int MAX_RATE_LIMIT_RETRIES = 5;
    private static final long[] RATE_LIMIT_BACKOFF_MS = {5_000L, 10_000L, 20_000L, 40_000L, 60_000L};
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile(
            "(?:retryDelay|retry_delay)[^0-9]{0,40}([0-9]+(?:\\.[0-9]+)?)s",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, CachedToken> TOKEN_CACHE = new HashMap<>();

    public static String defaultSystemPrompt() {
        return DEFAULT_SYSTEM_PROMPT;
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
        ImagePayload image = encodeImage(resolver, imageUri);
        String systemPrompt = settings.systemPrompt().trim().isEmpty()
                ? DEFAULT_SYSTEM_PROMPT : settings.systemPrompt().trim();
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

        JSONObject body = new JSONObject();
        body.put("model", settings.openAiModel());
        body.put("instructions", systemPrompt);
        body.put("max_output_tokens", 2500);

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

        String rawText = parseOpenAiText(response);
        TokenUsage usage = parseOpenAiUsage(response);
        ParsedTranslation parsed = parseStructuredTranslation(rawText);
        return new TranslationResult(parsed.text, usage, parsed.blocks, parsed.memoryUpdates);
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
        String model = settings.geminiModel().replace("models/", "");
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent";

        JSONObject inline = new JSONObject()
                .put("mime_type", image.mimeType)
                .put("data", image.base64);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", USER_REQUEST))
                .put(new JSONObject().put("inline_data", inline));
        JSONObject systemInstruction = new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
        JSONObject body = new JSONObject()
                .put("system_instruction", systemInstruction)
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.1)
                        .put("maxOutputTokens", 2500)
                        .put("responseMimeType", "application/json"));
        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", apiKey.trim());
        JSONObject response = postJson(endpoint, body, headers, retryListener);
        return parseGeminiResult(response, "Gemini");
    }

    private static TranslationResult translateVertex(ImagePayload image, String systemPrompt,
                                                     ApiSettings settings, String serviceJsonText,
                                                     RetryListener retryListener) throws Exception {
        require(serviceJsonText, "Vertex AI 서비스 계정 JSON을 불러오지 않았습니다.");
        require(settings.vertexModel(), "Vertex AI 모델명이 비어 있습니다.");
        JSONObject service = new JSONObject(serviceJsonText);
        String clientEmail = service.getString("client_email");
        String privateKey = service.getString("private_key");
        String tokenUri = service.optString("token_uri", "https://oauth2.googleapis.com/token");
        String project = settings.vertexProject().trim();
        if (project.isEmpty()) project = service.optString("project_id", "");
        require(project, "Vertex AI 프로젝트 ID가 없습니다.");
        String location = settings.vertexLocation().trim().isEmpty() ? "global" : settings.vertexLocation().trim();
        String token = getAccessToken(clientEmail, privateKey, tokenUri);
        String host = "global".equalsIgnoreCase(location)
                ? "aiplatform.googleapis.com"
                : location + "-aiplatform.googleapis.com";
        String endpoint = "https://" + host + "/v1/projects/" + urlPath(project)
                + "/locations/" + urlPath(location)
                + "/publishers/google/models/" + urlPath(settings.vertexModel()) + ":generateContent";

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
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.1)
                        .put("maxOutputTokens", 2500)
                        .put("responseMimeType", "application/json"));
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        JSONObject response = postJson(endpoint, body, headers, retryListener);
        return parseGeminiResult(response, "Vertex AI");
    }

    private static TranslationResult parseGeminiResult(JSONObject response, String provider) {
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException(provider + " 응답에 번역 후보가 없습니다.");
        }
        JSONObject content = candidates.optJSONObject(0) == null
                ? null : candidates.optJSONObject(0).optJSONObject("content");
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
        String clean = stripCodeFence(rawText == null ? "" : rawText.trim());
        List<TranslationBlock> blocks = new ArrayList<>();
        List<String> unplacedTexts = new ArrayList<>();
        JSONObject memoryUpdates = new JSONObject();
        JSONObject rootObject = null;
        try {
            JSONArray values;
            if (clean.startsWith("[")) {
                values = new JSONArray(clean);
            } else {
                int start = clean.indexOf('{');
                int end = clean.lastIndexOf('}');
                if (start < 0 || end <= start) throw new IllegalArgumentException("JSON 객체 없음");
                rootObject = new JSONObject(clean.substring(start, end + 1));
                JSONObject updates = rootObject.optJSONObject("memory_updates");
                if (updates != null) memoryUpdates = updates;
                values = rootObject.optJSONArray("blocks");
                if (values == null) values = rootObject.optJSONArray("translations");
                if (values == null) values = new JSONArray();
            }
            for (int i = 0; i < values.length(); i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                String text = firstNonEmpty(item.optString("text", ""),
                        item.optString("translation", ""), item.optString("translated_text", "")).trim();
                if (text.isEmpty()) continue;

                double x = item.optDouble("x", Double.NaN);
                double y = item.optDouble("y", Double.NaN);
                double width = item.optDouble("width", Double.NaN);
                double height = item.optDouble("height", Double.NaN);
                JSONObject boxObject = item.optJSONObject("box");
                if (boxObject != null) {
                    x = boxObject.optDouble("x", x);
                    y = boxObject.optDouble("y", y);
                    width = boxObject.optDouble("width", width);
                    height = boxObject.optDouble("height", height);
                }
                JSONArray boxArray = item.optJSONArray("box");
                if (boxArray != null && boxArray.length() >= 4) {
                    x = boxArray.optDouble(0, x);
                    y = boxArray.optDouble(1, y);
                    width = boxArray.optDouble(2, width);
                    height = boxArray.optDouble(3, height);
                }
                if ((!isFinitePositive(width) || !isFinitePositive(height)
                        || Double.isNaN(x) || Double.isNaN(y))) {
                    double x1 = item.optDouble("x1", Double.NaN);
                    double y1 = item.optDouble("y1", Double.NaN);
                    double x2 = item.optDouble("x2", Double.NaN);
                    double y2 = item.optDouble("y2", Double.NaN);
                    if (!Double.isNaN(x1) && !Double.isNaN(y1)
                            && !Double.isNaN(x2) && !Double.isNaN(y2)
                            && x2 > x1 && y2 > y1) {
                        x = x1;
                        y = y1;
                        width = x2 - x1;
                        height = y2 - y1;
                    } else {
                        unplacedTexts.add(text);
                        continue;
                    }
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
        } catch (Exception ignored) {
            // Compatible endpoints may ignore JSON-only instructions. Fallback below keeps the translation usable.
        }

        if (!unplacedTexts.isEmpty()) {
            int count = unplacedTexts.size();
            float height = Math.min(220f, Math.max(90f, 760f / Math.max(1, count)));
            float startY = Math.max(40f, 500f - (count * height) * 0.5f);
            for (int i = 0; i < count; i++) {
                float y = clamp(startY + i * height, 20f, 900f);
                blocks.add(new TranslationBlock(unplacedTexts.get(i), 80f, y, 840f,
                        Math.min(height - 12f, 1000f - y)));
            }
        }

        if (blocks.isEmpty() && !clean.isEmpty()
                && !clean.equals("{\"blocks\":[]}") && !clean.equals("[]")) {
            String fallbackText = clean;
            try {
                JSONObject root = rootObject != null ? rootObject : new JSONObject(clean);
                if (root.optJSONArray("blocks") != null) fallbackText = "";
            } catch (Exception ignored) {}
            if (!fallbackText.isEmpty()) {
                blocks.add(new TranslationBlock(fallbackText, 80f, 690f, 840f, 240f));
            }
        }

        StringBuilder combined = new StringBuilder();
        for (TranslationBlock block : blocks) {
            if (combined.length() > 0) combined.append("\n\n");
            combined.append(block.text);
        }
        String text = combined.length() == 0 ? "[번역할 텍스트 없음]" : combined.toString();
        return new ParsedTranslation(text, blocks, memoryUpdates);
    }

    private static String stripCodeFence(String value) {
        String clean = value.trim();
        if (clean.startsWith("```")) {
            int firstNewline = clean.indexOf('\n');
            if (firstNewline >= 0) clean = clean.substring(firstNewline + 1);
            int closing = clean.lastIndexOf("```");
            if (closing >= 0) clean = clean.substring(0, closing);
        }
        return clean.trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
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
            if (max > 1600) {
                float ratio = 1600f / max;
                decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * ratio)),
                        Math.max(1, Math.round(size.getHeight() * ratio)));
            }
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
        Bitmap rgb = decoded;
        if (decoded.hasAlpha()) {
            rgb = Bitmap.createBitmap(decoded.getWidth(), decoded.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rgb);
            canvas.drawColor(Color.WHITE);
            canvas.drawBitmap(decoded, 0, 0, null);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!rgb.compress(Bitmap.CompressFormat.JPEG, 82, output)) {
            throw new IllegalStateException("이미지를 JPEG로 변환하지 못했습니다.");
        }
        if (rgb != decoded) rgb.recycle();
        decoded.recycle();
        return new ImagePayload("image/jpeg", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
    }

    private static JSONObject postJson(String endpoint, JSONObject body,
                                       Map<String, String> headers, RetryListener retryListener) throws Exception {
        return request(endpoint, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8), headers, retryListener, true);
    }

    private static JSONObject postForm(String endpoint, String body) throws Exception {
        return request(endpoint, "application/x-www-form-urlencoded; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8), new HashMap<>(), null, false);
    }

    private static JSONObject request(String endpoint, String contentType, byte[] body,
                                      Map<String, String> headers, RetryListener retryListener,
                                      boolean retryRateLimit) throws Exception {
        int retryAttempt = 0;
        while (true) {
            if (retryListener != null && retryListener.isCancelled()) {
                throw new TranslationCancelledException();
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(25_000);
            connection.setReadTimeout(90_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ImageLingo/0.9 Android");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int code = connection.getResponseCode();
            String retryAfterHeader = connection.getHeaderField("Retry-After");
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseText = readAll(stream);
            connection.disconnect();

            if (code >= 200 && code < 300) return new JSONObject(responseText);
            String errorMessage = extractError(responseText);
            if (code != 429 || !retryRateLimit) {
                throw new IllegalStateException("HTTP " + code + ": " + errorMessage);
            }
            if (isNonRecoverableQuotaError(errorMessage + " " + responseText)) {
                throw new RateLimitException(errorMessage, retryAttempt);
            }
            if (retryAttempt >= MAX_RATE_LIMIT_RETRIES) {
                throw new RateLimitException(errorMessage, retryAttempt);
            }

            long backoff = RATE_LIMIT_BACKOFF_MS[Math.min(retryAttempt, RATE_LIMIT_BACKOFF_MS.length - 1)];
            long retryAfter = Math.max(parseRetryAfterMillis(retryAfterHeader),
                    parseRetryDelayFromBody(responseText));
            long delayMs = Math.max(backoff, retryAfter);
            retryAttempt++;
            waitForRetry(delayMs, retryAttempt, retryListener, errorMessage);
        }
    }

    private static void waitForRetry(long delayMs, int attempt, RetryListener listener,
                                     String message) throws Exception {
        long remaining = Math.max(1_000L, Math.min(delayMs, 120_000L));
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
        String lower = message.toLowerCase();
        return lower.contains("insufficient_quota")
                || lower.contains("check your plan")
                || lower.contains("billing details")
                || lower.contains("credit balance")
                || lower.contains("payment required");
    }

    private static long parseRetryAfterMillis(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        try {
            double seconds = Double.parseDouble(value.trim());
            return Math.max(0L, Math.round(seconds * 1000d));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long parseRetryDelayFromBody(String body) {
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

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }

    private static String extractError(String body) {
        try {
            JSONObject json = new JSONObject(body);
            Object error = json.opt("error");
            if (error instanceof JSONObject) {
                String message = ((JSONObject) error).optString("message", "");
                if (!message.isEmpty()) return message;
            }
            if (error instanceof String) return (String) error;
            String message = json.optString("message", "");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {}
        String trimmed = body == null ? "" : body.trim();
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
