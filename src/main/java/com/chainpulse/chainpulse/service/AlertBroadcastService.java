package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.entity.AlertEvent;
import com.chainpulse.chainpulse.entity.SlaRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * AlertBroadcastService — pushes real-time alerts to the browser dashboard.
 *
 * Think of this as the "announcer" in ChainPulse.
 * Every time a new alert is created, this service immediately
 * broadcasts it to all connected browser clients via WebSocket.
 *
 * Flow:
 * SlaRuleEngine creates AlertEvent → calls broadcastAlert()
 * → SimpMessagingTemplate sends to /topic/alerts
 * → Every browser subscribed to /topic/alerts receives it instantly
 * → Dashboard alert feed updates in real-time without page refresh
 *
 * SimpMessagingTemplate — Spring's WebSocket message sender.
 * It's the tool that actually pushes data to connected clients.
 */
@Slf4j
@Service
public class AlertBroadcastService {

    /**
     * SimpMessagingTemplate — Spring's tool for sending WebSocket messages.
     * "Simp" = Simple Messaging Protocol (STOMP).
     * Auto-injected by Spring since we have @EnableWebSocketMessageBroker.
     */
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * broadcastAlert — sends a new alert to all connected dashboard clients.
     *
     * Converts AlertEvent entity → clean Map (avoids Hibernate lazy loading issues)
     * Then sends it to /topic/alerts WebSocket topic.
     * All browsers subscribed to this topic receive it instantly.
     *
     * @param alert — the AlertEvent that was just created in PostgreSQL
     */
    public void broadcastAlert(AlertEvent alert) {
        try {
            // Build a clean map — easier for browser JS to consume than full entity
            Map<String, Object> alertPayload = new HashMap<>();
            alertPayload.put("id",           alert.getId());
            alertPayload.put("ruleType",     alert.getRuleType().name());
            alertPayload.put("severity",     alert.getSeverity().name());
            alertPayload.put("message",      alert.getMessage());
            alertPayload.put("resolved",     alert.getResolved());
            alertPayload.put("createdAt",    alert.getCreatedAt().toString());

            // Add supplier info if available
            if (alert.getSupplier() != null) {
                alertPayload.put("supplierName", alert.getSupplier().getName());
                alertPayload.put("supplierId",   alert.getSupplier().getId());
                alertPayload.put("location",     alert.getSupplier().getLocation());
            }

            // Push to /topic/alerts — all subscribed browsers receive this
            messagingTemplate.convertAndSend("/topic/alerts", (Object)alertPayload);

            log.info("📡 Alert broadcast sent | ID: {} | Severity: {} | Rule: {} | Supplier: {}",
                    alert.getId(),
                    alert.getSeverity(),
                    alert.getRuleType(),
                    alert.getSupplier() != null ? alert.getSupplier().getName() : "Unknown");

        } catch (Exception e) {
            // Don't let broadcast failure crash the alert creation flow
            log.error("❌ Failed to broadcast alert | ID: {} | Error: {}",
                    alert.getId(), e.getMessage());
        }
    }

    /**
     * broadcastStats — sends live dashboard stats to all connected clients.
     * Called every 10 seconds by StatsBroadcastTask.
     * Updates the 4 metric cards on the dashboard automatically.
     *
     * @param stats — map of stats: activeAlerts, criticalAlerts, etc.
     */
    public void broadcastStats(Map<String, Object> stats) {
        try {
            // Add timestamp so browser knows how fresh the data is
            stats.put("lastUpdated", LocalDateTime.now().toString());

            // Push to /topic/stats — all subscribed browsers receive this
            messagingTemplate.convertAndSend("/topic/stats", (Object) stats);

            log.debug("📊 Stats broadcast sent | ActiveAlerts: {} | Critical: {}",
                    stats.get("activeAlerts"),
                    stats.get("criticalAlerts"));

        } catch (Exception e) {
            log.error("❌ Failed to broadcast stats | Error: {}", e.getMessage());
        }
    }

    /**
     * broadcastAiAnalysis — sends AI root cause analysis to dashboard.
     * Browser receives this and displays it on the alert card.
     *
     * @param alertId  — which alert this analysis belongs to
     * @param analysis — Gemini's root cause analysis text
     */
    public void broadcastAiAnalysis(Long alertId, String analysis) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("alertId",   alertId);
            payload.put("analysis",  analysis);
            payload.put("timestamp", LocalDateTime.now().toString());

            messagingTemplate.convertAndSend("/topic/ai-analysis", (Object) payload);

            log.info("🤖 AI analysis broadcast sent | Alert ID: {}", alertId);
        } catch (Exception e) {
            log.error("❌ Failed to broadcast AI analysis | Error: {}", e.getMessage());
        }
    }
}