package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ImageRepository {
    public static final class ImageEntry {
        public final Uri uri;
        public final String name;
        public ImageEntry(Uri uri, String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    public static List<ImageEntry> listImages(ContentResolver resolver, Uri treeUri) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeId);
        String[] columns = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        List<ImageEntry> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("선택한 폴더를 읽을 수 없습니다.");
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idCol);
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                if (isImage(name, mime)) {
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                    result.add(new ImageEntry(uri, name == null ? "image" : name));
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
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) return result;
            int idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String name = cursor.getString(nameCol);
                String mime = cursor.getString(mimeCol);
                if (name == null || !isImage(name, mime)) continue;
                String id = cursor.getString(idCol);
                result.put(name, DocumentsContract.buildDocumentUriUsingTree(directoryUri, id));
            }
        }
        return result;
    }


    public static Uri findChildDocumentByName(ContentResolver resolver, Uri parentDocumentUri,
                                              String displayName) throws Exception {
        return findChildDocument(resolver, parentDocumentUri, displayName, null);
    }

    public static Uri saveBitmap(ContentResolver resolver, Uri directoryUri,
                                 String displayName, Bitmap bitmap) throws Exception {
        if (directoryUri == null) throw new IllegalArgumentException("directoryUri == null");
        if (bitmap == null || bitmap.isRecycled()) throw new IllegalArgumentException("bitmap 없음");
        Uri existing = findChildDocument(resolver, directoryUri, displayName, null);
        if (existing != null) {
            try {
                DocumentsContract.deleteDocument(resolver, existing);
            } catch (Exception ignored) {}
        }
        Uri created = DocumentsContract.createDocument(resolver, directoryUri, "image/png", displayName);
        if (created == null) throw new IllegalStateException("출력 이미지를 만들지 못했습니다: " + displayName);
        try (OutputStream output = resolver.openOutputStream(created, "w")) {
            if (output == null) throw new IllegalStateException("출력 스트림을 열 수 없습니다.");
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new IllegalStateException("이미지를 PNG로 저장하지 못했습니다.");
            }
            output.flush();
        }
        return created;
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

    private static int naturalCompare(String left, String right) {
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
