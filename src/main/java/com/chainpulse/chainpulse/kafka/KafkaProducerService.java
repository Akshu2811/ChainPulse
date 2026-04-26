package com.chainpulse.chainpulse.kafka;

import com.chainpulse.chainpulse.config.KafkaConfig;
import com.chainpulse.chainpulse.kafka.dto.ShipmentEventDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * KafkaProducerService — responsible for SENDING shipment events to Kafka.
 *
 * Think of this as the "publisher" in our system.
 * Whenever a shipment status changes, this service:
 * 1. Takes a ShipmentEventDto object
 * 2. Converts it to a JSON string using ObjectMapper
 * 3. Sends that JSON string to the "shipment-events" Kafka topic
 *
 * The ShipmentEventSimulator calls this service every 5 seconds
 * to simulate real shipment events flowing through the system.
 */
@Slf4j          // Lombok: auto-generates a logger (log.info, log.error etc.)
@Service        // Marks this as a Spring service bean — injectable anywhere
public class KafkaProducerService {

    /**
     * KafkaTemplate — Spring's tool for sending messages to Kafka.
     * Configured in KafkaConfig.java with String key and String value.
     */
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * ObjectMapper — Jackson's JSON converter.
     * Converts ShipmentEventDto Java object → JSON string.
     * We create it here with JavaTimeModule so LocalDateTime
     * fields are serialized correctly as readable strings.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructor — sets up ObjectMapper with proper configuration.
     * JavaTimeModule: handles Java 8+ date/time types (LocalDateTime etc.)
     * WRITE_DATES_AS_TIMESTAMPS=false: writes dates as "2026-04-26T09:00:00"
     * instead of ugly numbers like 1745640600000.
     */
    public KafkaProducerService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * sendShipmentEvent — the main method to publish a shipment event.
     *
     * How it works:
     * 1. Convert ShipmentEventDto → JSON string
     * 2. Use trackingNumber as the Kafka message key
     *    (same tracking number always goes to same partition — ordering guarantee)
     * 3. Send to "shipment-events" topic
     * 4. Log success or failure
     *
     * @param eventDto — the shipment event to publish
     */
    public void sendShipmentEvent(ShipmentEventDto eventDto) {
        try {
            // Step 1: Convert the DTO object to a JSON string
            // Example output: {"shipmentId":5,"trackingNumber":"BD-2291","status":"DELAYED",...}
            String jsonMessage = objectMapper.writeValueAsString(eventDto);

            // Step 2: Use tracking number as the message key
            // This ensures events for the same shipment always go to the same partition
            String messageKey = eventDto.getTrackingNumber();

            // Step 3: Send to Kafka topic asynchronously
            // CompletableFuture = the result will be available in the future (non-blocking)
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(KafkaConfig.SHIPMENT_EVENTS_TOPIC, messageKey, jsonMessage);

            // Step 4: Log success or failure when Kafka responds
            future.whenComplete((result, exception) -> {
                if (exception == null) {
                    // Success — message was accepted by Kafka broker
                    log.info("✅ Shipment event sent successfully | " +
                                    "Tracking: {} | Status: {} | Partition: {} | Offset: {}",
                            eventDto.getTrackingNumber(),
                            eventDto.getStatus(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    // Failure — Kafka rejected or couldn't receive the message
                    log.error("❌ Failed to send shipment event | " +
                                    "Tracking: {} | Error: {}",
                            eventDto.getTrackingNumber(),
                            exception.getMessage());
                }
            });

        } catch (JsonProcessingException e) {
            // This happens if ShipmentEventDto cannot be converted to JSON
            // Very rare but we handle it gracefully
            log.error("❌ Failed to serialize ShipmentEventDto to JSON | " +
                            "Tracking: {} | Error: {}",
                    eventDto.getTrackingNumber(),
                    e.getMessage());
        }
    }
}