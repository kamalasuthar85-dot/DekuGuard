package dev.dekuguard;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Appends violation lines to a file on a background thread so the server tick never waits on disk. */
public class FileLog {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final File file;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DekuGuard-Log");
        t.setDaemon(true);
        return t;
    });

    public FileLog(File file) {
        this.file = file;
    }

    public void log(String line) {
        final String out = LocalDateTime.now().format(FMT) + " " + line + System.lineSeparator();
        try {
            executor.execute(() -> {
                try {
                    Files.writeString(file.toPath(), out, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                    // logging must never break the server
                }
            });
        } catch (RuntimeException ignored) {
            // executor already shut down
        }
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
