package com.example.healing.patch;

import com.example.healing.incident.persistence.IncidentEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class IsolatedWorkspaceFactory {

    private final String configuredRepository;
    private final String configuredRoot;
    private final List<String> allowedBranches;

    public IsolatedWorkspaceFactory(
            @Value("${agent.repository.id:}") String configuredRepository,
            @Value("${agent.repository.root:}") String configuredRoot,
            @Value("${agent.repository.allowed-branches:main,master}") List<String> allowedBranches) {
        this.configuredRepository = configuredRepository;
        this.configuredRoot = configuredRoot;
        this.allowedBranches = allowedBranches;
    }

    public IsolatedWorkspace create(IncidentEntity incident) {
        if (configuredRepository.isBlank() || !configuredRepository.equals(incident.getRepository())
                || configuredRoot.isBlank() || !allowedBranches.contains(incident.getBranch())) {
            throw new PatchRejectedException("Repository or branch is not allowlisted on the server");
        }

        Path temporaryRoot = null;
        try {
            Path source = Path.of(configuredRoot).toRealPath();
            if (!Files.exists(source.resolve(".git")) || !runGit(source, "status", "--porcelain").isBlank()
                    || !runGit(source, "rev-parse", "--abbrev-ref", "HEAD").trim().equals(incident.getBranch())) {
                throw new PatchRejectedException("Source checkout is not clean or is on a different branch");
            }
            temporaryRoot = Files.createTempDirectory("healing-" + incident.getId() + "-");
            Path clone = temporaryRoot.resolve("repository");
            runGit(null, "clone", "--quiet", "--no-hardlinks", "--single-branch", "--branch",
                    incident.getBranch(), source.toString(), clone.toString());
                setSandboxPermissions(temporaryRoot);
            String branch = "agent/" + incident.getId().toString().replace('-', '_');
            runGit(clone, "checkout", "--quiet", "-b", branch);
            return new IsolatedWorkspace(clone, branch);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            deleteQuietly(temporaryRoot);
            throw new PatchRejectedException("Could not create a clean isolated workspace");
        }
    }

    private String runGit(Path workingDirectory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        if (workingDirectory != null) {
            command.add("-C");
            command.add(workingDirectory.toString());
        }
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        Process process = builder.start();
        if (!process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IOException("Git command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("Git command failed: " + output);
        }
        return output;
    }

    private void deleteQuietly(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private void setSandboxPermissions(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    continue;
                }
                Files.setPosixFilePermissions(path, Files.isDirectory(path)
                        ? java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxr-x")
                        : java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));
            }
        }
    }
}