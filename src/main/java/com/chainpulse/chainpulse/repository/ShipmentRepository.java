package com.chainpulse.chainpulse.repository;

import com.chainpulse.chainpulse.entity.Shipment;
import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * ShipmentRepository — handles all database operations for Shipment.
 * Contains custom queries for the dashboard and alert engine.
 */
@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    // Find shipment by tracking number e.g. "BD-2291"
    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    // Find all shipments for a specific supplier
    // SQL: SELECT * FROM shipments WHERE supplier_id = ?
    List<Shipment> findBySupplierId(Long supplierId);

    // Find all shipments with a specific status
    // e.g. findByStatus(DELAYED) → all delayed shipments
    List<Shipment> findByStatus(ShipmentStatus status);

    // Find all shipments that are NOT delivered or cancelled
    // These are the "active" shipments ChainPulse monitors
    List<Shipment> findByStatusNotIn(List<ShipmentStatus> statuses);
    // Add this to ShipmentRepository
    List<Shipment> findByStatusIn(List<ShipmentStatus> statuses);
    /**
     * Custom JPQL query — count how many shipments per supplier
     * are currently delayed or stuck.
     * Used for the supplier health score calculation.
     */
    @Query("SELECT COUNT(s) FROM Shipment s WHERE s.supplier.id = :supplierId " +
            "AND s.status IN ('DELAYED', 'STUCK')")
    Long countDelayedBySupplier(Long supplierId);
}