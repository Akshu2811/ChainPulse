package com.chainpulse.chainpulse.controller;

import com.chainpulse.chainpulse.entity.Supplier;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import com.chainpulse.chainpulse.repository.ShipmentRepository;
import com.chainpulse.chainpulse.repository.SupplierRepository;
import com.chainpulse.chainpulse.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SupplierController — REST API endpoints for supplier data.
 *
 * Exposes:
 * GET /api/suppliers           → list all suppliers
 * GET /api/suppliers/{id}      → get one supplier by ID
 * GET /api/suppliers/{id}/health → get supplier SLA health score
 */
@Slf4j
@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private RedisService redisService;

    /**
     * GET /api/suppliers
     * Returns all suppliers in the system.
     * Used by dashboard to render supplier health panel.
     */
    @GetMapping
    public ResponseEntity<List<Supplier>> getAllSuppliers() {
        log.debug("GET /api/suppliers");
        List<Supplier> suppliers = supplierRepository.findAll();
        return ResponseEntity.ok(suppliers);
    }

    /**
     * GET /api/suppliers/{id}
     * Returns a single supplier by ID.
     * Returns 404 if not found.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Supplier> getSupplierById(@PathVariable Long id) {
        log.debug("GET /api/suppliers/{}", id);
        return supplierRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/suppliers/{id}/health
     * Returns the health score of a supplier.
     *
     * Health score = how many active (unresolved) alerts this supplier has.
     * Used by dashboard to show green/amber/red status badge per supplier.
     *
     * Response example:
     * {
     *   "supplierId": 1,
     *   "supplierName": "BlueDart",
     *   "activeAlerts": 3,
     *   "status": "AT_RISK"
     * }
     */
    @GetMapping("/{id}/health")
    public ResponseEntity<Map<String, Object>> getSupplierHealth(@PathVariable Long id) {
        log.debug("GET /api/suppliers/{}/health", id);

        return supplierRepository.findById(id)
                .map(supplier -> {

                    // Check Redis cache first — avoids DB query if cached
                    String cachedHealth = redisService.getSupplierHealth(id);

                    String status;
                    Long activeAlerts;

                    if (cachedHealth != null) {
                        // Cache hit — use cached value, skip DB query
                        log.debug("✅ Supplier health cache hit | Supplier: {} | Status: {}",
                                supplier.getName(), cachedHealth);
                        status = cachedHealth;
                        activeAlerts = alertEventRepository.countByResolvedFalseAndSupplierId(id);
                    } else {
                        // Cache miss — query DB and cache the result
                        log.debug("❌ Supplier health cache miss | Supplier: {} — querying DB",
                                supplier.getName());
                        activeAlerts = alertEventRepository.countByResolvedFalseAndSupplierId(id);

                        if (activeAlerts == 0) {
                            status = "HEALTHY";
                        } else if (activeAlerts <= 3) {
                            status = "AT_RISK";
                        } else {
                            status = "CRITICAL";
                        }

                        // Write to Redis cache — expires in 5 minutes
                        redisService.cacheSupplierHealth(id, status);
                    }

                    Map<String, Object> health = new HashMap<>();
                    health.put("supplierId", supplier.getId());
                    health.put("supplierName", supplier.getName());
                    health.put("location", supplier.getLocation());
                    health.put("slaThresholdHours", supplier.getSlaThresholdHours());
                    health.put("activeAlerts", activeAlerts);
                    health.put("status", status);
                    health.put("cachedResult", cachedHealth != null);

                    return ResponseEntity.ok(health);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}