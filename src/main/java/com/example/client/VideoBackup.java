package com.example.client;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** File operations are independent of the game so recovery behavior can be tested. */
final class VideoBackup {
    private VideoBackup() {}
    static Properties read(Path path) throws IOException {
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) { values.load(reader); }
        return values;
    }
    static void saveOriginal(Path path, Properties values) throws IOException {
        if (Files.exists(path)) return;
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), "safaia-video-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp)) {
                values.store(writer, "Original Minecraft video settings before Safaia Lite preset");
            }
            // No REPLACE_EXISTING: a repeated apply cannot overwrite the original settings.
            Files.move(temp, path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
    static void retainRestored(Path active, Path lastRestored) throws IOException {
        Files.move(active, lastRestored, StandardCopyOption.REPLACE_EXISTING);
    }
}
