package com.example.healing.diagnosis;

import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.repository.RepositoryContext;

public interface DiagnosisProvider {
    DiagnosisReport diagnose(IncidentEntity incident, RepositoryContext repositoryContext);
}