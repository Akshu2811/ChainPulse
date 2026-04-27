package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.entity.AlertEvent;
import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import com.chainpulse.chainpulse.entity.SlaRule;
import com.chainpulse.chainpulse.entity.SlaRule.RuleType;
import com.chainpulse.chainpulse.entity.Supplier;
import com.chainpulse.chainpulse.kafka.dto.ShipmentEventDto;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import com.chainpulse.chainpulse.repository.ShipmentRepository;
import com.chainpulse.chainpulse.repository.SlaRuleRepository;
import com.chainpulse.chainpulse.repository.SupplierRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * SlaRuleEngine — the brain of ChainPulse.
 *
 * Every time a shipment event arrives from Kafka, this engine:
 * 1. Loads all active SLA rules from the database
 * 2. Evaluates each rule against the shipment event
 * 3. If a rule is breached → creates an AlertEvent record
 *
 * Think of it like a referee watching every shipment:
 * - "This shipment has been moving for 52 hours — that's over the 48hr limit → ALERT!"
 * - "This shipment has been stuck for 5 hours — that's over the 4hr limit → ALERT!"
 * - "This shipment missed its delivery deadline → ALERT!"
 */
@Slf4j
@Service
public class SlaRuleEngine {

    @Autowired
    private SlaRuleRepository slaRuleRepository;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    /**
     * evaluate — main method called by ShipmentEventConsumer for every event.
     *
     * Steps:
     * 1. Load global rules (apply to all suppliers)
     * 2. Load supplier-specific rules (apply to this supplier only)
     * 3. Combine both lists
     * 4. Evaluate each rule against the shipment event
     * 5. Create alerts for any breaches found
     *
     * @param eventDto — the shipment event received from Kafka
     */
    public void evaluate(ShipmentEventDto eventDto) {
        log.debug("🔍 Evaluating SLA rules for shipment: {} | Status: {}",
                eventDto.getTrackingNumber(), eventDto.getStatus());

        // Load global rules — apply to ALL suppliers
        List<SlaRule> globalRules = slaRuleRepository.findBySupplierIsNullAndActiveTrue();

        // Load supplier-specific rules — apply to THIS supplier only
        List<SlaRule> supplierRules = slaRuleRepository
                .findBySupplierIdAndActiveTrue(eventDto.getSupplierId());

        // Combine both lists into one
        List<SlaRule> allRules = new ArrayList<>();
        allRules.addAll(globalRules);
        allRules.addAll(supplierRules);

        if (allRules.isEmpty()) {
            log.debug("No active SLA rules found — skipping evaluation");
            return;
        }

        // Evaluate each rule one by one
        for (SlaRule rule : allRules) {
            evaluateRule(rule, eventDto);
        }
    }

    /**
     * evaluateRule — checks a single SLA rule against a shipment event.
     * Routes to the correct evaluation method based on rule type.
     *
     * @param rule     — the SLA rule to check
     * @param eventDto — the shipment event to check against
     */
    private void evaluateRule(SlaRule rule, ShipmentEventDto eventDto) {
        switch (rule.getRuleType()) {
            case MAX_TRANSIT_HOURS ->
                    evaluateMaxTransitHours(rule, eventDto);
            case CHECKPOINT_TIMEOUT ->
                    evaluateCheckpointTimeout(rule, eventDto);
            case DELIVERY_DEADLINE_MISS ->
                    evaluateDeliveryDeadlineMiss(rule, eventDto);
            default ->
                    log.warn("Unknown rule type: {}", rule.getRuleType());
        }
    }

    /**
     * evaluateMaxTransitHours — checks if shipment has been IN_TRANSIT too long.
     *
     * Example: Rule says max 48 hours.
     * Shipment was dispatched 52 hours ago and is still IN_TRANSIT.
     * → Breach! Fire a WARNING alert.
     */
    private void evaluateMaxTransitHours(SlaRule rule, ShipmentEventDto eventDto) {
        // Only applies to shipments that are IN_TRANSIT or DELAYED
        if (eventDto.getStatus() != ShipmentStatus.IN_TRANSIT &&
                eventDto.getStatus() != ShipmentStatus.DELAYED) {
            return;
        }

        if (eventDto.getDispatchedAt() == null) return;

        // Calculate how many hours since dispatch
        long hoursInTransit = ChronoUnit.HOURS.between(
                eventDto.getDispatchedAt(),
                LocalDateTime.now()
        );

        log.debug("Shipment {} in transit for {} hours (threshold: {}h)",
                eventDto.getTrackingNumber(), hoursInTransit, rule.getThresholdValue());

        // If hours exceeded threshold → breach!
        if (hoursInTransit > rule.getThresholdValue()) {
            String message = String.format(
                    "Shipment %s has been IN_TRANSIT for %d hours. " +
                            "SLA threshold is %d hours. " +
                            "Supplier: %s | Route: %s → %s",
                    eventDto.getTrackingNumber(),
                    hoursInTransit,
                    rule.getThresholdValue(),
                    eventDto.getSupplierName(),
                    eventDto.getOrigin(),
                    eventDto.getDestination()
            );
            createAlert(rule, eventDto, message);
        }
    }

