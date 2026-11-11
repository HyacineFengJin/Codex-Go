package dev.codex.go.testdriver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/** Independent, non-exported source with genuine temporary read grants. Emulator only. */
public final class ShareProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return uri.getLastPathSegment().equals("image") ? "image/png" : "text/plain"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String name = uri.getLastPathSegment();
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{name.equals("image") ? "photo.png" : "same-name.txt", name.equals("oversized") ? 67108865L : null});
        return cursor;
    }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!java.util.Arrays.asList("one", "two", "image", "oversized").contains(name) || !mode.equals("r")) throw new java.io.FileNotFoundException();
        File file = new File(getContext().getFilesDir(), "share-"+name);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(name.equals("image") ? android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=", 0) : ("independent-source-"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException error) { throw new java.io.FileNotFoundException(error.toString()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    static void send(android.content.Context context, Bundle args) {
        String kind = args.getString("kind", "text");
        Intent intent = new Intent(kind.equals("multiple") ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND).setType(kind.equals("text") ? "text/plain" : "*/*").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (args.containsKey("text")) intent.putExtra(Intent.EXTRA_TEXT, args.getString("text"));
        ArrayList<Uri> uris = new ArrayList<>();
        if (!kind.equals("text")) {
            if (kind.equals("multiple")) { uris.add(Uri.parse("content://dev.codex.go.sharefixture.shares/one")); uris.add(Uri.parse("content://dev.codex.go.sharefixture.shares/two")); uris.add(Uri.parse("content://dev.codex.go.sharefixture.shares/image")); }
            else uris.add(Uri.parse(kind.equals("unsafe") ? "file:///data/data/dev.codex.go/files/codex/auth.json" : "content://dev.codex.go.sharefixture.shares/"+kind));
            ClipData clip = ClipData.newUri(context.getContentResolver(), "test-share", uris.get(0));
            for (int i=1;i<uris.size();i++) clip.addItem(new ClipData.Item(uris.get(i)));
            intent.setClipData(clip);
            if (kind.equals("multiple")) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            else if (!Boolean.parseBoolean(args.getString("clipOnly", "false"))) intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        }
        if (Boolean.parseBoolean(args.getString("chooser", "false"))) intent = Intent.createChooser(intent, "Share test fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        else intent.setClassName("dev.codex.go", "dev.codex.go.ShareReceiverActivity");
        context.startActivity(intent);
    }
}
