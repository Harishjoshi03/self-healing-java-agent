package com.example.healing.repository;

import com.example.healing.incident.persistence.IncidentEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RepositoryEvidenceCollector {

    private static final Pattern STACK_FRAME = Pattern.compile(
            "\\bat ([\\w.$]+)\\.[\\w$<>]+\\(([^():]+\\.java):(\\d+)\\)");
    private static final int MAX_SOURCE_BYTES = 1_000_000;
    private static final int CONTEXT_LINES = 5;

    private final String configuredRepository;
    private final String configuredRoot;
    private final List<String> allowedBranches;

    public RepositoryEvidenceCollector(
            @Value("${agent.repository.id:}") String configuredRepository,
            @Value("${agent.repository.root:}") String configuredRoot,
            @Value("${agent.repository.allowed-branches:main,master}") List<String> allowedBranches) {
        this.configuredRepository = configuredRepository;
        this.configuredRoot = configuredRoot;
        this.allowedBranches = allowedBranches;
    }

    public RepositoryContext collect(IncidentEntity incident) {
        if (configuredRepository.isBlank() || configuredRoot.isBlank()) {
            throw new RepositoryInspectionException("Repository access is not configured on the server");
        }
        if (!configuredRepository.equals(incident.getRepository())) {
            throw new RepositoryInspectionException("Requested repository is not on the server allowlist");
        }
        if (!allowedBranches.contains(incident.getBranch())) {
            throw new RepositoryInspectionException("Requested branch is not on the server allowlist");
        }

        try {
            Path root = Path.of(configuredRoot).toRealPath();
            if (!Files.isDirectory(root) || !Files.exists(root.resolve(".git"))) {
                throw new RepositoryInspectionException("Configured repository root is not a Git working tree");
            }
            String currentBranch = runGit(root, "rev-parse", "--abbrev-ref", "HEAD").trim();
            if (!currentBranch.equals(incident.getBranch())) {
                throw new RepositoryInspectionException("Configured checkout branch does not match the requested branch");
            }
            if (!runGit(root, "status", "--porcelain").isBlank()) {
                throw new RepositoryInspectionException("Configured repository has uncommitted changes; refusing to inspect or modify it");
            }
            String commit = runGit(root, "rev-parse", "HEAD").trim();
            List<RepositoryEvidence> evidence = collectSourceEvidence(root, incident.getStackTrace());
            if (evidence.isEmpty()) {
                throw new RepositoryInspectionException("No stack-trace source file was found in the configured repository");
            }
            return new RepositoryContext(configuredRepository, currentBranch, commit, evidence, List.of());
        } catch (IOException exception) {
            throw new RepositoryInspectionException("Could not safely inspect the configured repository");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RepositoryInspectionException("Repository inspection was interrupted");
        }
    }

    private List<RepositoryEvidence> collectSourceEvidence(Path root, String stackTrace) throws IOException {
        Matcher matcher = STACK_FRAME.matcher(stackTrace);
        List<RepositoryEvidence> evidence = new ArrayList<>();
        while (matcher.find() && evidence.size() < 5) {
            String className = matcher.group(1);
            String packagePath = className.contains(".")
                    ? className.substring(0, className.lastIndexOf('.')).replace('.', '/') : "";
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            int innerClass = simpleName.indexOf('$');
            if (innerClass >= 0) {
                simpleName = simpleName.substring(0, innerClass);
            }
            Path source = root.resolve("src/main/java").resolve(packagePath)
                    .resolve(simpleName + ".java").normalize();
            if (!source.startsWith(root) || !Files.isRegularFile(source)
                    || Files.size(source) > MAX_SOURCE_BYTES) {
                continue;
            }
            Path realSource = source.toRealPath();
            if (!realSource.startsWith(root)) {
                continue;
            }
            int line = Integer.parseInt(matcher.group(3));
            List<String> lines = Files.readAllLines(realSource);
            if (line < 1 || line > lines.size()) {
                continue;
            }
            int from = Math.max(1, line - CONTEXT_LINES);
            int to = Math.min(lines.size(), line + CONTEXT_LINES);
            String excerpt = lines.subList(from - 1, to).stream()
                    .map(sourceLine -> sourceLine.length() > 500 ? sourceLine.substring(0, 500) : sourceLine)
                    .collect(java.util.stream.Collectors.joining("\n"));
            evidence.add(new RepositoryEvidence("SRC-" + (evidence.size() + 1),
                    root.relativize(realSource).toString().replace('\\', '/'), line, excerpt));
        }
        return evidence;
    }

    private String runGit(Path root, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Git inspection timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("Git inspection failed");
        }
        return output;
    }
}