package com.chainpulse.chainpulse.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.time.LocalDateTime;

/**
 * Supplier — represents a logistics/freight company in our system.
 * Examples: BlueDart, Delhivery, DTDC, Ecom Express, Shadowfax.
 * Each supplier has an SLA (Service Level Agreement) threshold —
 * the maximum hours a shipment is allowed to be in transit.
 * If a shipment exceeds this, ChainPulse fires an alert.
 */
@Entity                          // Tells Spring this class maps to a database table
@Table(name = "suppliers")       // The actual table name in PostgreSQL
@Data                            // Lombok: auto-generates getters, setters, toString, equals
@NoArgsConstructor               // Lombok: generates empty constructor (required by JPA)
@AllArgsConstructor              // Lombok: generates constructor with all fields
@JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class Supplier {

    @Id                                                    // This is the primary key
    @GeneratedValue(strategy = GenerationType.IDENTITY)    // Auto-increment (1, 2, 3...)
    private Long id;

    @Column(nullable = false)      // Name is required — cannot be null in DB
    private String name;           // Supplier name e.g. "BlueDart"

    @Column(nullable = false)      // Location is required
    private String location;       // City e.g. "Mumbai", "Delhi"

    @Column(name = "contact_email")   // Maps to column "contact_email" in DB
    private String contactEmail;      // Optional contact email for the supplier

    @Column(name = "sla_threshold_hours", nullable = false)
    private Integer slaThresholdHours;  // Max allowed transit hours e.g. 48
    // If shipment crosses this → alert fired

    @Column(name = "created_at")
    private LocalDateTime createdAt;    // When this supplier was added to the system

    /**
     * @PrePersist — this method runs automatically just before
     * a new Supplier record is saved to the database.
     * We use it to auto-set the createdAt timestamp.
     */
    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}