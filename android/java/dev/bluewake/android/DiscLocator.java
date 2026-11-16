package dev.bluewake.android;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Where the disc image the player picked really is, as a file path. With "All files access" the game reads the
 * disc where it is (BLUEWAKE_DISC) instead of from a 1.4 GB copy, and the game's reader (dvd_open_image, in a
 * library BlueWake does not own) takes a plain path, not a content URI.
 *
 * What the picker hands over is a document URI, and the path is made from its id:
 * <ul>
 * <li>external storage ("primary:Download/x.iso", an SD card's "1A2B-3C4D:x.iso", "home:x.iso"): the volume's
 *     mount point and the rest;</li>
 * <li>downloads ("raw:/storage/.../x.iso" as it is; a number, the older provider's row; "msf:1000593048",
 *     MediaStore's own Downloads): MediaStore's _data column, which is the path;</li>
 * <li>media ("image:12", "video:12", "audio:12", the others): MediaStore's _data column;</li>
 * <li>a file: URI as it is, and any other content URI by its own _data column.</li>
 * </ul>
 * Anything else (a cloud drive, a virtual file) has no path this process can open, and so does a path the
 * process may not read (no All files access): null, and FirstRunActivity copies the image as it always did.
 */
final class DiscLocator {
    private static final String EXTERNAL_STORAGE = "com.android.externalstorage.documents";
    private static final String DOWNLOADS = "com.android.providers.downloads.documents";
    private static final String MEDIA = "com.android.providers.media.documents";

    private DiscLocator() {}

    /** The picked document's file path, readable by this process, or null. */
    static String realPath(Context context, Uri uri) {
        if (uri == null)
            return null;
        String path = null;
        try {
            if ("file".equals(uri.getScheme()))
                path = uri.getPath();
            else if (DocumentsContract.isDocumentUri(context, uri))
                path = documentPath(context, uri);
            else if ("content".equals(uri.getScheme()))
                path = dataColumn(context, uri);
        } catch (RuntimeException e) {
            path = null;  // a provider that refuses, or an id it does not mean
        }
        return openable(path) ? path : null;
    }

    private static String documentPath(Context context, Uri uri) {
        String authority = uri.getAuthority();
        String id = DocumentsContract.getDocumentId(uri);
        if (EXTERNAL_STORAGE.equals(authority))
            return externalStoragePath(id, Environment.getExternalStorageDirectory().getPath());
        if (DOWNLOADS.equals(authority))
            return downloadsPath(context, id);
        if (MEDIA.equals(authority))
            return mediaPath(context, id);
        return null;
    }

    /**
     * The external storage provider's document id as a path: "primary:Download/x.iso" is under the primary
     * volume's root (/storage/emulated/0), "home:x.iso" under its Documents, and anything before the colon
     * that looks like a volume id (an SD card's "1A2B-3C4D") is a volume mounted at /storage/&lt;id&gt;.
     */
    static String externalStoragePath(String id, String primaryRoot) {
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon <= 0)
            return null;
        String volume = id.substring(0, colon);
        String rest = id.substring(colon + 1);
        String root;
        if (volume.equals("primary"))
            root = primaryRoot;
        else if (volume.equals("home"))
            root = primaryRoot + "/Documents";
        else if (volume.matches("[A-Za-z0-9-]+"))
            root = "/storage/" + volume;
        else
            return null;
        return clean(rest.isEmpty() ? root : root + "/" + rest);
    }

    private static String downloadsPath(Context context, String id) {
        if (id == null)
            return null;
        if (id.startsWith("raw:"))
            return clean(id.substring(4));
        if (id.startsWith("msf:"))
            return mediaStorePath(context, id.substring(4));
        if (!digits(id))
            return null;
        long row = Long.parseLong(id);
        for (String base : new String[] {"content://downloads/public_downloads", "content://downloads/all_downloads"}) {
            String path = dataColumn(context, ContentUris.withAppendedId(Uri.parse(base), row));
            if (path != null)
                return path;
        }
        return mediaStorePath(context, id);
    }

    /** MediaStore's Downloads, then its Files, by row: both hold a download, under the same _id. */
    private static String mediaStorePath(Context context, String row) {
        if (!digits(row))
            return null;
        long id = Long.parseLong(row);
        String path = dataColumn(context, ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id));
        return path != null ? path : dataColumn(context, MediaStore.Files.getContentUri("external", id));
    }

    private static String mediaPath(Context context, String id) {
        int colon = id == null ? -1 : id.indexOf(':');
        if (colon <= 0 || !digits(id.substring(colon + 1)))
            return null;
        Uri table;
        switch (id.substring(0, colon)) {
            case "image":
                table = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                break;
            case "video":
                table = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                break;
            case "audio":
                table = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                break;
            default:
                table = MediaStore.Files.getContentUri("external");
                break;
        }
        return dataColumn(context, ContentUris.withAppendedId(table, Long.parseLong(id.substring(colon + 1))));
    }

    /** The _data column of a row: its file path. Null for no such row or column, or one not ours to read. */
    @SuppressWarnings("deprecation")  // _data is deprecated and still the only way to the path
    private static String dataColumn(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[] {MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
                if (column >= 0)
                    return cursor.getString(column);
            }
        } catch (RuntimeException e) {
            // no such column, or not ours to read
        }
        return null;
    }

    /** True when the process can open the file: it is there, and the permission to read it is too. */
    static boolean openable(String path) {
        if (path == null || !path.startsWith("/"))
            return false;
        File file = new File(path);
        if (!file.isFile())
            return false;
        try (FileInputStream in = new FileInputStream(file)) {
            in.read();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** The path without empty or "." parts, or null if it climbs ("..") or names nothing. */
    private static String clean(String path) {
        if (path == null)
            return null;
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals("."))
                continue;
            if (part.equals(".."))
                return null;
            out.append('/').append(part);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static boolean digits(String text) {
        if (text == null || text.isEmpty() || text.length() > 18)
            return false;
        for (int i = 0; i < text.length(); i++)
            if (text.charAt(i) < '0' || text.charAt(i) > '9')
                return false;
        return true;
    }
}
