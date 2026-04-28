package com.chainpulse.chainpulse.kafka;

import com.chainpulse.chainpulse.kafka.dto.ShipmentEventDto;
import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

/**
 * ShipmentEventSimulator — simulates real shipment events flowing through the system.
 *
 * In a real system, actual supplier APIs would push shipment updates.
 * Since we don't have real supplier APIs, this simulator mimics that behavior.
 * Every 5 seconds it creates a random shipment event and publishes it to Kafka.
 *
 * Think of it as a "fake supplier" that keeps sending updates like:
 * "Hey, shipment BD-2291 is now DELAYED"
 * "Hey, shipment DL-1042 has been DELIVERED"
 * "Hey, shipment DC-3310 is STUCK at checkpoint"
 *
 * This makes our dashboard come alive with real-time data during demos!
 * @EnableScheduling — tells Spring to activate the @Scheduled annotation.
 */
@Slf4j
@Component
@EnableScheduling
public class ShipmentEventSimulator {

    @Autowired
    private KafkaProducerService kafkaProducerService;

    private final Random random = new Random();

    /**
     * Mock suppliers — 5 real Indian logistics companies.
     * Each entry: {supplierId, supplierName, trackingPrefix, origin, destination}
     */
    private final List<Object[]> SUPPLIERS = List.of(
            new Object[]{1L, "BlueDart",      "BD", "Mumbai",    "Hyderabad"},
            new Object[]{2L, "Delhivery",     "DL", "Delhi",     "Bangalore"},
            new Object[]{3L, "DTDC",          "DC", "Chennai",   "Pune"},
            new Object[]{4L, "Ecom Express",  "EE", "Kolkata",   "Ahmedabad"},
            new Object[]{5L, "Shadowfax",     "SF", "Bangalore", "Mumbai"}
    );

    /**
     * Shipment statuses with weighted probability.
     * Most shipments should be IN_TRANSIT (normal).
     * Some get DELAYED or STUCK (triggers alerts).
     * Few get DELIVERED (resolved).
     *
     * Weights: IN_TRANSIT=40%, DELAYED=25%, STUCK=20%, DELIVERED=15%
     */
    private final List<ShipmentStatus> STATUSES = List.of(
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.IN_TRANSIT,
            ShipmentStatus.DELAYED,
            ShipmentStatus.DELAYED,
            ShipmentStatus.DELAYED,
            ShipmentStatus.STUCK,
            ShipmentStatus.STUCK,
            ShipmentStatus.DELIVERED
    );

    /**
     * simulateShipmentEvent — fires every 5 seconds automatically.
     *
     * @Scheduled(fixedRate = 5000) means:
     * "Run this method every 5000 milliseconds (5 seconds)"
     *
     * Each time it runs:
     * 1. Picks a random supplier from our list
     * 2. Picks a random shipment status (weighted)
     * 3. Generates a random tracking number
     * 4. Creates a ShipmentEventDto
     * 5. Sends it to Kafka via KafkaProducerService
     */
    @Scheduled(fixedRate = 15000)
    public void simulateShipmentEvent() {

        // Step 1: Pick a random supplier
        Object[] supplier = SUPPLIERS.get(random.nextInt(SUPPLIERS.size()));
        Long supplierId      = (Long)   supplier[0];
        String supplierName  = (String) supplier[1];
        String trackingPrefix = (String) supplier[2];
        String origin        = (String) supplier[3];
        String destination   = (String) supplier[4];

        // Step 2: Pick a random status (weighted towards IN_TRANSIT)
        ShipmentStatus status = STATUSES.get(random.nextInt(STATUSES.size()));

        // Step 3: Generate a random tracking number
        // e.g. "BD-4823" for BlueDart, "DL-1042" for Delhivery
        String trackingNumber = trackingPrefix + "-" + (1000 + random.nextInt(9000));

        // Step 4: Generate realistic timestamps
        LocalDateTime now          = LocalDateTime.now();
        LocalDateTime dispatchedAt = now.minusHours(random.nextInt(72));  // Dispatched 0-72 hrs ago
        LocalDateTime expectedAt   = dispatchedAt.plusHours(48);          // Expected 48 hrs after dispatch

        // Step 5: Build the ShipmentEventDto
        ShipmentEventDto eventDto = new ShipmentEventDto(
                (long) random.nextInt(1000),  // shipmentId — random for simulation
                supplierId,
                supplierName,
                trackingNumber,
                status,
                origin,
                destination,
                dispatchedAt,
                expectedAt,
                now                           // eventTimestamp = right now
        );

        // Step 6: Send to Kafka — KafkaProducerService handles the rest
        log.info("🚀 Simulating event | Tracking: {} | Supplier: {} | Status: {}",
                trackingNumber, supplierName, status);

        kafkaProducerService.sendShipmentEvent(eventDto);
    }
}