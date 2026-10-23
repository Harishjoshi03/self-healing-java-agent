package com.example.healing.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.healing.incident.persistence.IncidentEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryEvidenceCollectorTest {

    @TempDir
    Path repository;

    private RepositoryEvidenceCollector collector;

    @BeforeEach
    void initializeCleanRepository() throws Exception {
        runGit("init", "-b", "main");
        runGit("config", "user.email", "agent-test@example.invalid");
        runGit("config", "user.name", "Agent Test");
        Path source = repository.resolve("src/main/java/com/example/demo/Example.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example.demo;\npublic class Example {\n  void fail() {}\n}\n");
        runGit("add", ".");
        runGit("commit", "-m", "test fixture");
        collector = new RepositoryEvidenceCollector("owner/orders", repository.toString(), List.of("main"));
    }

    @Test
    void collectsOnlyRealSourceEvidenceForNestedStackFrames() {
        RepositoryContext context = collector.collect(incident("at com.example.demo.Example$Nested.fail(Example.java:3)"));

        assertThat(context.commit()).isNotBlank();
        assertThat(context.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.path()).isEqualTo("src/main/java/com/example/demo/Example.java");
            assertThat(evidence.line()).isEqualTo(3);
            assertThat(evidence.excerpt()).contains("void fail()");
        });
    }

    @Test
    void refusesDirtyWorktrees() throws Exception {
        Files.writeString(repository.resolve("uncommitted.txt"), "user work");

        assertThatThrownBy(() -> collector.collect(incident("at com.example.demo.Example.fail(Example.java:3)")))
                .isInstanceOf(RepositoryInspectionException.class)
                .hasMessageContaining("uncommitted changes");
    }

    @Test
    void refusesRepositoriesOutsideTheServerAllowlist() {
        IncidentEntity otherRepository = new IncidentEntity("orders", "owner/other", "main", "failure",
                "at com.example.demo.Example.fail(Example.java:3)", "test", null, null);

        assertThatThrownBy(() -> collector.collect(otherRepository))
                .isInstanceOf(RepositoryInspectionException.class)
                .hasMessageContaining("allowlist");
    }

    private IncidentEntity incident(String stackTrace) {
        return new IncidentEntity("orders", "owner/orders", "main", "failure", stackTrace, "test", null, null);
    }

    private void runGit(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repository.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}