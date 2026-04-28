package com.chainpulse.chainpulse.controller;

import com.chainpulse.chainpulse.entity.SlaRule;
import com.chainpulse.chainpulse.repository.SlaRuleRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SlaRuleController — REST API for managing SLA rules.
 *
 * Allows creating, viewing and toggling SLA rules at runtime
 * without restarting the app. In a real system, ops teams
 * would use this to tune alert thresholds per supplier.
 *
 * GET  /api/sla-rules        → all rules
 * GET  /api/sla-rules/active → only active rules
 * POST /api/sla-rules        → create new rule
 * PUT  /api/sla-rules/{id}/toggle → enable/disable a rule
 */
@Slf4j
@RestController
@RequestMapping("/api/sla-rules")
public class SlaRuleController {

    @Autowired
    private SlaRuleRepository slaRuleRepository;

    /**
     * GET /api/sla-rules
     * Returns all SLA rules — active and inactive.
     */
    @GetMapping
    public ResponseEntity<List<SlaRule>> getAllRules() {
        log.debug("GET /api/sla-rules");
        return ResponseEntity.ok(slaRuleRepository.findAll());
    }

    /**
     * GET /api/sla-rules/active
     * Returns only active rules — same ones the SlaRuleEngine uses.
     */
    @GetMapping("/active")
    public ResponseEntity<List<SlaRule>> getActiveRules() {
        log.debug("GET /api/sla-rules/active");
        return ResponseEntity.ok(slaRuleRepository.findByActiveTrue());
    }

    /**
     * POST /api/sla-rules
     * Creates a new SLA rule.
     *
     * Example request body:
     * {
     *   "ruleType": "MAX_TRANSIT_HOURS",
     *   "thresholdValue": 24,
     *   "severity": "CRITICAL",
     *   "active": true
     * }
     */
    @PostMapping
    public ResponseEntity<SlaRule> createRule(@RequestBody SlaRule rule) {
        log.debug("POST /api/sla-rules | Type: {} | Threshold: {}",
                rule.getRuleType(), rule.getThresholdValue());
        SlaRule saved = slaRuleRepository.save(rule);
        log.info("✅ SLA rule created | ID: {} | Type: {} | Threshold: {}h | Severity: {}",
                saved.getId(), saved.getRuleType(),
                saved.getThresholdValue(), saved.getSeverity());
        return ResponseEntity.ok(saved);
    }

    /**
     * PUT /api/sla-rules/{id}/toggle
     * Enables or disables a rule without deleting it.
     * Useful for temporarily pausing an alert rule.
     */
    @PutMapping("/{id}/toggle")
    public ResponseEntity<SlaRule> toggleRule(@PathVariable Long id) {
        log.debug("PUT /api/sla-rules/{}/toggle", id);
        return slaRuleRepository.findById(id)
                .map(rule -> {
                    rule.setActive(!rule.getActive());
                    SlaRule saved = slaRuleRepository.save(rule);
                    log.info("🔄 SLA rule toggled | ID: {} | Active: {}",
                            saved.getId(), saved.getActive());
                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * DELETE /api/sla-rules/{id}
     * Permanently deletes a rule.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
        log.debug("DELETE /api/sla-rules/{}", id);
        return slaRuleRepository.findById(id)
                .map(rule -> {
                    slaRuleRepository.delete(rule);
                    log.info("🗑️ SLA rule deleted | ID: {}", id);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
}