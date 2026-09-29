package app.template.extension.extension;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Standard-Android part of the Messenger "Download audio messages" patch.
 *
 * It deliberately has no compile-time dependency on Facebook/Messenger classes.
 * Messenger passes its LX/1FU object as Object; LX/1FU extends FutureTask, so it
 * can safely be consumed as java.util.concurrent.Future<?>.
 */
public final class MessengerAudioDownloadHelper {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private MessengerAudioDownloadHelper() {
    }

    public static void saveFuture(Context context, Object futureObject) {
        final Context appContext = context.getApplicationContext();

        IO.execute(() -> {
            try {
                if (!(futureObject instanceof Future<?>)) {
                    throw new IllegalStateException("Messenger audio result is not a Future");
                }

                Object value = ((Future<?>) futureObject).get();
                if (!(value instanceof Uri)) {
                    throw new IllegalStateException("Messenger audio Future did not return a Uri");
                }

                Uri source = (Uri) value;
                String mime = detectMimeType(appContext, source);
                String extension = extensionForMime(mime);
                String fileName = "Messenger_audio_" +
                        new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                                .format(new Date()) +
                        extension;

                saveToDownloads(appContext, source, fileName, mime);
                toast(appContext, "Áudio guardado em Download/Messenger Audio");
            } catch (Throwable error) {
                toast(appContext, "Não foi possível descarregar o áudio");
            }
        });
    }

    public static void showMissingAudio(Context context) {
        toast(context.getApplicationContext(), "Não foi possível localizar o áudio desta mensagem");
    }

    private static void saveToDownloads(
            Context context,
            Uri source,
            String fileName,
            String mime
    ) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, fileName, mime);
        } else {
            saveLegacy(context, source, fileName, mime);
        }
    }

    private static void saveWithMediaStore(
            Context context,
            Uri source,
            String fileName,
            String mime
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Messenger Audio"
        );
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri destination = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
        );

        if (destination == null) {
            throw new IllegalStateException("MediaStore insert returned null");
        }

        boolean success = false;
        try (InputStream in = openSource(context, source);
             OutputStream out = resolver.openOutputStream(destination, "w")) {
            if (in == null || out == null) {
                throw new IllegalStateException("Could not open audio streams");
            }

            copy(in, out);
            success = true;
        } finally {
            if (success) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(destination, ready, null, null);
            } else {
                resolver.delete(destination, null, null);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void saveLegacy(
            Context context,
            Uri source,
            String fileName,
            String mime
    ) throws Exception {
        File base = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
        );
        File directory = new File(base, "Messenger Audio");

        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create Messenger Audio directory");
        }

        File destination = new File(directory, fileName);

        try (InputStream in = openSource(context, source);
             OutputStream out = new FileOutputStream(destination)) {
            if (in == null) {
                throw new IllegalStateException("Could not open source audio");
            }
            copy(in, out);
        }

        MediaScannerConnection.scanFile(
                context,
                new String[]{destination.getAbsolutePath()},
                new String[]{mime},
                null
        );
    }

    private static InputStream openSource(Context context, Uri source) throws Exception {
        if ("file".equalsIgnoreCase(source.getScheme())) {
            String path = source.getPath();
            if (path == null) {
                throw new IllegalStateException("file:// Uri has no path");
            }
            return new FileInputStream(new File(path));
        }

        return context.getContentResolver().openInputStream(source);
    }

    private static String detectMimeType(Context context, Uri source) {
        try {
            String resolverType = context.getContentResolver().getType(source);
            if (resolverType != null && resolverType.startsWith("audio/")) {
                return resolverType;
            }
        } catch (Throwable ignored) {
        }

        MediaExtractor extractor = new MediaExtractor();
        try {
            if ("file".equalsIgnoreCase(source.getScheme()) && source.getPath() != null) {
                extractor.setDataSource(source.getPath());
            } else {
                extractor.setDataSource(context, source, null);
            }

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    return mime;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }

        // Messenger voice clips commonly use an audio container handled by Android.
        // audio/mp4 is a conservative fallback when the temporary file has no extension.
        return "audio/mp4";
    }

    private static String extensionForMime(String mime) {
        if (mime == null) return ".m4a";

        String value = mime.toLowerCase(Locale.US);
        if (value.contains("ogg")) return ".ogg";
        if (value.contains("opus")) return ".opus";
        if (value.contains("mpeg")) return ".mp3";
        if (value.contains("aac")) return ".aac";
        if (value.contains("wav")) return ".wav";
        if (value.contains("flac")) return ".flac";
        if (value.contains("3gpp")) return ".3gp";
        return ".m4a";
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        );
    }
}
