package com.example.healing.attempt;

import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.incident.persistence.IncidentRepository;
import com.example.healing.sandbox.ValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealingAttemptService {

    private final HealingAttemptRepository attempts;
    private final IncidentRepository incidents;
    private final ObjectMapper objectMapper;

    public HealingAttemptService(HealingAttemptRepository attempts, IncidentRepository incidents,
                                 ObjectMapper objectMapper) {
        this.attempts = attempts;
        this.incidents = incidents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HealingAttemptEntity record(IncidentEntity incident, int number, String patchSummary,
                                      List<String> changedFiles, ValidationResult validation) {
        try {
            String changedFilesJson = objectMapper.writeValueAsString(changedFiles);
            HealingAttemptEntity attempt = new HealingAttemptEntity(incident.getId(), number, patchSummary,
                    changedFilesJson, validation.command(), validation.exitCode(),
                    validation.durationMillis(), validation.output(), validation.timedOut(),
                    validation.sandboxed(), validation.passed());
            attempts.save(attempt);
            incident.recordAttempt(objectMapper.writeValueAsString(validation), changedFilesJson, number);
            incidents.save(incident);
            return attempt;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not persist validation evidence", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<HealingAttemptResponse> list(IncidentEntity incident) {
        return attempts.findAllByIncidentIdOrderByAttemptNumber(incident.getId()).stream()
                .map(HealingAttemptResponse::from).toList();
    }
}