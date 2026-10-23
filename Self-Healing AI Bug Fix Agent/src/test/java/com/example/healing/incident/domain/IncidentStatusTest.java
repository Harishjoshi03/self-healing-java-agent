package com.example.healing.incident.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.healing.incident.persistence.IncidentEntity;
import org.junit.jupiter.api.Test;

class IncidentStatusTest {

    @Test
    void acceptsOrchestratedAndRetryTransitions() {
        IncidentEntity incident = new IncidentEntity("orders", "owner/orders", "main",
                "failure", "stack", "test", null, null);

        incident.updateStatus(IncidentStatus.INVESTIGATING, "collecting evidence", null);
        incident.updateStatus(IncidentStatus.DIAGNOSING, "diagnosing", null);
        incident.updateStatus(IncidentStatus.PATCHING, "patching", null);
        incident.updateStatus(IncidentStatus.TESTING, "testing", null);
        incident.updateStatus(IncidentStatus.RETRY_ANALYSIS, "retry", null);
        incident.updateStatus(IncidentStatus.PATCHING, "retry patch", null);

        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.PATCHING);
    }

    @Test
    void rejectsSkippingValidationAndRestartingTerminalIncidents() {
        IncidentEntity incident = new IncidentEntity("orders", "owner/orders", "main",
                "failure", "stack", "test", null, null);

        assertThatThrownBy(() -> incident.updateStatus(IncidentStatus.VALIDATED, "done", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECEIVED -> VALIDATED");

        incident.updateStatus(IncidentStatus.NEEDS_HUMAN, "escalated", "not enough evidence");
        assertThatThrownBy(() -> incident.updateStatus(IncidentStatus.INVESTIGATING, "restart", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NEEDS_HUMAN -> INVESTIGATING");
    }
}