package com.chainpulse.chainpulse.controller;

import com.chainpulse.chainpulse.entity.Shipment;
import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import com.chainpulse.chainpulse.repository.ShipmentRepository;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ShipmentController — REST API endpoints for shipment data.
 *
 * Exposes:
 * GET  /api/shipments          → all shipments
 * GET  /api/shipments/{id}     → single shipment by ID
 * GET  /api/shipments/delayed  → all delayed or stuck shipments
 * POST /api/shipments          → create a new shipment record
 */
@Slf4j
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    @Autowired
    private ShipmentRepository shipmentRepository;

    /**
     * GET /api/shipments
     * Returns all shipments in the system.
     */
    @GetMapping
    public ResponseEntity<List<Shipment>> getAllShipments() {
        log.debug("GET /api/shipments");
        List<Shipment> shipments = shipmentRepository.findAll();
        return ResponseEntity.ok(shipments);
    }

    /**
     * GET /api/shipments/{id}
     * Returns a single shipment by ID.
     * Returns 404 if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Shipment> getShipmentById(@PathVariable Long id) {
        log.debug("GET /api/shipments/{}", id);
        return shipmentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/shipments/delayed
     * Returns all shipments that are currently DELAYED or STUCK.
     * Used by dashboard to highlight problem shipments.
     */
    @GetMapping("/delayed")
    public ResponseEntity<List<Shipment>> getDelayedShipments() {
        log.debug("GET /api/shipments/delayed");
        List<Shipment> delayed = shipmentRepository.findByStatusIn(
                List.of(ShipmentStatus.DELAYED, ShipmentStatus.STUCK)
        );
        return ResponseEntity.ok(delayed);
    }

    /**
     * POST /api/shipments
     * Creates a new shipment record in the database.
     * Used to register real shipments (not simulator ones).
     */
    @PostMapping
    public ResponseEntity<Shipment> createShipment(@Valid @RequestBody Shipment shipment) {
        log.debug("POST /api/shipments | Tracking: {}", shipment.getTrackingNumber());
        Shipment saved = shipmentRepository.save(shipment);
        log.info("✅ Shipment created | ID: {} | Tracking: {}",
                saved.getId(), saved.getTrackingNumber());
        return ResponseEntity.ok(saved);
    }
}