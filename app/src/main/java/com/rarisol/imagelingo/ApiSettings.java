package com.rarisol.imagelingo;

import android.content.Context;
import android.content.SharedPreferences;

public final class ApiSettings {
    public static final String OPENAI = "OPENAI";
    public static final String GEMINI = "GEMINI";
    public static final String VERTEX = "VERTEX";

    private static final String PREFS = "api_settings";
    private final SharedPreferences prefs;

    public ApiSettings(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String provider() { return prefs.getString("provider", GEMINI); }
    public String openAiModel() { return prefs.getString("openai_model", "gpt-4.1-mini"); }
    public String openAiEndpoint() { return prefs.getString("openai_endpoint", "https://api.openai.com/v1/responses"); }
    public String geminiModel() { return prefs.getString("gemini_model", "gemini-2.5-flash"); }
    public String vertexModel() { return prefs.getString("vertex_model", "gemini-2.5-flash"); }
    public String vertexLocation() { return prefs.getString("vertex_location", "global"); }
    public String vertexProject() { return prefs.getString("vertex_project", ""); }
    public String customPrompt() { return prefs.getString("custom_prompt", ""); }

    public void save(String provider, String openAiModel, String openAiEndpoint,
                     String geminiModel, String vertexModel, String vertexLocation,
                     String vertexProject, String customPrompt) {
        prefs.edit()
                .putString("provider", provider)
                .putString("openai_model", openAiModel.trim())
                .putString("openai_endpoint", openAiEndpoint.trim())
                .putString("gemini_model", geminiModel.trim())
                .putString("vertex_model", vertexModel.trim())
                .putString("vertex_location", vertexLocation.trim())
                .putString("vertex_project", vertexProject.trim())
                .putString("custom_prompt", customPrompt.trim())
                .apply();
    }
}
