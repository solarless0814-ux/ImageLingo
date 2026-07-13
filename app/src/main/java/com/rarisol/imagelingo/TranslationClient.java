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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class TranslationClient {
    private static final String DEFAULT_PROMPT =
            "이미지에 보이는 한국어가 아닌 모든 글자를 자연스러운 한국어로 번역해 주세요. " +
            "만화, 게임, 비주얼노벨의 대사라면 화자 순서, 말투, 존댓말, 감정과 줄바꿈을 최대한 유지하세요. " +
            "효과음과 UI 문구도 빠뜨리지 마세요. 설명이나 원문 반복 없이 한국어 번역만 출력하세요. " +
            "번역할 글자가 없으면 [번역할 텍스트 없음]이라고 출력하세요.";

    private static final Map<String, CachedToken> TOKEN_CACHE = new HashMap<>();

    public static String translate(ContentResolver resolver, Uri imageUri, ApiSettings settings,
                                   SecureStore secrets) throws Exception {
        ImagePayload image = encodeImage(resolver, imageUri);
        String prompt = settings.customPrompt().isEmpty() ? DEFAULT_PROMPT : settings.customPrompt();
        switch (settings.provider()) {
            case ApiSettings.OPENAI:
                return translateOpenAi(image, prompt, settings, secrets.get("openai_key"));
            case ApiSettings.VERTEX:
                return translateVertex(image, prompt, settings, secrets.get("vertex_json"));
            case ApiSettings.GEMINI:
            default:
                return translateGemini(image, prompt, settings, secrets.get("gemini_key"));
        }
    }

    private static String translateOpenAi(ImagePayload image, String prompt, ApiSettings settings,
                                          String apiKey) throws Exception {
        require(apiKey, "OpenAI API 키가 설정되지 않았습니다.");
        require(settings.openAiModel(), "OpenAI 모델명이 비어 있습니다.");
        require(settings.openAiEndpoint(), "OpenAI 엔드포인트가 비어 있습니다.");

        JSONObject body = new JSONObject();
        body.put("model", settings.openAiModel());
        body.put("max_output_tokens", 3000);
        JSONArray input = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "input_text").put("text", prompt));
        content.put(new JSONObject()
                .put("type", "input_image")
                .put("detail", "high")
                .put("image_url", "data:" + image.mimeType + ";base64," + image.base64));
        message.put("content", content);
        input.put(message);
        body.put("input", input);

        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey.trim());
        JSONObject response = postJson(settings.openAiEndpoint(), body, headers);
        String direct = response.optString("output_text", "").trim();
        if (!direct.isEmpty()) return direct;

        StringBuilder text = new StringBuilder();
        JSONArray output = response.optJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.length(); i++) {
                JSONArray parts = output.optJSONObject(i) == null ? null
                        : output.optJSONObject(i).optJSONArray("content");
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
        if (text.length() == 0) throw new IllegalStateException("OpenAI 응답에서 번역문을 찾지 못했습니다.");
        return text.toString().trim();
    }

    private static String translateGemini(ImagePayload image, String prompt, ApiSettings settings,
                                          String apiKey) throws Exception {
        require(apiKey, "Gemini API 키가 설정되지 않았습니다.");
        require(settings.geminiModel(), "Gemini 모델명이 비어 있습니다.");
        String model = settings.geminiModel().replace("models/", "");
        String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":generateContent";

        JSONObject inline = new JSONObject()
                .put("mime_type", image.mimeType)
                .put("data", image.base64);
        JSONArray parts = new JSONArray()
                .put(new JSONObject().put("text", prompt))
                .put(new JSONObject().put("inline_data", inline));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("maxOutputTokens", 3000));
        Map<String, String> headers = new HashMap<>();
        headers.put("x-goog-api-key", apiKey.trim());
        JSONObject response = postJson(endpoint, body, headers);
        return parseGeminiText(response, "Gemini");
    }

    private static String translateVertex(ImagePayload image, String prompt, ApiSettings settings,
                                          String serviceJsonText) throws Exception {
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
                .put(new JSONObject().put("text", prompt))
                .put(new JSONObject().put("inlineData", inline));
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("maxOutputTokens", 3000));
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + token);
        JSONObject response = postJson(endpoint, body, headers);
        return parseGeminiText(response, "Vertex AI");
    }

    private static String parseGeminiText(JSONObject response, String provider) throws Exception {
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new IllegalStateException(provider + " 응답에 번역 후보가 없습니다.");
        }
        JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder result = new StringBuilder();
        if (parts != null) {
            for (int i = 0; i < parts.length(); i++) {
                String text = parts.getJSONObject(i).optString("text", "");
                if (!text.isEmpty()) {
                    if (result.length() > 0) result.append('\n');
                    result.append(text);
                }
            }
        }
        if (result.length() == 0) throw new IllegalStateException(provider + " 응답에서 번역문을 찾지 못했습니다.");
        return result.toString().trim();
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
            if (max > 2048) {
                float ratio = 2048f / max;
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
        if (!rgb.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
            throw new IllegalStateException("이미지를 JPEG로 변환하지 못했습니다.");
        }
        if (rgb != decoded) rgb.recycle();
        decoded.recycle();
        return new ImagePayload("image/jpeg", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
    }

    private static JSONObject postJson(String endpoint, JSONObject body,
                                       Map<String, String> headers) throws Exception {
        return request(endpoint, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8), headers);
    }

    private static JSONObject postForm(String endpoint, String body) throws Exception {
        return request(endpoint, "application/x-www-form-urlencoded; charset=utf-8",
                body.getBytes(StandardCharsets.UTF_8), new HashMap<>());
    }

    private static JSONObject request(String endpoint, String contentType, byte[] body,
                                      Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "ImageLingo/0.1 Android");
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            connection.setRequestProperty(entry.getKey(), entry.getValue());
        }
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String responseText = readAll(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code + ": " + extractError(responseText));
        }
        return new JSONObject(responseText);
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
