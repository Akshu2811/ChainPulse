package com.chainpulse.chainpulse.kafka;

import com.chainpulse.chainpulse.kafka.dto.ShipmentEventDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * ShipmentEventConsumer — responsible for RECEIVING shipment events from Kafka.
 *
 * Think of this as the "subscriber" in our system.
 * It listens to the "shipment-events" Kafka topic 24/7.
 * Every time a new message arrives:
 * 1. Receives the raw JSON string from Kafka
 * 2. Converts it back to a ShipmentEventDto object
 * 3. Logs the event (later: passes to SlaRuleEngine for breach evaluation)
 *
 * @KafkaListener makes this method run automatically
 * whenever a new message appears in the topic.
 */
@Slf4j
@Service
public class ShipmentEventConsumer {

    /**
     * ObjectMapper — Jackson's JSON converter.
     * Converts JSON string → ShipmentEventDto Java object.
     * Same configuration as in KafkaProducerService —
     * JavaTimeModule handles LocalDateTime fields correctly.
     */
    private final ObjectMapper objectMapper;

    public ShipmentEventConsumer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * consumeShipmentEvent — the main method that processes incoming events.
     *
     * @KafkaListener tells Spring:
     * - topics: listen to "shipment-events" topic
     * - groupId: this consumer belongs to "chainpulse-group"
     * - containerFactory: use the factory we configured in KafkaConfig
     *
     * How it works:
     * 1. Kafka delivers a JSON string message to this method
     * 2. We convert JSON string → ShipmentEventDto using ObjectMapper
     * 3. Log the received event with key details
     * 4. (Day 3) Pass to SlaRuleEngine to check for SLA breaches
     *
     * @param message — raw JSON string received from Kafka topic
     */
    @KafkaListener(
            topics = "shipment-events",
            groupId = "chainpulse-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeShipmentEvent(String message) {
        try {
            // Step 1: Convert JSON string back to ShipmentEventDto object
            ShipmentEventDto eventDto = objectMapper.readValue(message, ShipmentEventDto.class);

            // Step 2: Log the received event with key details
            log.info("📦 Shipment event received | " +
                            "Tracking: {} | Supplier: {} | Status: {} | " +
                            "Route: {} → {} | Expected: {}",
                    eventDto.getTrackingNumber(),
                    eventDto.getSupplierName(),
                    eventDto.getStatus(),
                    eventDto.getOrigin(),
                    eventDto.getDestination(),
                    eventDto.getExpectedAt());

            // Step 3: TODO — Day 3: pass to SlaRuleEngine
            // slaRuleEngine.evaluate(eventDto);

        } catch (Exception e) {
            // If message is malformed or cannot be parsed — log and skip
            // Don't crash the consumer for one bad message
            log.error("❌ Failed to process shipment event | " +
                            "Raw message: {} | Error: {}",
                    message, e.getMessage());
        }
    }
}