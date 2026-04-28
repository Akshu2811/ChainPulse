package com.chainpulse.chainpulse.kafka;

import com.chainpulse.chainpulse.kafka.dto.ShipmentEventDto;
import com.chainpulse.chainpulse.service.RedisService;
import com.chainpulse.chainpulse.service.SlaRuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

/**
 * ShipmentEventConsumer — receives shipment events from Kafka.
 *
 * Updated in Day 3 to wire into SlaRuleEngine.
 * Now every received event is evaluated for SLA breaches.
 *
 * Flow:
 * Kafka → consumeShipmentEvent() → SlaRuleEngine.evaluate() → AlertEvent saved
 */
@Slf4j
@Service
public class ShipmentEventConsumer {

    /**
     * SlaRuleEngine — injected here so every consumed event
     * is immediately evaluated for SLA breaches.
     */
    @Autowired
    private SlaRuleEngine slaRuleEngine;

    @Autowired
    private RedisService redisService;

    /**
     * ObjectMapper — converts JSON string → ShipmentEventDto.
     * JavaTimeModule handles LocalDateTime fields correctly.
     */
    private final ObjectMapper objectMapper;

    public ShipmentEventConsumer() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * consumeShipmentEvent — processes every incoming Kafka message.
     *
     * Steps:
     * 1. Receive raw JSON string from Kafka
     * 2. Convert JSON → ShipmentEventDto
     * 3. Log the event
     * 4. Pass to SlaRuleEngine for breach evaluation
     *
     * @param message — raw JSON string from "shipment-events" topic
     */
    @KafkaListener(
            topics = "shipment-events",
            groupId = "chainpulse-group",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeShipmentEvent(String message) {
        try {
            // Step 1: Convert JSON string → ShipmentEventDto
            ShipmentEventDto eventDto = objectMapper.readValue(message,
                    ShipmentEventDto.class);

            // Step 2: Log the received event
            log.info("📦 Shipment event received | " +
                            "Tracking: {} | Supplier: {} | Status: {} | Route: {} → {}",
                    eventDto.getTrackingNumber(),
                    eventDto.getSupplierName(),
                    eventDto.getStatus(),
                    eventDto.getOrigin(),
                    eventDto.getDestination());

            // Step 3: Pass to SlaRuleEngine — evaluates all active rules
            // If any rule is breached → AlertEvent is created automatically
            slaRuleEngine.evaluate(eventDto);

            // Cache the latest shipment status in Redis
// This keeps Redis up to date with every Kafka event
            redisService.cacheShipmentStatus(
                    eventDto.getTrackingNumber(),
                    eventDto.getStatus().name()
            );

        } catch (Exception e) {
            // Bad/corrupt message — log and skip, don't crash the consumer
            log.error("❌ Failed to process shipment event | " +
                            "Raw message: {} | Error: {}",
                    message, e.getMessage());
        }
    }
}