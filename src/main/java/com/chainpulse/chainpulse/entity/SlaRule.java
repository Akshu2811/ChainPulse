package com.chainpulse.chainpulse.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * SlaRule — defines the rules that ChainPulse uses to detect disruptions.
 * Think of it as a "trigger condition" — when a shipment breaks this rule,
 * an alert is fired automatically.
 *
 * Examples of rules:
 * - "If any BlueDart shipment is IN_TRANSIT for more than 48 hours → fire CRITICAL alert"
 * - "If any shipment is STUCK at a checkpoint for more than 4 hours → fire WARNING alert"
 * - "If any shipment misses its expected delivery time → fire CRITICAL alert"
 *
 * Rules can be global (apply to all suppliers) or
 * supplier-specific (apply to one supplier only).
 */
@Entity
@Table(name = "sla_rules")       // Maps to "sla_rules" table in PostgreSQL
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SlaRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which supplier this rule applies to.
     * Optional — if null, the rule applies to ALL suppliers globally.
     * @ManyToOne — many rules can belong to one supplier.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")   // nullable — null means global rule
    private Supplier supplier;

    /**
     * The type of rule being evaluated.
     * Stored as String in DB for readability.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false)
    private RuleType ruleType;

    /**
     * The threshold value for this rule.
     * Meaning depends on rule type:
     * - For MAX_TRANSIT_HOURS → number of hours e.g. 48
     * - For CHECKPOINT_TIMEOUT → number of hours e.g. 4
     * - For DELIVERY_DEADLINE_MISS → 0 (any miss triggers alert)
     */
    @Column(name = "threshold_value", nullable = false)
    private Integer thresholdValue;

    /**
     * How serious is this rule breach?
     * CRITICAL → immediate action needed
     * WARNING  → needs attention soon
     * INFO     → just a heads up
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity severity;

    @Column(nullable = false)
    private Boolean active = true;    // Can enable/disable rules without deleting them

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * RuleType — the three types of SLA rules ChainPulse can evaluate.
     *
     * MAX_TRANSIT_HOURS      → shipment has been IN_TRANSIT too long
     * CHECKPOINT_TIMEOUT     → shipment has been STUCK at a checkpoint too long
     * DELIVERY_DEADLINE_MISS → shipment missed its expected delivery time
     */
    public enum RuleType {
        MAX_TRANSIT_HOURS,
        CHECKPOINT_TIMEOUT,
        DELIVERY_DEADLINE_MISS
    }

    /**
     * AlertSeverity — how critical is the breach?
     * Used across SlaRule and AlertEvent.
     * Defined here as it's the source of truth for severity levels.
     */
    public enum AlertSeverity {
        CRITICAL,   // e.g. shipment missing for 48+ hours
        WARNING,    // e.g. shipment delayed by 4 hours
        INFO        // e.g. minor checkpoint delay
    }
}