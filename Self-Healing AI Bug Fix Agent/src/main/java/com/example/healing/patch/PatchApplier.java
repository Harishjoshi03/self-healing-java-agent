package com.example.healing.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PatchApplier {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.+) b/(.+)$", Pattern.MULTILINE);
    private static final int MAX_PATCH_BYTES = 64_000;
    private static final int MAX_CHANGED_FILES = 5;

    public List<String> apply(IsolatedWorkspace workspace, String diff) {
        if (diff == null || diff.isBlank() || diff.getBytes(StandardCharsets.UTF_8).length > MAX_PATCH_BYTES) {
            throw new PatchRejectedException("Patch is empty or exceeds the configured size limit");
        }
        if (diff.contains("GIT binary patch") || diff.contains("new file mode 120000")
                || diff.contains("new file mode 160000") || diff.contains("old mode 120000")
                || diff.contains("new mode 120000")) {
            throw new PatchRejectedException("Binary files, symlinks, and submodules are not permitted");
        }
        Set<String> files = changedFiles(diff);
        if (files.isEmpty() || files.size() > MAX_CHANGED_FILES) {
            throw new PatchRejectedException("Patch must change between one and five source files");
        }
        for (String file : files) {
            Path normalized = Path.of(file).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..") || !normalized.toString().equals(file)
                    || !isAllowedSourcePath(normalized)) {
                throw new PatchRejectedException("Patch changes a path outside the Java source/test allowlist");
            }
            rejectSymlinkComponents(workspace.root(), normalized);
        }
        runGitApply(workspace.root(), diff, true);
        runGitApply(workspace.root(), diff, false);
        return List.copyOf(files);
    }

    private Set<String> changedFiles(String diff) {
        Matcher matcher = DIFF_HEADER.matcher(diff);
        Set<String> files = new LinkedHashSet<>();
        while (matcher.find()) {
            if (!matcher.group(1).equals(matcher.group(2))) {
                throw new PatchRejectedException("Renames and copies are not permitted");
            }
            files.add(matcher.group(1));
        }
        return files;
    }

    private boolean isAllowedSourcePath(Path path) {
        Path mainSource = Path.of("src/main/java");
        Path testSource = Path.of("src/test/java");
        return path.startsWith(mainSource) && path.getNameCount() > mainSource.getNameCount()
                || path.startsWith(testSource) && path.getNameCount() > testSource.getNameCount();
    }

    private void rejectSymlinkComponents(Path root, Path relativePath) {
        Path current = root;
        for (Path component : relativePath) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new PatchRejectedException("Patch path crosses a symbolic link");
            }
        }
    }

    private void runGitApply(Path root, String diff, boolean check) {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString(), "apply",
                "--whitespace=error"));
        if (check) {
            command.add("--check");
        }
        command.add("-");
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().write(diff.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            if (!process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new PatchRejectedException("Patch application timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new PatchRejectedException("Patch did not apply cleanly");
            }
        } catch (IOException exception) {
            throw new PatchRejectedException("Could not apply patch in isolated workspace");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PatchRejectedException("Patch application was interrupted");
        }
    }
}