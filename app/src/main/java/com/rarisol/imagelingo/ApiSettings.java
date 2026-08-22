package com.rarisol.imagelingo;

import android.content.Context;
import android.content.SharedPreferences;

public final class ApiSettings {
    public static final String OPENAI = "OPENAI";
    public static final String GEMINI = "GEMINI";
    public static final String VERTEX = "VERTEX";

    /** OpenAI's regular, low-latency processing tier. */
    public static final String SERVICE_TIER_STANDARD = "STANDARD";
    /** OpenAI Flex processing. It can be cheaper, but may wait when capacity is busy. */
    public static final String SERVICE_TIER_FLEX = "FLEX";

    private static final String PREFS = "api_settings";
    private final SharedPreferences prefs;

    public ApiSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String provider() { return prefs.getString("provider", GEMINI); }
    public String openAiModel() { return prefs.getString("openai_model", "gpt-5.6-luna"); }
    public String openAiEndpoint() { return prefs.getString("openai_endpoint", "https://api.openai.com/v1/responses"); }
    public String geminiModel() { return prefs.getString("gemini_model", "gemini-3.7-flash"); }
    public String vertexModel() { return prefs.getString("vertex_model", "gemini-3.7-flash"); }
    public String vertexLocation() { return prefs.getString("vertex_location", "global"); }
    public String vertexProject() { return prefs.getString("vertex_project", ""); }

    /**
     * User-facing service tier. The API value for Standard is deliberately kept out of the UI:
     * OpenAI calls it {@code default}, while the product-facing name is Standard.
     */
    public String serviceTier() {
        return normalizeServiceTier(prefs.getString("service_tier", SERVICE_TIER_STANDARD));
    }

    public boolean usesFlexTier() {
        return SERVICE_TIER_FLEX.equals(serviceTier());
    }

    public void setServiceTier(String serviceTier) {
        prefs.edit().putString("service_tier", normalizeServiceTier(serviceTier)).apply();
    }

    public String systemPrompt() {
        String current = prefs.getString("system_prompt", "");
        if (current != null && !current.isEmpty()) return current;
        // 0.1 버전의 번역 지시문을 자동으로 이어받습니다.
        return prefs.getString("custom_prompt", "");
    }

    public void save(String provider, String openAiModel, String openAiEndpoint,
                     String geminiModel, String vertexModel, String vertexLocation,
                     String vertexProject, String systemPrompt) {
        prefs.edit()
                .putString("provider", provider)
                .putString("openai_model", openAiModel.trim())
                .putString("openai_endpoint", openAiEndpoint.trim())
                .putString("gemini_model", geminiModel.trim())
                .putString("vertex_model", vertexModel.trim())
                .putString("vertex_location", vertexLocation.trim())
                .putString("vertex_project", vertexProject.trim())
                .putString("system_prompt", systemPrompt.trim())
                .remove("custom_prompt")
                .apply();
    }

    private static String normalizeServiceTier(String value) {
        if (value == null) return SERVICE_TIER_STANDARD;
        String clean = value.trim();
        if (SERVICE_TIER_FLEX.equalsIgnoreCase(clean) || "flex".equalsIgnoreCase(clean)) {
            return SERVICE_TIER_FLEX;
        }
        // Also migrates an earlier/raw OpenAI value ("default") to the UI-facing Standard value.
        return SERVICE_TIER_STANDARD;
    }
}
