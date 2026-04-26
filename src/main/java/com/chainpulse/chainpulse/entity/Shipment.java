package com.chainpulse.chainpulse.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * Shipment — represents a single delivery/freight movement in the system.
 * A shipment belongs to a supplier (e.g. BlueDart handles this shipment).
 * It moves from an origin city to a destination city.
 * ChainPulse monitors each shipment's status in real-time.
 * If it gets DELAYED or STUCK beyond the SLA threshold → alert is fired.
 */
@Entity
@Table(name = "shipments")       // Maps to "shipments" table in PostgreSQL
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Which supplier is handling this shipment.
     * @ManyToOne — many shipments can belong to one supplier.
     * @JoinColumn — the foreign key column in the shipments table.
     * LAZY fetch — only load supplier data when we actually need it (performance).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    /**
     * Shipment status — tracks where the shipment is in its journey.
     * Stored as a String in DB (e.g. "IN_TRANSIT", "DELAYED").
     * We use an enum so the code is type-safe and readable.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status;

    @Column(nullable = false)
    private String origin;          // Where the shipment started e.g. "Mumbai"

    @Column(nullable = false)
    private String destination;     // Where it's going e.g. "Hyderabad"

    @Column(name = "tracking_number", unique = true)
    private String trackingNumber;  // Unique tracking ID e.g. "BD-2291"

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;   // When the shipment left the warehouse

    @Column(name = "expected_at")
    private LocalDateTime expectedAt;     // When it should arrive (SLA deadline)

    @Column(name = "actual_at")
    private LocalDateTime actualAt;       // When it actually arrived (null if not yet)

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Auto-set createdAt when first saved to DB.
     */
    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Auto-update updatedAt every time the shipment record is modified.
     * e.g. when status changes from IN_TRANSIT → DELAYED.
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ShipmentStatus — all possible states a shipment can be in.
     * IN_TRANSIT  → moving normally, no issues
     * DELAYED     → behind schedule but still moving
     * STUCK       → not moving at all (checkpoint blocked)
     * DELIVERED   → successfully reached destination
     * CANCELLED   → shipment was cancelled
     */
    public enum ShipmentStatus {
        IN_TRANSIT,
        DELAYED,
        STUCK,
        DELIVERED,
        CANCELLED
    }
}