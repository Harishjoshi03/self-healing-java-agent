package com.example.healing.attempt;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealingAttemptRepository extends JpaRepository<HealingAttemptEntity, Long> {
    List<HealingAttemptEntity> findAllByIncidentIdOrderByAttemptNumber(UUID incidentId);
}