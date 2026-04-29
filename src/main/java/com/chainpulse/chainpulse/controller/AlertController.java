package com.chainpulse.chainpulse.controller;

import com.chainpulse.chainpulse.entity.AlertEvent;
import com.chainpulse.chainpulse.entity.SlaRule;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;


/**
 * AlertController — REST API endpoints for alert data.
 *
 * Exposes:
 * GET /api/alerts              → all alerts (full history)
 * GET /api/alerts/active       → only unresolved alerts
 * GET /api/alerts/stats        → summary stats for dashboard
 * GET /api/alerts/stats/trend  → alerts per day for chart
 * PUT /api/alerts/{id}/resolve → mark an alert as resolved
 */
@Slf4j
@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    @Autowired
    private AlertEventRepository alertEventRepository;


    /**
     * GET /api/alerts?page=0&size=20&sort=createdAt,desc
     * Returns paginated alerts — newest first by default.
     * Avoids loading all 285+ alerts at once.
     *
     * Example: /api/alerts?page=0&size=20
     * Returns first 20 alerts with pagination metadata.
     */
    @Transactional
    @GetMapping
    public ResponseEntity<Page<AlertEvent>> getAllAlerts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String direction) {

        log.debug("GET /api/alerts | page={} size={} sort={} dir={}",
                page, size, sortBy, direction);

        Sort sort = direction.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<AlertEvent> alerts = alertEventRepository.findAll(pageable);

        return ResponseEntity.ok(alerts);
    }
    /**
     * GET /api/alerts/active
     * Returns only unresolved alerts.
     * Used to populate the live alert feed on dashboard load.
     */
    @Transactional
    @GetMapping("/active")
    public ResponseEntity<List<AlertEvent>> getActiveAlerts() {
        log.debug("GET /api/alerts/active");
        List<AlertEvent> alerts = alertEventRepository.findByResolvedFalse();
        return ResponseEntity.ok(alerts);
    }
    /**
     * GET /api/alerts/stats
     * Returns summary statistics for the dashboard metric cards.
     *
     * Response example:
     * {
     *   "totalAlerts": 45,
     *   "activeAlerts": 12,
     *   "criticalAlerts": 5,
     *   "warningAlerts": 7,
     *   "resolvedAlerts": 33
     * }
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getAlertStats() {
        log.debug("GET /api/alerts/stats");

        // Count total alerts ever created
        Long totalAlerts = alertEventRepository.count();

        // Count currently active (unresolved) alerts
        Long activeAlerts = alertEventRepository.countByResolvedFalse();

        // Count active alerts by severity
        Long criticalAlerts = alertEventRepository
                .countByResolvedFalseAndSeverity(SlaRule.AlertSeverity.CRITICAL);
        Long warningAlerts = alertEventRepository
                .countByResolvedFalseAndSeverity(SlaRule.AlertSeverity.WARNING);

        // Resolved = total - active
        Long resolvedAlerts = totalAlerts - activeAlerts;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAlerts", totalAlerts);
        stats.put("activeAlerts", activeAlerts);
        stats.put("criticalAlerts", criticalAlerts);
        stats.put("warningAlerts", warningAlerts);
        stats.put("resolvedAlerts", resolvedAlerts);

        return ResponseEntity.ok(stats);
    }

    /**
     * GET /api/alerts/stats/trend
     * Returns alerts created in the last N days — used for Chart.js bar chart.
     * Default: last 7 days.
     *
     * Response example:
     * {
     *   "since": "2026-04-20T00:00:00",
     *   "totalInPeriod": 28,
     *   "alerts": [...]
     * }
     */
    @GetMapping("/stats/trend")
    public ResponseEntity<Map<String, Object>> getAlertTrend(
            @RequestParam(defaultValue = "7") int days) {
        log.debug("GET /api/alerts/stats/trend?days={}", days);

        LocalDateTime since = LocalDateTime.now().minusDays(days);

        // Only fetch id and createdAt — not full alert objects
        // Avoids sending 300+ full alert objects to browser
        List<AlertEvent> recentAlerts = alertEventRepository.findByCreatedAtAfter(since);

        // Count per day — send only numbers to browser
        Map<String, Long> countPerDay = new java.util.LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            String label = LocalDateTime.now().minusDays(i)
                    .toLocalDate().toString();
            countPerDay.put(label, 0L);
        }
        recentAlerts.forEach(a -> {
            String label = a.getCreatedAt().toLocalDate().toString();
            countPerDay.merge(label, 1L, Long::sum);
        });

        Map<String, Object> trend = new HashMap<>();
        trend.put("since", since);
        trend.put("days", days);
        trend.put("totalInPeriod", recentAlerts.size());
        trend.put("countPerDay", countPerDay);  // just counts, not full objects

        return ResponseEntity.ok(trend);
    }
    /**
     * PUT /api/alerts/{id}/resolve
     * Marks an alert as resolved.
     * Called when a team member fixes the issue.
     *
     * Response: updated alert with resolved=true and resolvedAt timestamp.
     */
    @PutMapping("/{id}/resolve")
    public ResponseEntity<AlertEvent> resolveAlert(
            @PathVariable Long id,
            @RequestParam(required = false) String resolvedBy) {
        log.debug("PUT /api/alerts/{}/resolve", id);

        return alertEventRepository.findById(id)
                .map(alert -> {
                    // Mark as resolved
                    alert.setResolved(true);
                    alert.setResolvedAt(LocalDateTime.now());
                    alert.setResolvedBy(resolvedBy != null ? resolvedBy : "system");

                    AlertEvent saved = alertEventRepository.save(alert);

                    log.info("✅ Alert resolved | ID: {} | Rule: {} | ResolvedBy: {}",
                            saved.getId(), saved.getRuleType(), saved.getResolvedBy());

                    return ResponseEntity.ok(saved);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}