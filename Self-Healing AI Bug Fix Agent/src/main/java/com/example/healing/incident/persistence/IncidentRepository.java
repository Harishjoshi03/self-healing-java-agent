package com.example.healing.incident.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID> {
    Optional<IncidentEntity> findByIdempotencyKey(String idempotencyKey);
}