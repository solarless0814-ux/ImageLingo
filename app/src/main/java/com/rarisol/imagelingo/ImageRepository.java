package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ImageRepository {
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    public static final class ImageEntry {
        public final Uri uri;
        public final String name;
        public final long size;
        public final long lastModified;
        public ImageEntry(Uri uri, String name, long size, long lastModified) {
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.lastModified = lastModified;
        }
    }

    public static List<ImageEntry> listImages(ContentResolver resolver, Uri treeUri) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        List<ImageEntry> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("선택한 폴더를 읽을 수 없습니다.");
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            int modifiedCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idCol);
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                if (isImage(name, mime)) {
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    long size = sizeCol >= 0 && !cursor.isNull(sizeCol) ? cursor.getLong(sizeCol) : -1L;
                    long modified = modifiedCol >= 0 && !cursor.isNull(modifiedCol)
                            ? cursor.getLong(modifiedCol) : -1L;
                    result.add(new ImageEntry(uri, name == null ? "image" : name, size, modified));
                }
            }
        }
        result.sort((a, b) -> naturalCompare(a.name, b.name));
        return result;
    }

    public static Uri findSubdirectory(ContentResolver resolver, Uri treeUri, String name) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        return findChildDocument(resolver, rootDocument, name, DocumentsContract.Document.MIME_TYPE_DIR);
    }

    public static Uri ensureSubdirectory(ContentResolver resolver, Uri treeUri, String name) throws Exception {
        Uri existing = findSubdirectory(resolver, treeUri, name);
        if (existing != null) return existing;
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocument = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        Uri created = DocumentsContract.createDocument(
                resolver, rootDocument, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IllegalStateException("출력 폴더를 만들지 못했습니다: " + name);
        return created;
    }

    public static Map<String, Uri> listImageDocumentsByName(ContentResolver resolver,
                                                             Uri directoryUri) throws Exception {
        Map<String, Uri> result = new HashMap<>();
        if (directoryUri == null) return result;
        String parentId = DocumentsContract.getDocumentId(directoryUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, parentId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                if (name == null || !isImage(name, mime)) continue;
                if (sizeCol >= 0 && !cursor.isNull(sizeCol) && cursor.getLong(sizeCol) <= 0L) continue;
                String id = cursor.getString(idCol);
                Uri document = DocumentsContract.buildDocumentUriUsingTree(directoryUri, id);
                // ImageLingo outputs are PNGs. A matching name alone must never make a
                // zero-byte or interrupted write look like a completed translation.
                if (name.toLowerCase(Locale.ROOT).endsWith(".png")
                        && !hasPngSignature(resolver, document)) continue;
                result.put(name, document);
            }
        }
        return result;
    }


    public static Uri findChildDocumentByName(ContentResolver resolver, Uri parentDocumentUri,
                                              String displayName) throws Exception {
        return findChildDocument(resolver, parentDocumentUri, displayName, null);
    }

    public static String readTextDocument(ContentResolver resolver, Uri documentUri,
                                          int maxCharacters) throws Exception {
        StringBuilder result = new StringBuilder();
        try (InputStream input = resolver.openInputStream(documentUri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, read);
                if (result.length() > maxCharacters) {
                    throw new IllegalArgumentException("텍스트 파일이 너무 큽니다.");
                }
            }
        }
        return result.toString();
    }

    public static Uri saveTextDocument(ContentResolver resolver, Uri directoryUri,
                                       String displayName, String mimeType, String text) throws Exception {
        if (directoryUri == null) throw new IllegalArgumentException("directoryUri == null");
        Uri target = findChildDocument(resolver, directoryUri, displayName, null);
        if (target != null) {
            // Never delete the last known-good document merely because a provider
            // rejected one overwrite mode. The caller can surface the error and retry.
            overwriteTextDocument(resolver, target, text);
            return target;
        }
        target = DocumentsContract.createDocument(resolver, directoryUri,
                mimeType == null || mimeType.isEmpty() ? "text/plain" : mimeType, displayName);
        if (target == null) throw new IllegalStateException("텍스트 파일을 만들지 못했습니다: " + displayName);
        try {
            overwriteTextDocument(resolver, target, text);
            return target;
        } catch (Exception writeError) {
            deleteQuietly(resolver, target, writeError);
            throw writeError;
        }
    }

    /** Reuses a known SAF document URI, avoiding a directory query on every translated page. */
    static void overwriteTextDocument(ContentResolver resolver, Uri documentUri,
                                      String text) throws Exception {
        if (documentUri == null) throw new IllegalArgumentException("documentUri == null");
        try (OutputStream output = openTruncatingOutputStream(resolver, documentUri)) {
            output.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    public static Uri saveBitmap(ContentResolver resolver, Uri directoryUri,
                                 String displayName, Bitmap bitmap) throws Exception {
        if (directoryUri == null) throw new IllegalArgumentException("directoryUri == null");
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("bitmap 없음");
        Uri target = findChildDocument(resolver, directoryUri, displayName, null);
        if (target != null) {
            overwriteBitmapDocument(resolver, target, bitmap);
            verifyPngDocument(resolver, target);
            return target;
        }
        target = DocumentsContract.createDocument(resolver, directoryUri, "image/png", displayName);
        if (target == null) throw new IllegalStateException("출력 이미지를 만들지 못했습니다: " + displayName);
        try {
            overwriteBitmapDocument(resolver, target, bitmap);
            verifyPngDocument(resolver, target);
            return target;
        } catch (Exception writeError) {
            deleteQuietly(resolver, target, writeError);
            throw writeError;
        }
    }

    /** Saves with a caller-provided directory snapshot, avoiding another SAF directory scan. */
    public static Uri saveBitmapKnown(ContentResolver resolver, Uri directoryUri,
                                      String displayName, Bitmap bitmap,
                                      Uri knownDocumentUri) throws Exception {
        if (directoryUri == null) throw new IllegalArgumentException("directoryUri == null");
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("bitmap 없음");
        Uri target = knownDocumentUri;
        if (target != null) {
            overwriteBitmapDocument(resolver, target, bitmap);
            verifyPngDocument(resolver, target);
            return target;
        }
        target = DocumentsContract.createDocument(resolver, directoryUri, "image/png", displayName);
        if (target == null) throw new IllegalStateException("출력 이미지를 만들지 못했습니다: " + displayName);
        try {
            overwriteBitmapDocument(resolver, target, bitmap);
            verifyPngDocument(resolver, target);
            return target;
        } catch (Exception writeError) {
            deleteQuietly(resolver, target, writeError);
            throw writeError;
        }
    }

    private static void overwriteBitmapDocument(ContentResolver resolver, Uri documentUri,
                                                Bitmap bitmap) throws Exception {
        try (OutputStream output = openTruncatingOutputStream(resolver, documentUri)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("이미지를 PNG로 저장하지 못했습니다.");
            }
            output.flush();
        }
    }

    private static void deleteQuietly(ContentResolver resolver, Uri documentUri, Exception original) {
        try {
            if (!DocumentsContract.deleteDocument(resolver, documentUri)) {
                original.addSuppressed(new IllegalStateException("부분 저장 파일을 정리하지 못했습니다."));
            }
        } catch (Exception cleanupError) {
            original.addSuppressed(cleanupError);
        }
    }

    private static void verifyPngDocument(ContentResolver resolver, Uri documentUri) throws Exception {
        if (!hasPngSignature(resolver, documentUri)) {
            throw new IllegalStateException("저장된 PNG 파일 검증에 실패했습니다.");
        }
    }

    private static boolean hasPngSignature(ContentResolver resolver, Uri documentUri) {
        try (InputStream input = resolver.openInputStream(documentUri)) {
            if (input == null) return false;
            for (byte expected : PNG_SIGNATURE) {
                if (input.read() != (expected & 0xFF)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void verifyWritableDirectory(ContentResolver resolver, Uri directoryUri) throws Exception {
        String name = "ImageLingo_write_test_" + Long.toHexString(System.nanoTime()) + ".tmp";
        Uri probe = DocumentsContract.createDocument(resolver, directoryUri, "text/plain", name);
        if (probe == null) throw new IllegalStateException("출력 폴더에 파일을 만들 수 없습니다.");
        Exception failure = null;
        try {
            overwriteTextDocument(resolver, probe, "ImageLingo write test");
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            try {
                if (!DocumentsContract.deleteDocument(resolver, probe) && failure == null) {
                    throw new IllegalStateException("출력 폴더의 테스트 파일을 정리하지 못했습니다.");
                }
            } catch (Exception cleanupError) {
                if (failure != null) failure.addSuppressed(cleanupError);
                else throw cleanupError;
            }
        }
    }

    private static OutputStream openTruncatingOutputStream(ContentResolver resolver,
                                                            Uri documentUri) throws Exception {
        // "wt" requests explicit truncation. A few older document providers only accept "w",
        // so retain that compatible fallback without deleting and recreating the document.
        try {
            OutputStream output = resolver.openOutputStream(documentUri, "wt");
            if (output != null) return output;
        } catch (Exception ignored) {}
        OutputStream output = resolver.openOutputStream(documentUri, "w");
        if (output == null) throw new IllegalStateException("출력 스트림을 열 수 없습니다.");
        return output;
    }

    private static Uri findChildDocument(ContentResolver resolver, Uri parentDocumentUri,
                                         String displayName, String mimeFilter) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parentDocumentUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parentDocumentUri, parentId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return null;
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                if (displayName.equals(name) && (mimeFilter == null || mimeFilter.equals(mime))) {
                    String id = cursor.getString(idCol);
                    return DocumentsContract.buildDocumentUriUsingTree(parentDocumentUri, id);
                }
            }
        }
        return null;
    }

    private static boolean isImage(String name, String mime) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".gif") || n.endsWith(".bmp")
                || n.endsWith(".heic") || n.endsWith(".heif") || n.endsWith(".avif");
    }

    static int naturalCompare(String left, String right) {
        int i = 0, j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i), b = right.charAt(j);
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int startI = i, startJ = j;
                while (i < left.length() && left.charAt(i) == '0') i++;
                while (j < right.length() && right.charAt(j) == '0') j++;
                int numberI = i, numberJ = j;
                while (i < left.length() && Character.isDigit(left.charAt(i))) i++;
                while (j < right.length() && Character.isDigit(right.charAt(j))) j++;
                int lenI = i - numberI, lenJ = j - numberJ;
                if (lenI != lenJ) return Integer.compare(lenI, lenJ);
                int cmp = left.regionMatches(true, numberI, right, numberJ, lenI) ? 0
                        : left.substring(numberI, i).compareToIgnoreCase(right.substring(numberJ, j));
                if (cmp != 0) return cmp;
                int zerosCmp = Integer.compare(numberI - startI, numberJ - startJ);
                if (zerosCmp != 0) return zerosCmp;
            } else {
                int ca = Character.toLowerCase(a), cb = Character.toLowerCase(b);
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return Integer.compare(left.length(), right.length());
    }

    private ImageRepository() {}
}
