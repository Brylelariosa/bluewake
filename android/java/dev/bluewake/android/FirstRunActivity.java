package dev.bluewake.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * The launcher activity: runs before SDL and the game, so a missing game
 * folder can be put right without the game's own lifecycle getting in the way.
 * If main.dol and the 415 rels are present it forwards straight to the game
 * (BlueWakeActivity). Otherwise it shows the first-run screen, the Android
 * counterpart of the iPhone and iPad app's (apple/ios/src/first_run.m): the
 * player picks their own GZLE01 disc image and main.dol + rels/ are extracted
 * from it on the device (disc_import.c, through Shell.nativeDiscPrepare). The
 * translated game code is already in the app, so once the files exist the game
 * starts and this screen never returns.
 *
 * The disc image is read where the player keeps it when it can be: with "All
 * files access" (Android 11 and newer, asked for here) the picked document's real
 * path (DiscLocator) is saved in the settings and becomes BLUEWAKE_DISC at
 * launch, so the 1.4 GB is not copied and not stored twice. The saved path is
 * checked on every launch (there, readable, a whole disc); one that fails is
 * forgotten and the picker returns. When the permission is refused or the
 * document has no path, the image is copied into the game folder as it always was.
 *
 * This screen is also where the session log starts, because the disc has to be
 * read before the game (and its menu) exists: a crash here would otherwise
 * leave the player with nothing to send. android_entry.c names the log in
 * logs/session-in-progress when a session starts and removes that file when the
 * session ends, so one left behind means the last session died, and this screen
 * offers to share its log.
 */
public final class FirstRunActivity extends Activity {
    static final int REQUEST_DISC = 0x4B57;  // "BW"
    static final int REQUEST_ALL_FILES = 0x4B58;
    /**
     * Room kept aside for the extraction that follows the copy: main.dol and the
     * 415 rels take about 11 MB, and the import writes them beside the old ones
     * before swapping them in.
     */
    private static final long IMPORT_HEADROOM = 32L * 1024 * 1024;

    private Settings settings;
    private boolean askedAllFilesAccess;  // the All files access screen is offered once a run; a second tap goes on
    private File dataDir;
    private File gameDir;
    private File isoPath;
    private String problem;     // why the game cannot start, when the files are there but the disc is not usable
    private File previousLog;   // the last session's log, when it did not end properly
    private TextView status;
    private Button choose;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        File external = getExternalFilesDir(null);
        dataDir = external != null ? external : getFilesDir();
        gameDir = new File(dataDir, "game");
        isoPath = new File(gameDir, "GZLE01.iso");
        settings = new Settings(this);
        previousLog = previousSessionLog();

        // The disc image read where the player keeps it (BLUEWAKE_DISC), checked on every launch: one that is
        // gone, unreadable or no longer a whole image is forgotten, and the picker comes back.
        String saved = settings.discPath();
        boolean makeFiles = false;
        if (saved != null) {
            problem = savedDiscProblem(saved);
            if (problem != null) {
                Shell.log("[first-run] the saved disc image is no longer usable: " + problem);
                settings.clearDiscPath();
                saved = null;
            } else if (ready()) {
                forward();
                return;
            } else {
                makeFiles = true;  // the files made from it are gone: made again from the same image
            }
        }
        if (saved == null && ready()) {
            // The files are there, from the copy in the game folder. The image they were made from has to be
            // whole as well: the game reads the disc directly, so an image that ends early kills it before
            // the menu (disc_import.c).
            String copyProblem = discProblem();
            if (copyProblem == null) {
                forward();
                return;
            }
            if (problem == null)
                problem = copyProblem;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(48, 48, 48, 48);
        layout.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("BlueWake");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("To play, BlueWake needs your own disc image of\nThe Legend of Zelda: The Wind Waker (GameCube USA).\nChoose it once and the game files are made on this device.");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sub.setMargins(0, 24, 0, 0);
        layout.addView(subtitle, sub);

        status = new TextView(this);
        status.setText(problem != null ? problem : "");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, 24, 0, 0);
        layout.addView(status, sp);

