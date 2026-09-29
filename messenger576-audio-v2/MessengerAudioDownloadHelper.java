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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MessengerAudioDownloadHelper {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final String AUDIO_ATTACHMENT =
            "com.facebook.messaging.attachments.AudioAttachmentData";
    private static final int MAX_DEPTH = 6;
    private static final int MAX_OBJECTS = 350;

    private MessengerAudioDownloadHelper() {}

    public static boolean hasAudioAttachment(Object first, Object second) {
        return findAudioUri(first, second) != null;
    }

    public static Uri findAudioUri(Object first, Object second) {
        try {
            IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
            int[] budget = new int[]{MAX_OBJECTS};
            Uri uri = scan(first, 0, seen, budget);
            if (uri != null) return uri;
            return scan(second, 0, seen, budget);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Uri scan(Object value, int depth,
                            IdentityHashMap<Object, Boolean> seen, int[] budget) {
        if (value == null || depth > MAX_DEPTH || budget[0]-- <= 0) return null;
        if (value instanceof Uri) return null;
        if (seen.put(value, Boolean.TRUE) != null) return null;

        Class<?> cls = value.getClass();
        String name = cls.getName();

        if (AUDIO_ATTACHMENT.equals(name)) {
            Uri uri = firstUriField(value, cls);
            if (uri != null) return uri;
        }

        if (cls.isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Uri uri = scan(Array.get(value, i), depth + 1, seen, budget);
                if (uri != null) return uri;
            }
            return null;
        }

        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                Uri uri = scan(item, depth + 1, seen, budget);
                if (uri != null) return uri;
            }
            return null;
        }

        if (value instanceof Map<?, ?>) {
            for (Object item : ((Map<?, ?>) value).values()) {
                Uri uri = scan(item, depth + 1, seen, budget);
                if (uri != null) return uri;
            }
            return null;
        }

        if (!(name.startsWith("com.facebook.") || name.startsWith("X."))) return null;

        for (Class<?> current = cls;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Uri uri = scan(field.get(value), depth + 1, seen, budget);
                    if (uri != null) return uri;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Uri firstUriField(Object value, Class<?> cls) {
        for (Class<?> current = cls;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (!Uri.class.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        Object uri = field.get(value);
                        if (uri instanceof Uri) return (Uri) uri;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return null;
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
                        new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) +
                        extension;

                saveToDownloads(appContext, source, fileName, mime);
                toast(appContext, "Áudio guardado em Download/Messenger Audio");
            } catch (Throwable error) {
                toast(appContext, "Não foi possível descarregar o áudio");
            }
        });
    }

    private static void saveToDownloads(Context context, Uri source,
                                        String fileName, String mime) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Messenger Audio");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IllegalStateException("MediaStore insert returned null");

            boolean success = false;
            try (InputStream in = openSource(context, source);
                 OutputStream out = resolver.openOutputStream(destination, "w")) {
                if (in == null || out == null) throw new IllegalStateException("Could not open streams");
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
        } else {
            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File directory = new File(base, "Messenger Audio");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create directory");
            }
            File destination = new File(directory, fileName);
            try (InputStream in = openSource(context, source);
                 OutputStream out = new FileOutputStream(destination)) {
                if (in == null) throw new IllegalStateException("Could not open source");
                copy(in, out);
            }
            MediaScannerConnection.scanFile(context,
                    new String[]{destination.getAbsolutePath()},
                    new String[]{mime}, null);
        }
    }

    private static InputStream openSource(Context context, Uri source) throws Exception {
        if ("file".equalsIgnoreCase(source.getScheme())) {
            if (source.getPath() == null) throw new IllegalStateException("No file path");
            return new FileInputStream(new File(source.getPath()));
        }
        return context.getContentResolver().openInputStream(source);
    }

    private static String detectMimeType(Context context, Uri source) {
        try {
            String type = context.getContentResolver().getType(source);
            if (type != null && type.startsWith("audio/")) return type;
        } catch (Throwable ignored) {}

        MediaExtractor extractor = new MediaExtractor();
        try {
            if ("file".equalsIgnoreCase(source.getScheme()) && source.getPath() != null) {
                extractor.setDataSource(source.getPath());
            } else {
                extractor.setDataSource(context, source, null);
            }
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String type = format.getString(MediaFormat.KEY_MIME);
                if (type != null && type.startsWith("audio/")) return type;
            }
        } catch (Throwable ignored) {
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
        }
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
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