    /**
     * evaluateCheckpointTimeout — checks if shipment has been STUCK too long.
     *
     * Example: Rule says max 4 hours stuck.
     * Shipment status is STUCK and was dispatched 6 hours ago.
     * → Breach! Fire a CRITICAL alert.
     */
    private void evaluateCheckpointTimeout(SlaRule rule, ShipmentEventDto eventDto) {
        // Only applies to STUCK shipments
        if (eventDto.getStatus() != ShipmentStatus.STUCK) {
            return;
        }

        if (eventDto.getDispatchedAt() == null) return;

        // Calculate how many hours since dispatch (approximation of time stuck)
        long hoursStuck = ChronoUnit.HOURS.between(
                eventDto.getDispatchedAt(),
                LocalDateTime.now()
        );

        log.debug("Shipment {} STUCK for {} hours (threshold: {}h)",
                eventDto.getTrackingNumber(), hoursStuck, rule.getThresholdValue());

        if (hoursStuck > rule.getThresholdValue()) {
            String message = String.format(
                    "Shipment %s has been STUCK at a checkpoint for %d hours. " +
                            "Maximum allowed: %d hours. " +
                            "Supplier: %s | Route: %s → %s. Immediate action required!",
                    eventDto.getTrackingNumber(),
                    hoursStuck,
                    rule.getThresholdValue(),
                    eventDto.getSupplierName(),
                    eventDto.getOrigin(),
                    eventDto.getDestination()
            );
            createAlert(rule, eventDto, message);
        }
    }

    /**
     * evaluateDeliveryDeadlineMiss — checks if shipment missed delivery deadline.
     *
     * Example: Shipment was expected at 5 PM but it's now 8 PM and still IN_TRANSIT.
     * → Breach! Fire a CRITICAL alert.
     */
    private void evaluateDeliveryDeadlineMiss(SlaRule rule, ShipmentEventDto eventDto) {
        // Only applies to shipments that aren't delivered or cancelled
        if (eventDto.getStatus() == ShipmentStatus.DELIVERED ||
                eventDto.getStatus() == ShipmentStatus.CANCELLED) {
            return;
        }

        if (eventDto.getExpectedAt() == null) return;

        // Check if current time is past the expected delivery time
        boolean missedDeadline = LocalDateTime.now().isAfter(eventDto.getExpectedAt());

        if (missedDeadline) {
            long hoursOverdue = ChronoUnit.HOURS.between(
                    eventDto.getExpectedAt(),
                    LocalDateTime.now()
            );

            String message = String.format(
                    "Shipment %s missed its delivery deadline! " +
                            "Was expected at: %s (overdue by %d hours). " +
                            "Current status: %s | Supplier: %s | Route: %s → %s",
                    eventDto.getTrackingNumber(),
                    eventDto.getExpectedAt(),
                    hoursOverdue,
                    eventDto.getStatus(),
                    eventDto.getSupplierName(),
                    eventDto.getOrigin(),
                    eventDto.getDestination()
            );
            createAlert(rule, eventDto, message);
        }
    }

    /**
     * createAlert — creates and saves an AlertEvent to PostgreSQL.
     *
     * Before creating:
     * - Checks if an alert already exists for this shipment + rule
     *   in the last 30 minutes (deduplication — avoids alert spam).
     * - Loads the Supplier entity from DB.
     *
     * @param rule     — the SLA rule that was breached
     * @param eventDto — the shipment event that caused the breach
     * @param message  — human-readable description of what went wrong
     */
    private void createAlert(SlaRule rule, ShipmentEventDto eventDto, String message) {
        // Deduplication check — don't fire same alert twice within 30 minutes
        LocalDateTime thirtyMinutesAgo = LocalDateTime.now().minusMinutes(30);
        boolean alertExists = alertEventRepository.existsActiveAlert(
                eventDto.getShipmentId(),
                rule.getRuleType(),
                thirtyMinutesAgo
        );

        if (alertExists) {
            log.debug("⏭️ Skipping duplicate alert for shipment: {} rule: {}",
                    eventDto.getTrackingNumber(), rule.getRuleType());
            return;
        }

        // Load supplier from DB (needed for AlertEvent entity)
        Supplier supplier = supplierRepository.findById(eventDto.getSupplierId())
                .orElse(null);

        if (supplier == null) {
            log.warn("Supplier not found for ID: {} — skipping alert",
                    eventDto.getSupplierId());
            return;
        }

        // Build the AlertEvent entity
        AlertEvent alert = new AlertEvent();
        alert.setSupplier(supplier);
        alert.setRuleType(rule.getRuleType());
        alert.setSeverity(rule.getSeverity());
        alert.setMessage(message);
        alert.setResolved(false);

        // Save to PostgreSQL
        AlertEvent saved = alertEventRepository.save(alert);

        log.warn("🚨 ALERT CREATED | ID: {} | Severity: {} | Rule: {} | Shipment: {} | {}",
                saved.getId(),
                saved.getSeverity(),
                saved.getRuleType(),
                eventDto.getTrackingNumber(),
                message);
    }
}