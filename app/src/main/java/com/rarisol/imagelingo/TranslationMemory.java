package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/** Folder-local continuity memory for names, terms, honorifics and one recent page. */
public final class TranslationMemory {
    public static final String FILE_NAME = "translation_memory.json";
    private static final int MAX_RECENT_PAGES = 1;
    private static final int MAX_RECENT_TRANSLATION_CHARS = 1000;
    private static final int MAX_CONTEXT_CHARS = 8000;

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
            context.put("characters", safeObject(root.optJSONObject("characters")));
            context.put("terms", safeObject(root.optJSONObject("terms")));
            context.put("honorifics", safeObject(root.optJSONObject("honorifics")));
            context.put("recent_pages", safeArray(root.optJSONArray("recent_pages")));
        } catch (Exception ignored) {}

        String json = context.toString();
        if (json.length() > MAX_CONTEXT_CHARS) {
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
        if (json.length() > MAX_CONTEXT_CHARS) {
            try {
                JSONObject compact = new JSONObject();
                compact.put("characters", limitedObject(context.optJSONObject("characters"), 45));
                compact.put("terms", limitedObject(context.optJSONObject("terms"), 80));
                compact.put("honorifics", limitedObject(context.optJSONObject("honorifics"), 80));
                json = compact.toString();
            } catch (Exception ignored) {}
        }

        return "같은 폴더의 확정 번역 규칙입니다. 이름, 고유명사, 존댓말/반말, 호칭을 반드시 그대로 유지하세요. "
                + "새로 확실해진 정보만 memory_updates에 추가하고 기존 표기를 임의로 바꾸지 마세요.\n"
                + json;
    }

    public synchronized void applyResult(String pageName, TranslationClient.TranslationResult result) {
        if (result == null) return;
        JSONObject updates = result.memoryUpdates;
        if (updates != null) {
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
        JSONArray recent = root.optJSONArray("recent_pages");
        int count = recent == null ? 0 : recent.length();
        return count == 0 ? "최근 번역 문맥 없음" : "최근 번역 문맥 " + count + "페이지 유지";
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
            value.put("version", 2);
            value.put("characters", new JSONObject());
            value.put("terms", new JSONObject());
            value.put("honorifics", new JSONObject());
            value.put("recent_pages", new JSONArray());
        } catch (Exception ignored) {}
        return value;
    }

    private static JSONObject normalize(JSONObject value) {
        try {
            value.put("version", 2);
            value.remove("story_summary");
            value.remove("updated_story_summary");
            if (value.optJSONObject("characters") == null) value.put("characters", new JSONObject());
            if (value.optJSONObject("terms") == null) value.put("terms", new JSONObject());
            if (value.optJSONObject("honorifics") == null) value.put("honorifics", new JSONObject());
            JSONArray recent = value.optJSONArray("recent_pages");
            if (recent == null) {
                recent = new JSONArray();
                value.put("recent_pages", recent);
            }
            for (int i = 0; i < recent.length(); i++) {
                JSONObject page = recent.optJSONObject(i);
                if (page != null) page.remove("summary");
            }
            while (recent.length() > MAX_RECENT_PAGES) recent.remove(0);
        } catch (Exception ignored) {}
        return value;
    }

    private static JSONObject safeObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try { return new JSONObject(value.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONArray safeArray(JSONArray value) {
        if (value == null) return new JSONArray();
        try { return new JSONArray(value.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static JSONObject limitedObject(JSONObject source, int maxEntries) {
        JSONObject result = new JSONObject();
        if (source == null || maxEntries <= 0) return result;
        Iterator<String> keys = source.keys();
        int count = 0;
        while (keys.hasNext() && count < maxEntries) {
            String key = keys.next();
            try { result.put(key, source.opt(key)); }
            catch (Exception ignored) {}
            count++;
        }
        return result;
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

    private static String limit(String value, int max) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
