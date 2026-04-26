package com.chainpulse.chainpulse.kafka.dto;

import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * ShipmentEventDto — the message that flows through Kafka.
 *
 * Every time a shipment status changes, this object is created,
 * converted to JSON, and sent to the "shipment-events" Kafka topic.
 *
 * DTO = Data Transfer Object — it carries data between systems.
 * It's separate from the Shipment entity because Kafka messages
 * don't need all the database fields, just the important ones.
 *
 * Example message flowing through Kafka:
 * {
 *   "shipmentId": 5,
 *   "supplierId": 2,
 *   "trackingNumber": "BD-2291",
 *   "status": "DELAYED",
 *   "origin": "Mumbai",
 *   "destination": "Hyderabad",
 *   "dispatchedAt": "2026-04-26T09:00:00",
 *   "expectedAt": "2026-04-26T17:00:00",
 *   "eventTimestamp": "2026-04-26T13:00:00"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentEventDto {

    private Long shipmentId;          // Which shipment this event is about
    private Long supplierId;          // Which supplier handles this shipment
    private String supplierName;      // e.g. "BlueDart" — for quick display
    private String trackingNumber;    // e.g. "BD-2291"
    private ShipmentStatus status;    // Current status: IN_TRANSIT, DELAYED, etc.
    private String origin;            // Where it came from e.g. "Mumbai"
    private String destination;       // Where it's going e.g. "Hyderabad"
    private LocalDateTime dispatchedAt;   // When it left the warehouse
    private LocalDateTime expectedAt;     // When it should arrive
    private LocalDateTime eventTimestamp; // When this event was created
}