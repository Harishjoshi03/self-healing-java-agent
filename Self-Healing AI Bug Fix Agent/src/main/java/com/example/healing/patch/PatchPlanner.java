package com.example.healing.patch;

import com.example.healing.diagnosis.DiagnosisReport;
import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.repository.RepositoryContext;

public interface PatchPlanner {
    PatchPlan plan(IncidentEntity incident, DiagnosisReport diagnosis, RepositoryContext context,
                   String previousValidationFailure);
}