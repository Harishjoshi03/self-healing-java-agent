package com.example.healing.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.example.healing.attempt.HealingAttemptRepository;
import com.example.healing.audit.persistence.AuditEventRepository;
import com.example.healing.diagnosis.DiagnosisProvider;
import com.example.healing.diagnosis.DiagnosisReport;
import com.example.healing.incident.api.IncidentRequest;
import com.example.healing.incident.application.IncidentService;
import com.example.healing.incident.domain.IncidentStatus;
import com.example.healing.incident.persistence.IncidentRepository;
import com.example.healing.patch.PatchPlan;
import com.example.healing.patch.PatchPlanner;
import com.example.healing.sandbox.SandboxRunner;
import com.example.healing.sandbox.ValidationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "agent.repository.id=owner/orders",
        "agent.repository.allowed-branches=main",
        "agent.workflow.enabled=false",
        "agent.sandbox.enabled=true",
        "agent.max-patch-attempts=2"
})
class IncidentWorkflowIntegrationTest {

    private static Path repositoryRoot;

    @Autowired
    private IncidentWorkflow workflow;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private HealingAttemptRepository attempts;

    @Autowired
    private AuditEventRepository auditEvents;

    @MockitoBean
    private DiagnosisProvider diagnosisProvider;

    @MockitoBean
    private PatchPlanner patchPlanner;

    @MockitoBean
    private SandboxRunner sandboxRunner;

    @DynamicPropertySource
    static void repositoryProperties(DynamicPropertyRegistry registry) throws Exception {
        if (repositoryRoot == null) {
            repositoryRoot = Files.createTempDirectory("workflow-repository-");
            runGit("init", "-b", "main");
            runGit("config", "user.email", "agent-test@example.invalid");
            runGit("config", "user.name", "Agent Test");
            Path source = repositoryRoot.resolve("src/main/java/demo/Calculator.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package demo;\npublic class Calculator {\n  public int add(int a, int b) { return a - b; }\n}\n");
            runGit("add", ".");
            runGit("commit", "-m", "known reproducible bug");
        }
        registry.add("agent.repository.root", () -> repositoryRoot.toString());
    }

    @BeforeEach
    void cleanDatabase() {
        attempts.deleteAll();
        auditEvents.deleteAll();
        incidents.deleteAll();
    }

    @Test
    void reportsValidatedOnlyAfterSandboxReturnsZeroExitCode() {
        prepareDiagnosisAndPatch();
        given(sandboxRunner.validate(any())).willReturn(
                new ValidationResult("docker run ... mvn test", 0, 1200, "Tests run: 1, Failures: 0", false, true));

        UUID incidentId = submitIncident();
        workflow.process(incidentId);

        var incident = incidents.findById(incidentId).orElseThrow();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.VALIDATED);
        assertThat(incident.getAttemptCount()).isEqualTo(1);
        assertThat(incident.getEscalationReason()).isNull();
        assertThat(attempts.findAllByIncidentIdOrderByAttemptNumber(incidentId))
                .singleElement().satisfies(attempt -> {
                    assertThat(attempt.getExitCode()).isZero();
                    assertThat(attempt.isPassed()).isTrue();
                });
        try {
            assertThat(Files.readString(repositoryRoot.resolve("src/main/java/demo/Calculator.java")))
                    .contains("return a - b");
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not verify source checkout remained unchanged", exception);
        }
    }

    @Test
    void neverReportsFixWhenEverySandboxAttemptFails() {
        prepareDiagnosisAndPatch();
        given(sandboxRunner.validate(any())).willReturn(
                new ValidationResult("docker run ... mvn test", 1, 900, "Tests run: 1, Failures: 1", false, true));

        UUID incidentId = submitIncident();
        workflow.process(incidentId);

        var incident = incidents.findById(incidentId).orElseThrow();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.NEEDS_HUMAN);
        assertThat(incident.getEscalationReason()).contains("Validation failed");
        assertThat(incident.getAttemptCount()).isEqualTo(2);
        assertThat(attempts.findAllByIncidentIdOrderByAttemptNumber(incidentId))
                .hasSize(2).allSatisfy(attempt -> assertThat(attempt.isPassed()).isFalse());
        verify(sandboxRunner, times(2)).validate(any());
    }

    private void prepareDiagnosisAndPatch() {
        given(diagnosisProvider.diagnose(any(), any())).willReturn(new DiagnosisReport(
                "Addition returns subtraction", "The implementation subtracts operands", 0.95,
                List.of("SRC-1"), List.of("src/main/java/demo/Calculator.java"),
                "Use addition for this method", List.of("configured Maven tests"), List.of(), "LOW", false));
        PatchPlan plan = new PatchPlan(
                "Change subtraction to addition", """
                        diff --git a/src/main/java/demo/Calculator.java b/src/main/java/demo/Calculator.java
                        --- a/src/main/java/demo/Calculator.java
                        +++ b/src/main/java/demo/Calculator.java
                        @@ -1,4 +1,4 @@
                         package demo;
                         public class Calculator {
                        -  public int add(int a, int b) { return a - b; }
                        +  public int add(int a, int b) { return a + b; }
                         }
                        """, false);
        given(patchPlanner.plan(any(), any(), any(), isNull())).willReturn(plan);
        given(patchPlanner.plan(any(), any(), any(), anyString())).willReturn(plan);
    }

    private UUID submitIncident() {
        var incident = incidentService.submit(new IncidentRequest("orders", "owner/orders", "main",
                "Unexpected result", "at demo.Calculator.add(Calculator.java:3)", "test", null), null);
        return incident.getId();
    }

    private static void runGit(String... arguments) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-C", repositoryRoot.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}