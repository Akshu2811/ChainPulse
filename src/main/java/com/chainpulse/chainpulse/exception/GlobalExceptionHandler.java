package com.chainpulse.chainpulse.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GlobalExceptionHandler — catches all errors across all controllers
 * and returns clean, structured JSON error responses.
 *
 * Without this, Spring returns ugly HTML error pages.
 * With this, every error returns a consistent JSON structure:
 * {
 *   "timestamp": "2026-04-28T12:00:00",
 *   "status": 400,
 *   "error": "Validation failed",
 *   "messages": ["Tracking number is required", "Origin is required"]
 * }
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles @Valid validation failures.
     * Fired when a POST request body fails validation constraints.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        // Extract all validation error messages
        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Validation failed");
        response.put("messages", errors);

        log.warn("⚠️ Validation failed | Errors: {}", errors);

        return ResponseEntity.badRequest().body(response);
    }

    /**
     * Handles any unexpected exception not caught elsewhere.
     * Returns 500 with a clean message instead of a stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        response.put("error", "Internal server error");
        response.put("message", ex.getMessage());

        log.error("❌ Unexpected error | {}", ex.getMessage());

        return ResponseEntity.internalServerError().body(response);
    }


    /**
     * Handles missing or malformed request body.
     * Fired when request body is empty or not valid JSON.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMissingBody(
            HttpMessageNotReadableException ex) {

        Map<String, Object> response = new HashMap<>();
        response.put("timestamp", LocalDateTime.now().toString());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        response.put("error", "Bad request");
        response.put("message", "Request body is missing or malformed");

        log.warn("⚠️ Missing or malformed request body");

        return ResponseEntity.badRequest().body(response);
    }
}