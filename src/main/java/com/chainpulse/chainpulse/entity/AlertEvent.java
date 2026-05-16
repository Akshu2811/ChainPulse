package com.chainpulse.chainpulse.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * AlertEvent — represents a single alert that was fired by ChainPulse.
 * Every time an SLA rule is breached, one AlertEvent record is created.
 *
 * Think of it as the "incident log" of the system.
 * Example:
 * - BlueDart shipment BD-2291 was IN_TRANSIT for 52 hours (threshold: 48h)
 * - SLA rule MAX_TRANSIT_HOURS was breached
 * - ChainPulse creates an AlertEvent with severity CRITICAL
 * - This alert is pushed to the dashboard via WebSocket in real-time
 *
 * Alerts can be resolved once the issue is fixed.
 */
@Entity
@Table(name = "alert_events")    // Maps to "alert_events" table in PostgreSQL
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which shipment triggered this alert.
     * @ManyToOne — one shipment can have multiple alerts over its lifetime.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id", nullable = true)
    private Shipment shipment;

    /**
     * Which supplier's shipment this alert is about.
     * Stored separately for quick querying —
     * e.g. "show me all alerts for BlueDart today"
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    /**
     * Which SLA rule was breached to trigger this alert.
     * Uses the same RuleType enum defined in SlaRule.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private SlaRule.RuleType ruleType;

    /**
     * How serious is this alert?
     * Uses the same AlertSeverity enum defined in SlaRule.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlaRule.AlertSeverity severity;

    /**
     * Human-readable description of what went wrong.
     * Example: "Shipment BD-2291 has been IN_TRANSIT for 52 hours.
     *           SLA threshold is 48 hours. Supplier: BlueDart, Route: Mumbai → Hyderabad"
     */
    @Column(nullable = false, length = 1000)
    private String message;

    /**
     * Is this alert still active or has it been resolved?
     * false = active (problem still exists)
     * true  = resolved (someone fixed it and marked it done)
     */
    @Column(nullable = false)
    private Boolean resolved = false;

    /**
     * When was this alert resolved?
     * Null if still active.
     * Set when someone calls PUT /api/alerts/{id}/resolve
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /**
     * Who resolved this alert? (optional for now)
     * Can be expanded later to track which team member resolved it.
     */
    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;    // When this alert was fired

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * AI-generated root cause analysis from Gemini.
     * Populated asynchronously after a CRITICAL alert fires.
     * Stored so it's never regenerated for the same alert.
     */
    @Column(name = "ai_analysis", length = 8000)
    private String aiAnalysis;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.resolved = false;          // Every new alert starts as unresolved
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}