package com.example.healing.attempt;

public record HealingAttemptResponse(int attemptNumber, Integer exitCode, boolean passed) {
    static HealingAttemptResponse from(HealingAttemptEntity attempt) {
        return new HealingAttemptResponse(attempt.getAttemptNumber(), attempt.getExitCode(), attempt.isPassed());
    }
}