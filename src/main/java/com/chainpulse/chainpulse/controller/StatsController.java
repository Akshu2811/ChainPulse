package com.chainpulse.chainpulse.controller;

import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import com.chainpulse.chainpulse.entity.SlaRule.AlertSeverity;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import com.chainpulse.chainpulse.repository.ShipmentRepository;
import com.chainpulse.chainpulse.repository.SupplierRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StatsController — REST API for live dashboard statistics.
 *
 * GET /api/stats → returns all numbers needed for the 4 metric cards:
 * - Active shipments count
 * - SLA compliance percentage
 * - Active alerts count
 * - Critical alerts count
 *
 * Called on page load and updated every 10s via WebSocket broadcast.
 */
@Slf4j
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    /**
     * GET /api/stats
     * Returns live dashboard statistics.
     *
     * Response example:
     * {
     *   "totalSuppliers": 5,
     *   "activeAlerts": 12,
     *   "criticalAlerts": 5,
     *   "warningAlerts": 7,
     *   "totalAlerts": 45,
     *   "resolvedAlerts": 33
     * }
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getLiveStats() {
        log.debug("GET /api/stats");

        // Total suppliers in the system
        Long totalSuppliers = supplierRepository.count();

        // Alert counts
        Long totalAlerts    = alertEventRepository.count();
        Long activeAlerts   = alertEventRepository.countByResolvedFalse();
        Long criticalAlerts = alertEventRepository
                .countByResolvedFalseAndSeverity(AlertSeverity.CRITICAL);
        Long warningAlerts  = alertEventRepository
                .countByResolvedFalseAndSeverity(AlertSeverity.WARNING);
        Long resolvedAlerts = totalAlerts - activeAlerts;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSuppliers", totalSuppliers);
        stats.put("totalAlerts", totalAlerts);
        stats.put("activeAlerts", activeAlerts);
        stats.put("criticalAlerts", criticalAlerts);
        stats.put("warningAlerts", warningAlerts);
        stats.put("resolvedAlerts", resolvedAlerts);

        return ResponseEntity.ok(stats);
    }
}