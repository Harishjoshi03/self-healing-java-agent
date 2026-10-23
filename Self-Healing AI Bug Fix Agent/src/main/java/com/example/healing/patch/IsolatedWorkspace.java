package com.example.healing.patch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record IsolatedWorkspace(Path root, String branch) implements AutoCloseable {
    @Override
    public void close() throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}