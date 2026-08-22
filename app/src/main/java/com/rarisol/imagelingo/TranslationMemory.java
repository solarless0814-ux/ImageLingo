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
    private static final int MAX_RECENT_TRANSLATION_CHARS = 700;
    private static final int MAX_CONTEXT_CHARS = 6000;
    private static final int MAX_MEMORY_FILE_CHARS = 256_000;
    private static final int MAX_CHARACTER_ENTRIES = 30;
    private static final int MAX_TERM_ENTRIES = 60;
    private static final int MAX_HONORIFIC_ENTRIES = 60;
    private static final int MAX_NESTED_FIELDS = 3;
    private static final int MAX_KEY_CHARS = 60;
    private static final int MAX_VALUE_CHARS = 160;

    private final JSONObject root;
    private Uri documentUri;
    private String cachedContextPrompt;

    private TranslationMemory(JSONObject value, Uri documentUri) {
        root = value == null ? createEmptyRoot() : normalize(value);
        this.documentUri = documentUri;
    }

    public static TranslationMemory load(ContentResolver resolver, Uri outputDirectory) {
        if (outputDirectory == null) return new TranslationMemory(createEmptyRoot(), null);
        try {
            Uri memoryUri = ImageRepository.findChildDocumentByName(resolver, outputDirectory, FILE_NAME);
            if (memoryUri == null) return new TranslationMemory(createEmptyRoot(), null);
            String text = ImageRepository.readTextDocument(resolver, memoryUri, MAX_MEMORY_FILE_CHARS);
            return new TranslationMemory(new JSONObject(text), memoryUri);
        } catch (Exception ignored) {
            return new TranslationMemory(createEmptyRoot(), null);
        }
    }

    public synchronized void save(ContentResolver resolver, Uri outputDirectory) throws Exception {
        root.put("updated_at", System.currentTimeMillis());
        String serialized = root.toString(2);
        if (serialized.length() > MAX_MEMORY_FILE_CHARS) {
            throw new IllegalStateException("문맥 메모리가 저장 한도를 초과했습니다.");
        }
        if (documentUri != null) {
            try {
                ImageRepository.overwriteTextDocument(resolver, documentUri, serialized);
                return;
            } catch (Exception ignored) {
                // The file may have been removed outside the app; resolve/create it again below.
                documentUri = null;
            }
        }
        documentUri = ImageRepository.saveTextDocument(resolver, outputDirectory, FILE_NAME,
                "application/json", serialized);
    }

    public synchronized String buildContextPrompt() {
        if (cachedContextPrompt != null) return cachedContextPrompt;
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
                compact.put("characters", contextObject(context.optJSONObject("characters"), 3));
                compact.put("terms", contextObject(context.optJSONObject("terms"), 6));
                compact.put("honorifics", contextObject(context.optJSONObject("honorifics"), 6));
                json = compact.toString();
            } catch (Exception ignored) {}
        }
        if (json.length() > MAX_CONTEXT_CHARS) {
            try {
                JSONObject minimal = new JSONObject();
                minimal.put("characters", contextObject(context.optJSONObject("characters"), 1));
                minimal.put("terms", contextObject(context.optJSONObject("terms"), 2));
                minimal.put("honorifics", contextObject(context.optJSONObject("honorifics"), 2));
                json = minimal.toString();
            } catch (Exception ignored) {
                json = "{}";
            }
        }

        cachedContextPrompt = "같은 폴더의 확정 번역 규칙입니다. 이름, 고유명사, 존댓말/반말, 호칭을 반드시 그대로 유지하세요. "
                + "새로 확실해진 정보만 memory_updates에 추가하고 기존 표기를 임의로 바꾸지 마세요.\n"
                + json;
        return cachedContextPrompt;
    }

    public synchronized void applyResult(String pageName, TranslationClient.TranslationResult result) {
        if (result == null) return;
        JSONObject updates = result.memoryUpdates;
        if (updates != null) {
            mergeObject(root.optJSONObject("characters"), updates.optJSONObject("characters"),
                    MAX_CHARACTER_ENTRIES, 0);
            mergeObject(root.optJSONObject("terms"), updates.optJSONObject("terms"),
                    MAX_TERM_ENTRIES, 0);
            mergeObject(root.optJSONObject("honorifics"), updates.optJSONObject("honorifics"),
                    MAX_HONORIFIC_ENTRIES, 0);
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
        cachedContextPrompt = null;
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
        if (jsonText != null && jsonText.length() > MAX_MEMORY_FILE_CHARS) {
            throw new IllegalArgumentException("문맥 JSON이 너무 큽니다.");
        }
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
        cachedContextPrompt = null;
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
            value.put("characters", sanitizeObject(value.optJSONObject("characters"),
                    MAX_CHARACTER_ENTRIES, 0, MAX_VALUE_CHARS));
            value.put("terms", sanitizeObject(value.optJSONObject("terms"),
                    MAX_TERM_ENTRIES, 0, MAX_VALUE_CHARS));
            value.put("honorifics", sanitizeObject(value.optJSONObject("honorifics"),
                    MAX_HONORIFIC_ENTRIES, 0, MAX_VALUE_CHARS));
            JSONArray recent = value.optJSONArray("recent_pages");
            if (recent == null) {
                recent = new JSONArray();
                value.put("recent_pages", recent);
            }
            for (int i = 0; i < recent.length(); i++) {
                JSONObject page = recent.optJSONObject(i);
                if (page != null) {
                    page.remove("summary");
                    page.put("page", limit(page.optString("page", ""), MAX_KEY_CHARS));
                    page.put("translation", limit(page.optString("translation", ""),
                            MAX_RECENT_TRANSLATION_CHARS));
                }
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

    private static JSONObject contextObject(JSONObject source, int maxEntries) {
        return sanitizeObject(source, maxEntries, 0, 80);
    }

    private static JSONObject sanitizeObject(JSONObject source, int maxEntries, int depth,
                                             int valueLimit) {
        JSONObject result = new JSONObject();
        if (source == null || maxEntries <= 0) return result;
        Iterator<String> keys = source.keys();
        int count = 0;
        while (keys.hasNext() && count < maxEntries) {
            String rawKey = keys.next();
            String key = limit(rawKey, MAX_KEY_CHARS);
            if (key.isEmpty()) continue;
            Object incoming = source.opt(rawKey);
            try {
                if (incoming instanceof JSONObject && depth < 2) {
                    int nestedLimit = valueLimit <= 80 ? 4 : MAX_NESTED_FIELDS;
                    result.put(key, sanitizeObject((JSONObject) incoming, nestedLimit,
                            depth + 1, valueLimit));
                } else if (incoming != null && incoming != JSONObject.NULL
                        && !(incoming instanceof JSONArray)) {
                    result.put(key, limit(String.valueOf(incoming), valueLimit));
                }
            }
            catch (Exception ignored) {}
            count++;
        }
        return result;
    }

    private static void mergeObject(JSONObject destination, JSONObject updates,
                                    int maxEntries, int depth) {
        if (destination == null || updates == null) return;
        Iterator<String> keys = updates.keys();
        while (keys.hasNext()) {
            String rawKey = keys.next();
            String key = limit(rawKey, MAX_KEY_CHARS);
            if (key.isEmpty()) continue;
            Object incoming = updates.opt(rawKey);
            try {
                if (!destination.has(key) && destination.length() >= maxEntries) continue;
                if (incoming instanceof JSONObject && depth < 2) {
                    JSONObject nested = destination.optJSONObject(key);
                    if (nested == null) {
                        nested = new JSONObject();
                        destination.put(key, nested);
                    }
                    mergeObject(nested, (JSONObject) incoming, MAX_NESTED_FIELDS, depth + 1);
                } else if (incoming != null && incoming != JSONObject.NULL
                        && !(incoming instanceof JSONArray)) {
                    destination.put(key, limit(String.valueOf(incoming), MAX_VALUE_CHARS));
                }
            } catch (Exception ignored) {}
        }
    }

    private static int objectLength(JSONObject object) {
        return object == null ? 0 : object.length();
    }

    private static String limit(String value, int max) {
        if (value == null || max <= 0) return "";
        String clean = value.trim();
        return clean.length() <= max ? clean
                : (max == 1 ? "…" : clean.substring(0, max - 1) + "…");
    }
}