        choose = new Button(this);
        choose.setText("Choose Disc Image");
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, 24, 0, 0);
        bp.gravity = Gravity.CENTER_HORIZONTAL;
        layout.addView(choose, bp);
        choose.setOnClickListener(v -> pickDisc());

        if (previousLog != null) {
            TextView note = new TextView(this);
            note.setText("The last session ended unexpectedly.\nIts log is the best record of what went wrong.");
            note.setTextColor(Color.LTGRAY);
            note.setTextSize(14);
            note.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            np.setMargins(0, 24, 0, 0);
            layout.addView(note, np);

            Button share = new Button(this);
            share.setText("Share Its Log");
            LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            shp.setMargins(0, 12, 0, 0);
            shp.gravity = Gravity.CENTER_HORIZONTAL;
            layout.addView(share, shp);
            share.setOnClickListener(v -> shareLog(previousLog));
        }

        setContentView(layout);
        if (makeFiles)
            useDiscInPlace(saved);
    }

    /**
     * Null when files/game/GZLE01.iso is a whole disc image, else the reason. The
     * answer comes from the same check the game's own start makes (disc_import.c,
     * through Shell.nativeDiscCheck), so the screen and the game cannot disagree:
     * an image that ends before the files it names is what leaves a player with a
     * crash instead of a message.
     */
    private String discProblem() {
        if (!isoPath.isFile())
            return "The disc image is missing. Choose it again.";
        return checkDisc(isoPath.getAbsolutePath());
    }

    /** Null when the disc image saved as BLUEWAKE_DISC is there, readable and whole, else the reason. */
    private String savedDiscProblem(String path) {
        if (!new File(path).isFile())
            return "The disc image is no longer where you chose it from. Choose it again.";
        if (!DiscLocator.openable(path))
            return "BlueWake can no longer read the disc image where you chose it from"
                    + (hasAllFilesAccess() ? "" : " (All files access is off)") + ". Choose it again.";
        return checkDisc(path);
    }

    private static String checkDisc(String path) {
        try {
            // Load libmain.so (SDL is inside it) so the check is there;
            // JNI_OnLoad only registers methods, it does not start SDL.
            System.loadLibrary("main");
            return Shell.nativeDiscCheck(path);
        } catch (UnsatisfiedLinkError e) {
            return "The disc image could not be checked.";
        }
    }

    /** "All files access": Android 11 and newer, where the player turns it on in Settings. */
    private static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
    }

    private boolean ready() {
        File main = new File(gameDir, "main.dol");
        File rels = new File(gameDir, "rels");
        String[] files = rels.list();
        return main.isFile() && rels.isDirectory() && files != null && files.length == 415;
    }

    private void forward() {
        Intent intent = new Intent(this, BlueWakeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void pickDisc() {
        // Reading the disc where it is takes All files access: its screen first, once a run, then the picker.
        // Left off (or no such screen), the picker goes on and the image is copied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess() && !askedAllFilesAccess) {
            askedAllFilesAccess = true;
            if (askAllFilesAccess())
                return;
        }
        openPicker();
    }

    private boolean askAllFilesAccess() {
        setStatus("To play from your disc image where it is, without copying its 1.4 GB, BlueWake needs "
                + "\u201CAll files access\u201D. Turn it on for BlueWake, then come back. Left off, the "
                + "disc image is copied into BlueWake's folder instead.");
        try {
            startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())), REQUEST_ALL_FILES);
            return true;
        } catch (ActivityNotFoundException e) {
            try {
                startActivityForResult(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_ALL_FILES);
                return true;
            } catch (ActivityNotFoundException ignored) {
                return false;
            }
        }
    }

    private void openPicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        try {
            startActivityForResult(pick, REQUEST_DISC);
        } catch (Exception e) {
            setStatus("No file picker on this device.");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_ALL_FILES) {
            // The settings screen returns nothing: the permission itself says what the player chose.
            if (hasAllFilesAccess()) {
                setStatus("");
                openPicker();
            } else {
                setStatus("All files access is off, so the disc image will be copied into BlueWake's folder "
                        + "instead (about 1.4 GB free is needed). Choose the disc image to go on.");
            }
            return;
        }
        if (request != REQUEST_DISC || result != Activity.RESULT_OK || data == null)
            return;
        Uri uri = data.getData();
        if (uri == null)
            return;
        chooseDisc(uri);
    }

    /**
     * The picked document's real path, if the permission is on and there is one this process can open: the
     * disc is then read where it is. Otherwise it is copied, as it always was.
     */
    private void chooseDisc(final Uri uri) {
        if (choose != null)
            choose.setEnabled(false);
        setStatus("Looking for the disc image\u2026");
        new Thread(() -> {
            final String real = hasAllFilesAccess() ? DiscLocator.realPath(this, uri) : null;
            runOnUiThread(() -> {
                if (real != null)
                    useDiscInPlace(real);
                else
                    importDisc(uri);
            });
        }).start();
    }

    /**
     * Makes main.dol and rels/ from a disc image where it is, with no copy of it: only about 32 MB are needed
     * (IMPORT_HEADROOM), not the 1.4 GB the image takes. The path is saved, and becomes BLUEWAKE_DISC at launch
     * (Settings.launchEnvironment), once the image has passed the same completeness check the game's start makes
     * and the files are made from it.
     */
    private void useDiscInPlace(final String path) {
        if (choose != null)
            choose.setEnabled(false);
        setStatus("Checking the disc image\u2026");
        new Thread(() -> {
            String error;
            try {
                System.loadLibrary("main");
                Shell.nativeSessionLogStart(dataDir.getAbsolutePath());
                Shell.log("[first-run] using the disc image where it is: " + path + ", "
                        + dataDir.getUsableSpace() + " bytes free");
                error = Shell.nativeDiscCheck(path);
                if (error == null) {
                    if (!gameDir.isDirectory() && !gameDir.mkdirs())
                        throw new IOException("Could not create the game folder.");
                    requireSpace(IMPORT_HEADROOM, "to make the game files");
                    runOnUiThread(() -> setStatus("Making the game files\u2026"));
                    error = Shell.nativeDiscPrepare(path, gameDir.getAbsolutePath());
                }
                Shell.log("[first-run] the disc import " + (error == null ? "finished" : "failed: " + error));
            } catch (IOException e) {
                error = e.getMessage() != null ? e.getMessage() : "The game files could not be made.";
                Shell.log("[first-run] " + error);
            } catch (UnsatisfiedLinkError e) {
                error = "The native library could not be loaded.";
                Shell.log("[first-run] " + error);
            }
            final String result = error;
            runOnUiThread(() -> {
                if (result == null) {
                    settings.setDiscPath(path);
                    setStatus("Done.");
                    forward();
                } else {
                    setStatus(result);
                    if (choose != null)
                        choose.setEnabled(true);
                }
            });
        }).start();
    }

    private void setStatus(String text) {
        if (status != null)
            status.setText(text);
    }

    private void importDisc(final Uri uri) {
        if (choose != null)
            choose.setEnabled(false);
        setStatus("Copying the disc image…");
        new Thread(() -> {
            String error = null;
            // Copied beside the real name and renamed at the end, so a copy that
            // an interrupted session or a kill stops halfway leaves no image for
            // the game to read (#476).
            final File part = new File(gameDir, "GZLE01.iso.part");
            try {
                // Load libmain.so (SDL is inside it) so the extractor is there;
                // JNI_OnLoad only registers methods, it does not start SDL.
                System.loadLibrary("main");
                // The session log starts here, before the disc is read: a crash
                // while importing it leaves a log the next start can share, and
                // the game's own Share Session Log is unreachable until the
                // files exist.
                Shell.nativeSessionLogStart(dataDir.getAbsolutePath());
                // What the provider says about the file it handed over: the size
                // the copy is checked against, and what the free-space check is
                // made of (#476).
                final long declared = declaredSize(uri);
                final String name = displayName(uri);
                Shell.log("[first-run] importing " + uri + (name != null ? " (" + name + ")" : "")
                        + ", " + (declared > 0 ? declared + " bytes" : "size not reported")
                        + ", " + dataDir.getUsableSpace() + " bytes free");
                if (!gameDir.isDirectory() && !gameDir.mkdirs())
                    throw new IOException("Could not create the game folder.");
                // Room for the image and for the files made from it, before a
                // byte is copied: a phone short of 4.7 GB is told so here, not
                // when the copy or the extraction runs out (#476).
                requireSpace(declared > 0 ? declared + IMPORT_HEADROOM : IMPORT_HEADROOM,
                        "to import the disc image");
                part.delete();
                long copied;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(part)) {
                    if (in == null)
                        throw new IOException("The disc image could not be opened.");
                    copied = copy(in, out);
                }
                Shell.log("[first-run] copied " + copied + " bytes of "
                        + (declared > 0 ? declared : copied) + ", " + dataDir.getUsableSpace()
                        + " bytes free");
                // A provider may end the stream early (a cloud-backed file, a
                // cancelled download), which leaves a short image: it passes the
                // disc's own header check and kills the game when the game reads
                // past its end, so it is refused here instead (#476).
                if (declared > 0 && copied != declared)
                    throw new IOException("The copy stopped at " + formatSize(copied) + " of "
                            + formatSize(declared) + ". If the image is on a cloud drive, download"
                            + " it to this device first, then choose it again.");
                if (!finishCopy(part, isoPath))
                    throw new IOException("Could not finish writing the disc image into "
                            + gameDir.getName() + ".");
                requireSpace(IMPORT_HEADROOM, "to make the game files");
                error = Shell.nativeDiscPrepare(isoPath.getAbsolutePath(), gameDir.getAbsolutePath());
                Shell.log("[first-run] the disc import "
                        + (error == null ? "finished" : "failed: " + error));
            } catch (IOException e) {
                error = e.getMessage() != null ? e.getMessage() : "The disc image could not be copied.";
                Shell.log("[first-run] " + error);
            } catch (UnsatisfiedLinkError e) {
                error = "The native library could not be loaded.";
                Shell.log("[first-run] " + error);
            } finally {
                // A copy that never finished leaves nothing for the game to read.
                if (part.isFile())
                    part.delete();
            }
            final String result = error;
            runOnUiThread(() -> {
                if (result == null) {
                    settings.clearDiscPath();  // the copy is the disc now
                    setStatus("Done.");
                    forward();
                } else {
                    setStatus(result);
                    if (choose != null)
                        choose.setEnabled(true);
                }
            });
        }).start();
    }

    /**
     * Refuses an import that cannot fit, naming both sizes. Silent when the
     * filesystem does not report free space: the write itself is the only answer
     * then (#476).
     */
    private void requireSpace(long needed, String what) throws IOException {
        final long usable = dataDir.getUsableSpace();
        if (usable < 0 || usable >= needed)
            return;
        throw new IOException("BlueWake needs about " + formatSize(needed) + " free " + what
                + ", and " + formatSize(usable) + " is available. Free some space and choose it"
                + " again.");
    }

    /** The size the picked file's provider reports, or -1 when it does not say. */
    private long declaredSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int column = c.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !c.isNull(column))
                    return c.getLong(column);
            }
        } catch (RuntimeException e) {
            // A provider that cannot answer is not an error: the log says the size
            // was not reported, and the copy is then only checked against itself.
        }
        return -1;
    }

    /** The picked file's name as the provider shows it, for the log, or null. */
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                final int column = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !c.isNull(column))
                    return c.getString(column);
            }
        } catch (RuntimeException e) {
            // As above: the name is only for the log.
        }
        return null;
    }

    /** "4.4 GB", "229 MB": the sizes a file manager shows. */
    static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024)
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024 * 1024)
            return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%d KB", (bytes + 1023) / 1024);
    }

    /**
     * Renames the finished copy over the real name, so an image the player is
     * replacing is only gone once the new one is whole. Some external-storage
     * providers refuse a rename onto an existing file, which is why the old image
     * is dropped before a second attempt (#476).
     */
    private static boolean finishCopy(File part, File target) {
        if (part.renameTo(target))
            return true;
        return target.delete() && part.renameTo(target);
    }

    private static long copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
            total += n;
        }
        return total;
    }

    /**
     * The log the last session left when it did not end properly: android_entry.c
     * names it in logs/session-in-progress at the start of a session and removes
     * that file when the session ends, so a file here with a log beside it means
     * the app died before its own menu could share it.
     */
    private File previousSessionLog() {
        File logs = new File(dataDir, "logs");
        File marker = new File(logs, "session-in-progress");
        if (!marker.isFile())
            return null;
        String name = null;
        try (BufferedReader in = new BufferedReader(new FileReader(marker))) {
            name = in.readLine();
        } catch (IOException e) {
            return null;
        }
        if (name == null || !LogProvider.isSessionLog(name))
            return null;
        File log = new File(logs, name);
        return log.isFile() ? log : null;
    }

    private void shareLog(File log) {
        Uri uri = LogProvider.uriFor(this, log);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(ClipData.newRawUri(log.getName(), uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, "Share Session Log"));
        } catch (ActivityNotFoundException e) {
            setStatus("This device has no app to share the log with.");
        }
    }
}
