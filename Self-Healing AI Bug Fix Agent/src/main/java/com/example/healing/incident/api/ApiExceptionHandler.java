package com.example.healing.incident.api;

import com.example.healing.incident.application.IncidentNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IncidentNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Incident not found"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidRequest() {
        return ResponseEntity.badRequest().body(Map.of("error", "Request validation failed"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidHeader() {
        return ResponseEntity.badRequest().body(Map.of("error", "Request header is invalid"));
    }
}