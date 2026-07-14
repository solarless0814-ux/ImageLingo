package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** Folder-local continuity memory for names, terms, honorifics and recent context. */
public final class TranslationMemory {
    public static final String FILE_NAME = "translation_memory.json";
    private static final int MAX_RECENT_PAGES = 3;
    private static final int MAX_RECENT_TRANSLATION_CHARS = 2400;
    private static final int MAX_CONTEXT_CHARS = 18000;

    private final JSONObject root;

    private TranslationMemory(JSONObject value) {
        root = value == null ? createEmptyRoot() : normalize(value);
    }

    public static TranslationMemory load(ContentResolver resolver, Uri outputDirectory) {
        if (outputDirectory == null) return new TranslationMemory(createEmptyRoot());
        try {
            Uri memoryUri = ImageRepository.findChildDocumentByName(resolver, outputDirectory, FILE_NAME);
            if (memoryUri == null) return new TranslationMemory(createEmptyRoot());
            String text = ImageRepository.readTextDocument(resolver, memoryUri, 1_000_000);
            return new TranslationMemory(new JSONObject(text));
        } catch (Exception ignored) {
            return new TranslationMemory(createEmptyRoot());
        }
    }

    public synchronized void save(ContentResolver resolver, Uri outputDirectory) throws Exception {
        root.put("updated_at", System.currentTimeMillis());
        ImageRepository.saveTextDocument(resolver, outputDirectory, FILE_NAME,
                "application/json", root.toString(2));
    }

    public synchronized String buildContextPrompt() {
        JSONObject context = new JSONObject();
        try {
            context.put("story_summary", root.optString("story_summary", ""));
            context.put("characters", root.optJSONObject("characters") == null
                    ? new JSONObject() : root.optJSONObject("characters"));
            context.put("terms", root.optJSONObject("terms") == null
                    ? new JSONObject() : root.optJSONObject("terms"));
            context.put("honorifics", root.optJSONObject("honorifics") == null
                    ? new JSONObject() : root.optJSONObject("honorifics"));
            context.put("recent_pages", root.optJSONArray("recent_pages") == null
                    ? new JSONArray() : root.optJSONArray("recent_pages"));
        } catch (Exception ignored) {}

        String json = context.toString();
        if (json.length() > MAX_CONTEXT_CHARS) {
            // Recent translations are the largest field. Keep dictionaries and summary first.
            try {
                JSONArray recent = context.optJSONArray("recent_pages");
                if (recent != null) {
                    for (int i = 0; i < recent.length(); i++) {
                        JSONObject page = recent.optJSONObject(i);
                        if (page != null) page.remove("translation");
                    }
                }
                json = context.toString();
            } catch (Exception ignored) {}
        }
        if (json.length() > MAX_CONTEXT_CHARS) json = json.substring(0, MAX_CONTEXT_CHARS);

        return "아래는 같은 폴더의 이전 페이지에서 확정된 번역 문맥입니다. "
                + "이 정보를 최우선으로 지켜 이름, 고유명사, 존댓말/반말, 호칭을 통일하세요. "
                + "기존 표기를 임의로 바꾸지 말고, 새 정보만 memory_updates에 추가하세요.\n"
                + json;
    }

    public synchronized void applyResult(String pageName, TranslationClient.TranslationResult result) {
        if (result == null) return;
        JSONObject updates = result.memoryUpdates;
        if (updates != null) {
            String summary = firstNonEmpty(
                    updates.optString("story_summary", ""),
                    updates.optString("updated_story_summary", ""));
            if (!summary.isEmpty()) {
                try { root.put("story_summary", limit(summary, 5000)); } catch (Exception ignored) {}
            }
            mergeObject(root.optJSONObject("characters"), updates.optJSONObject("characters"));
            mergeObject(root.optJSONObject("terms"), updates.optJSONObject("terms"));
            mergeObject(root.optJSONObject("honorifics"), updates.optJSONObject("honorifics"));
        }

        JSONArray recent = root.optJSONArray("recent_pages");
        if (recent == null) {
            recent = new JSONArray();
            try { root.put("recent_pages", recent); } catch (Exception ignored) {}
        }
        JSONObject page = new JSONObject();
        try {
            page.put("page", pageName == null ? "" : pageName);
            page.put("summary", limit(result.pageSummary, 1000));
            page.put("translation", limit(result.text, MAX_RECENT_TRANSLATION_CHARS));
            recent.put(page);
            while (recent.length() > MAX_RECENT_PAGES) recent.remove(0);
        } catch (Exception ignored) {}
    }

    public synchronized int characterCount() {
        return objectLength(root.optJSONObject("characters"));
    }

    public synchronized int termCount() {
        return objectLength(root.optJSONObject("terms"));
    }

    public synchronized int honorificCount() {
        return objectLength(root.optJSONObject("honorifics"));
    }

    public synchronized String summaryPreview() {
        String summary = root.optString("story_summary", "").trim();
        return summary.isEmpty() ? "아직 누적 줄거리 없음" : limit(summary, 240);
    }

    public synchronized JSONObject copyRoot() {
        try { return new JSONObject(root.toString()); }
        catch (Exception ignored) { return createEmptyRoot(); }
    }

    public synchronized void replaceFromJson(String jsonText) throws Exception {
        JSONObject replacement = normalize(new JSONObject(jsonText == null ? "{}" : jsonText));
        JSONArray names = replacement.names();
        JSONArray oldNames = root.names();
        if (oldNames != null) {
            for (int i = 0; i < oldNames.length(); i++) root.remove(oldNames.optString(i));
        }
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i);
                root.put(name, replacement.opt(name));
            }
        }
        normalize(root);
    }

    public static void reset(ContentResolver resolver, Uri outputDirectory) throws Exception {
        ImageRepository.saveTextDocument(resolver, outputDirectory, FILE_NAME,
                "application/json", createEmptyRoot().toString(2));
    }

    private static JSONObject createEmptyRoot() {
        JSONObject value = new JSONObject();
        try {
            value.put("version", 1);
            value.put("story_summary", "");
            value.put("characters", new JSONObject());
            value.put("terms", new JSONObject());
            value.put("honorifics", new JSONObject());
            value.put("recent_pages", new JSONArray());
        } catch (Exception ignored) {}
        return value;
    }

    private static JSONObject normalize(JSONObject value) {
        try {
            if (!value.has("version")) value.put("version", 1);
            if (!value.has("story_summary")) value.put("story_summary", "");
            if (value.optJSONObject("characters") == null) value.put("characters", new JSONObject());
            if (value.optJSONObject("terms") == null) value.put("terms", new JSONObject());
            if (value.optJSONObject("honorifics") == null) value.put("honorifics", new JSONObject());
            if (value.optJSONArray("recent_pages") == null) value.put("recent_pages", new JSONArray());
        } catch (Exception ignored) {}
        return value;
    }

    private static void mergeObject(JSONObject destination, JSONObject updates) {
        if (destination == null || updates == null) return;
        Iterator<String> keys = updates.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object incoming = updates.opt(key);
            try {
                if (incoming instanceof JSONObject && destination.optJSONObject(key) != null) {
                    mergeObject(destination.optJSONObject(key), (JSONObject) incoming);
                } else if (incoming != null && incoming != JSONObject.NULL) {
                    destination.put(key, incoming);
                }
            } catch (Exception ignored) {}
        }
    }

    private static int objectLength(JSONObject object) {
        return object == null ? 0 : object.length();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String limit(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
