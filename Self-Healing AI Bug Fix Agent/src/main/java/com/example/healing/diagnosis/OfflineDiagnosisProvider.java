package com.example.healing.diagnosis;

import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.repository.RepositoryContext;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "agent.ai.mode", havingValue = "offline", matchIfMissing = true)
public class OfflineDiagnosisProvider implements DiagnosisProvider {
    @Override
    public DiagnosisReport diagnose(IncidentEntity incident, RepositoryContext repositoryContext) {
        return new DiagnosisReport("Automated diagnosis is unavailable in offline mode",
                "No root cause inferred", 0.0, List.of(), List.of(), "No patch proposed",
                List.of(), List.of("Configure agent.ai.mode=openai and a supported provider key"),
                "UNKNOWN", true);
    }
}