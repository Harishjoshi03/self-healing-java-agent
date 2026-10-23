package com.example.healing.patch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PatchApplierTest {

    @TempDir
    Path workspaceRoot;

    private PatchApplier patchApplier;

    @BeforeEach
    void initializeRepository() throws Exception {
        runGit("init", "-b", "agent/test");
        runGit("config", "user.email", "agent-test@example.invalid");
        runGit("config", "user.name", "Agent Test");
        Path source = workspaceRoot.resolve("src/main/java/example/Service.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package example;\nclass Service {\n  String value() { return \"old\"; }\n}\n");
        runGit("add", ".");
        runGit("commit", "-m", "fixture");
        patchApplier = new PatchApplier();
    }

    @Test
    void appliesSmallJavaPatch() throws Exception {
        String patch = """
                diff --git a/src/main/java/example/Service.java b/src/main/java/example/Service.java
                index 55f6582..b4e7880 100644
                --- a/src/main/java/example/Service.java
                +++ b/src/main/java/example/Service.java
                @@ -1,4 +1,4 @@
                 package example;
                 class Service {
                -  String value() { return "old"; }
                +  String value() { return "fixed"; }
                 }
                """;

        List<String> changed = patchApplier.apply(new IsolatedWorkspace(workspaceRoot, "agent/test"), patch);

        assertThat(changed).containsExactly("src/main/java/example/Service.java");
        assertThat(Files.readString(workspaceRoot.resolve(changed.getFirst()))).contains("return \"fixed\"");
    }

    @Test
    void rejectsTraversalAndNonSourcePaths() {
        String traversal = """
                diff --git a/../../outside.txt b/../../outside.txt
                --- a/../../outside.txt
                +++ b/../../outside.txt
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> patchApplier.apply(new IsolatedWorkspace(workspaceRoot, "agent/test"), traversal))
                .isInstanceOf(PatchRejectedException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void rejectsBuildAndConfigurationChanges() {
        String pomPatch = """
                diff --git a/pom.xml b/pom.xml
                --- a/pom.xml
                +++ b/pom.xml
                @@ -1 +1 @@
                -old
                +new
                """;

        assertThatThrownBy(() -> patchApplier.apply(new IsolatedWorkspace(workspaceRoot, "agent/test"), pomPatch))
                .isInstanceOf(PatchRejectedException.class)
                .hasMessageContaining("allowlist");
    }

            @Test
            void rejectsPathsThatTraverseSymlinks() throws Exception {
            Path sourceRoot = workspaceRoot.resolve("src/main/java/example");
            Path externalDirectory = Files.createTempDirectory("patch-external-");
            Files.delete(sourceRoot.resolve("Service.java"));
            Files.delete(sourceRoot);
            Files.createSymbolicLink(sourceRoot, externalDirectory);
            String patch = """
                diff --git a/src/main/java/example/Service.java b/src/main/java/example/Service.java
                --- a/src/main/java/example/Service.java
                +++ b/src/main/java/example/Service.java
                @@ -1 +1 @@
                -old
                +new
                """;

            assertThatThrownBy(() -> patchApplier.apply(new IsolatedWorkspace(workspaceRoot, "agent/test"), patch))
                .isInstanceOf(PatchRejectedException.class)
                .hasMessageContaining("symbolic link");
            }

            @Test
            void rejectsNormalizedTraversalOutOfSourceRoot() {
            String traversal = """
                diff --git a/src/main/java/../../pom.xml b/src/main/java/../../pom.xml
                --- a/src/main/java/../../pom.xml
                +++ b/src/main/java/../../pom.xml
                @@ -1 +1 @@
                -old
                +new
                """;

            assertThatThrownBy(() -> patchApplier.apply(new IsolatedWorkspace(workspaceRoot, "agent/test"), traversal))
                .isInstanceOf(PatchRejectedException.class)
                .hasMessageContaining("allowlist");
            }

    private void runGit(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", workspaceRoot.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}