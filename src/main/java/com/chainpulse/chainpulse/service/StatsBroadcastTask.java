package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.entity.SlaRule.AlertSeverity;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import com.chainpulse.chainpulse.repository.SupplierRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * StatsBroadcastTask — automatically pushes live stats to the dashboard every 10 seconds.
 *
 * Think of this as a heartbeat — every 10 seconds it:
 * 1. Fetches fresh stats from PostgreSQL (alert counts, supplier count)
 * 2. Sends them to /topic/stats via WebSocket
 * 3. Every connected browser receives updated numbers instantly
 * 4. Dashboard metric cards update without any page refresh
 *
 * This is what keeps the 4 metric cards on the dashboard alive:
 * - Active Shipments
 * - SLA Compliance %
 * - Active Alerts count
 * - Critical Alerts count
 */
@Slf4j
@Component
public class StatsBroadcastTask {

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private AlertBroadcastService alertBroadcastService;

    /**
     * broadcastLiveStats — runs every 10 seconds automatically.
     *
     * @Scheduled(fixedRate = 10000) means:
     * "Run this method every 10,000 milliseconds (10 seconds)"
     *
     * Fetches fresh counts from DB and broadcasts to all
     * connected WebSocket clients via AlertBroadcastService.
     */
    @Scheduled(fixedRate = 10000)
    public void broadcastLiveStats() {
        try {
            // Fetch fresh stats from PostgreSQL
            Long totalSuppliers  = supplierRepository.count();
            Long totalAlerts     = alertEventRepository.count();
            Long activeAlerts    = alertEventRepository.countByResolvedFalse();
            Long criticalAlerts  = alertEventRepository
                    .countByResolvedFalseAndSeverity(AlertSeverity.CRITICAL);
            Long warningAlerts   = alertEventRepository
                    .countByResolvedFalseAndSeverity(AlertSeverity.WARNING);
            Long resolvedAlerts  = totalAlerts - activeAlerts;

            // Build stats map
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalSuppliers",  totalSuppliers);
            stats.put("totalAlerts",     totalAlerts);
            stats.put("activeAlerts",    activeAlerts);
            stats.put("criticalAlerts",  criticalAlerts);
            stats.put("warningAlerts",   warningAlerts);
            stats.put("resolvedAlerts",  resolvedAlerts);

            // Broadcast to all connected browsers via WebSocket
            alertBroadcastService.broadcastStats(stats);

        } catch (Exception e) {
            // Don't crash the scheduler if something goes wrong
            log.error("❌ Stats broadcast failed | Error: {}", e.getMessage());
        }
    }
}