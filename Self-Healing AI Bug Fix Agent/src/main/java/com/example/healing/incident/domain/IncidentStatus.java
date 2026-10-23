package com.example.healing.incident.domain;

import java.util.EnumSet;

public enum IncidentStatus {
    RECEIVED,
    VALIDATING,
    INVESTIGATING,
    DIAGNOSING,
    PLAN_READY,
    PATCHING,
    TESTING,
    RETRY_ANALYSIS,
    VALIDATED,
    PR_CREATED,
    COMPLETED,
    NEEDS_HUMAN,
    FAILED,
    REJECTED;

    public boolean allowsTransitionTo(IncidentStatus next) {
        if (this == next) {
            return true;
        }
        return switch (this) {
            case RECEIVED -> EnumSet.of(VALIDATING, INVESTIGATING, NEEDS_HUMAN, FAILED, REJECTED).contains(next);
            case VALIDATING -> EnumSet.of(INVESTIGATING, NEEDS_HUMAN, FAILED, REJECTED).contains(next);
            case INVESTIGATING -> EnumSet.of(DIAGNOSING, NEEDS_HUMAN, FAILED).contains(next);
            case DIAGNOSING -> EnumSet.of(PLAN_READY, PATCHING, NEEDS_HUMAN, FAILED).contains(next);
            case PLAN_READY -> EnumSet.of(PATCHING, NEEDS_HUMAN, FAILED).contains(next);
            case PATCHING -> EnumSet.of(TESTING, RETRY_ANALYSIS, NEEDS_HUMAN, FAILED).contains(next);
            case TESTING -> EnumSet.of(VALIDATED, RETRY_ANALYSIS, NEEDS_HUMAN, FAILED).contains(next);
            case RETRY_ANALYSIS -> EnumSet.of(PATCHING, NEEDS_HUMAN, FAILED).contains(next);
            case VALIDATED -> EnumSet.of(PR_CREATED, COMPLETED, NEEDS_HUMAN).contains(next);
            case PR_CREATED -> EnumSet.of(COMPLETED, NEEDS_HUMAN).contains(next);
            case COMPLETED, NEEDS_HUMAN, FAILED, REJECTED -> false;
        };
    }
}