package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Folder-local provenance for deciding whether a translated PNG is still current. */
final class TranslationManifest {
    static final String FILE_NAME = "translation_manifest.json";
    private static final int VERSION = 1;
    private static final int MAX_FILE_CHARACTERS = 4_000_000;
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_RESULT_CHARACTERS = 8_000;

    private final JSONObject root;
    private final JSONObject entries;
    private Uri documentUri;

    private TranslationManifest(JSONObject root, Uri documentUri) {
        this.root = normalize(root);
        this.entries = this.root.optJSONObject("entries");
        this.documentUri = documentUri;
    }

    static TranslationManifest empty() {
        return new TranslationManifest(null, null);
    }

    static TranslationManifest load(ContentResolver resolver, Uri outputDirectory) {
        if (outputDirectory == null) return empty();
        try {
            Uri uri = ImageRepository.findChildDocumentByName(resolver, outputDirectory, FILE_NAME);
            if (uri == null) return empty();
            String text = ImageRepository.readTextDocument(resolver, uri, MAX_FILE_CHARACTERS);
            return new TranslationManifest(new JSONObject(text), uri);
        } catch (Exception ignored) {
            // A missing/corrupt manifest means "not current"; it must never cause a stale skip.
            return empty();
        }
    }

    synchronized boolean isCurrent(ImageRepository.ImageEntry source, String outputName,
                                   String fingerprint) {
        JSONObject item = entries.optJSONObject(key(source));
        return item != null
                && safe(outputName).equals(item.optString("output_name", ""))
                && safe(fingerprint).equals(item.optString("fingerprint", ""));
    }

    synchronized String resultText(ImageRepository.ImageEntry source, String fingerprint) {
        JSONObject item = entries.optJSONObject(key(source));
        if (item == null || !safe(fingerprint).equals(item.optString("fingerprint", ""))) return "";
        return item.optString("translation_text", "");
    }

    synchronized void record(ImageRepository.ImageEntry source, String outputName,
                             String fingerprint, String translationText) throws Exception {
        JSONObject item = new JSONObject();
        item.put("source_name", safe(source == null ? "" : source.name));
        item.put("source_uri", safe(source == null || source.uri == null ? "" : source.uri.toString()));
        item.put("source_size", source == null ? -1L : source.size);
        item.put("source_last_modified", source == null ? -1L : source.lastModified);
        item.put("output_name", safe(outputName));
        item.put("fingerprint", safe(fingerprint));
        item.put("translation_text", limit(translationText, MAX_RESULT_CHARACTERS));
        item.put("updated_at", System.currentTimeMillis());
        entries.put(key(source), item);
        pruneToEntryLimit();
    }

    synchronized void remove(ImageRepository.ImageEntry source) {
        entries.remove(key(source));
    }

    synchronized void save(ContentResolver resolver, Uri outputDirectory) throws Exception {
        root.put("version", VERSION);
        root.put("updated_at", System.currentTimeMillis());
        String serialized = root.toString(2);
        while (serialized.length() > MAX_FILE_CHARACTERS && entries.length() > 0) {
            removeOldest(Math.max(1, entries.length() / 8));
            serialized = root.toString(2);
        }
        if (serialized.length() > MAX_FILE_CHARACTERS) {
            throw new IllegalStateException("번역 manifest가 저장 한도를 초과했습니다.");
        }
        if (documentUri != null) {
            try {
                ImageRepository.overwriteTextDocument(resolver, documentUri, serialized);
                return;
            } catch (Exception ignored) {
                documentUri = null;
            }
        }
        documentUri = ImageRepository.saveTextDocument(resolver, outputDirectory, FILE_NAME,
                "application/json", serialized);
    }

    private void pruneToEntryLimit() {
        int excess = entries.length() - MAX_ENTRIES;
        if (excess > 0) removeOldest(excess);
    }

    private void removeOldest(int count) {
        List<EntryAge> ages = new ArrayList<>();
        Iterator<String> keys = entries.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject value = entries.optJSONObject(key);
            ages.add(new EntryAge(key, value == null ? 0L : value.optLong("updated_at", 0L)));
        }
        ages.sort(Comparator.comparingLong((EntryAge value) -> value.updatedAt)
                .thenComparing(value -> value.key));
        for (int i = 0; i < count && i < ages.size(); i++) entries.remove(ages.get(i).key);
    }

    private static JSONObject normalize(JSONObject value) {
        JSONObject normalized = new JSONObject();
        JSONObject normalizedEntries = new JSONObject();
        try {
            normalized.put("version", VERSION);
            normalized.put("entries", normalizedEntries);
            if (value == null || value.optInt("version", -1) != VERSION) return normalized;
            JSONObject sourceEntries = value.optJSONObject("entries");
            if (sourceEntries == null) return normalized;
            Iterator<String> keys = sourceEntries.keys();
            int count = 0;
            while (keys.hasNext() && count < MAX_ENTRIES) {
                String key = keys.next();
                JSONObject item = sourceEntries.optJSONObject(key);
                if (key.length() == 64 && item != null
                        && !item.optString("output_name", "").isEmpty()
                        && item.optString("fingerprint", "").length() == 64) {
                    JSONObject clean = new JSONObject(item.toString());
                    clean.put("translation_text", limit(clean.optString("translation_text", ""),
                            MAX_RESULT_CHARACTERS));
                    normalizedEntries.put(key, clean);
                    count++;
                }
            }
        } catch (Exception ignored) {}
        return normalized;
    }

    private static String key(ImageRepository.ImageEntry source) {
        String uri = source == null || source.uri == null ? "" : source.uri.toString();
        return OutputFingerprint.sha256(uri);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String limit(String value, int max) {
        String clean = safe(value);
        if (clean.length() <= max) return clean;
        return clean.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static final class EntryAge {
        final String key;
        final long updatedAt;

        EntryAge(String key, long updatedAt) {
            this.key = key;
            this.updatedAt = updatedAt;
        }
    }
}
