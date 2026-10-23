package com.example.healing.sandbox;

import com.example.healing.incident.application.SecretRedactor;
import com.example.healing.patch.IsolatedWorkspace;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.sandbox.enabled", havingValue = "true")
public class DockerSandboxRunner implements SandboxRunner {

    private static final int MAX_OUTPUT_BYTES = 48_000;
    private final String image;
    private final Duration timeout;
    private final Path dependencyRepository;
    private final String containerUser;
    private final SecretRedactor redactor;

    public DockerSandboxRunner(@Value("${agent.sandbox.image:maven:3.9.9-eclipse-temurin-21}") String image,
                               @Value("${agent.sandbox.timeout-seconds:300}") long timeoutSeconds,
                               @Value("${agent.sandbox.dependency-cache:${user.home}/.m2/repository}") Path dependencyRepository,
                               @Value("${agent.sandbox.container-user:1000:1000}") String containerUser,
                               SecretRedactor redactor) {
        this.image = image;
        this.timeout = Duration.ofSeconds(Math.max(30, Math.min(timeoutSeconds, 1800)));
        this.dependencyRepository = dependencyRepository;
        if (!containerUser.matches("\\d+:\\d+")) {
            throw new IllegalArgumentException("Sandbox container user must be numeric uid:gid");
        }
        this.containerUser = containerUser;
        this.redactor = redactor;
    }

    @Override
    public ValidationResult validate(IsolatedWorkspace workspace) {
        String containerName = "healing-test-" + UUID.randomUUID().toString().substring(0, 12);
        List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--pull=never",
                "--name", containerName, "--network=none", "--read-only", "--cap-drop=ALL",
                "--security-opt=no-new-privileges", "--memory=2g", "--cpus=2", "--pids-limit=256",
                "--user", containerUser, "--tmpfs", "/tmp:rw,noexec,nosuid,size=512m",
                "--env", "HOME=/tmp",
                "--mount", "type=bind,src=" + workspace.root().toAbsolutePath() + ",dst=/workspace",
                "--mount", "type=bind,src=" + dependencyRepository.toAbsolutePath() + ",dst=/cache/repository,readonly",
                "--workdir=/workspace", "--entrypoint", "mvn", image, "--batch-mode", "--no-transfer-progress",
                "--offline", "-Dmaven.repo.local=/cache/repository", "test"));
        long started = System.nanoTime();
        Process process = null;
        BoundedOutput output = new BoundedOutput(MAX_OUTPUT_BYTES);
        Thread reader = null;
        try {
            if (!java.nio.file.Files.isDirectory(dependencyRepository)) {
                throw new IOException("Maven dependency cache is not available to the sandbox");
            }
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process runningProcess = process;
            reader = Thread.ofVirtual().start(() -> copyBounded(runningProcess, output));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                removeContainer(containerName);
                return result(command, null, started, output.text(), true, true);
            }
            reader.join(2000);
            return result(command, process.exitValue(), started, output.text(), false, true);
        } catch (IOException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            removeContainer(containerName);
            output.append("Sandbox could not start: " + exception.getMessage());
            return result(command, 127, started, output.text(), false, false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            removeContainer(containerName);
            return result(command, 130, started, "Sandbox interrupted", true, process != null);
        }
    }

    private void copyBounded(Process process, BoundedOutput output) {
        try (var input = process.getInputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.append(buffer, count);
            }
        } catch (IOException ignored) {
        }
    }

    private void removeContainer(String name) {
        try {
            Process cleanup = new ProcessBuilder("docker", "rm", "--force", name)
                    .redirectErrorStream(true).start();
            cleanup.waitFor(10, TimeUnit.SECONDS);
        } catch (IOException exception) {
            // The daemon reaps containers when the CLI exits; cleanup is best-effort after timeout.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private ValidationResult result(List<String> command, Integer exitCode, long started,
                                    String output, boolean timedOut, boolean sandboxed) {
        String displayCommand = "docker run ... " + image + " mvn --batch-mode --no-transfer-progress --offline "
                + "-Dmaven.repo.local=/cache/repository test";
        return new ValidationResult(displayCommand, exitCode,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), redactor.redact(output), timedOut, sandboxed);
    }

    private static final class BoundedOutput {
        private final int maximumBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean truncated;

        private BoundedOutput(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        synchronized void append(byte[] bytes, int length) {
            int remaining = maximumBytes - output.size();
            if (remaining > 0) {
                output.write(bytes, 0, Math.min(remaining, length));
            }
            if (length > remaining) {
                truncated = true;
            }
        }

        synchronized void append(String text) {
            append(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), text.length());
        }

        synchronized String text() {
            return output.toString(java.nio.charset.StandardCharsets.UTF_8)
                    + (truncated ? "\n[output truncated]" : "");
        }
    }
}