package com.chainpulse.chainpulse.repository;

import com.chainpulse.chainpulse.entity.AlertEvent;
import com.chainpulse.chainpulse.entity.SlaRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AlertEventRepository — handles all database operations for AlertEvent.
 * Used by the alert engine to save alerts and by the dashboard to display them.
 */
@Repository
public interface AlertEventRepository extends JpaRepository<AlertEvent, Long> {

    // Get all unresolved alerts — shown on the dashboard as "active alerts"
    List<AlertEvent> findByResolvedFalse();

    // Get all alerts for a specific supplier
    // e.g. "show me all BlueDart alerts today"
    List<AlertEvent> findBySupplierId(Long supplierId);

    // Get all unresolved alerts for a specific supplier
    List<AlertEvent> findBySupplierIdAndResolvedFalse(Long supplierId);

    // Count active (unresolved) alerts — used in dashboard stats card
    Long countByResolvedFalse();

    // Count active alerts by severity — e.g. how many CRITICAL alerts right now
    Long countByResolvedFalseAndSeverity(SlaRule.AlertSeverity severity);
    // Add this method to AlertEventRepository
    Long countByResolvedFalseAndSupplierId(Long supplierId);
    /**
     * Check if an alert already exists for this shipment + rule combination
     * within a given time window.
     * Used by Redis deduplication as a DB-level backup check.
     * Prevents duplicate alerts for the same issue.
     */
    @Query("SELECT COUNT(a) > 0 FROM AlertEvent a WHERE a.shipment.id = :shipmentId " +
            "AND a.ruleType = :ruleType AND a.resolved = false " +
            "AND a.createdAt > :since")
    boolean existsActiveAlert(Long shipmentId, SlaRule.RuleType ruleType,
                              LocalDateTime since);

    /**
     * Get alerts created after a certain date — used for the
     * disruption trend chart on the dashboard (last 7 days).
     */
    List<AlertEvent> findByCreatedAtAfter(LocalDateTime since);
}