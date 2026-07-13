package com.rarisol.imagelingo;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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
