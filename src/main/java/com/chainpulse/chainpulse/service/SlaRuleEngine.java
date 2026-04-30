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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
    private RedisService redisService;

    @Autowired
    private SlaRuleRepository slaRuleRepository;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ShipmentRepository shipmentRepository;

    @Autowired
    private AlertBroadcastService alertBroadcastService;

    @Autowired
    private AiRootCauseService aiRootCauseService;

    private final ObjectMapper objectMapper;

    // Add constructor
    public SlaRuleEngine() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * evaluate — main method called by ShipmentEventConsumer for every event.
     *
     * Updated to use Redis cache for SLA rules.
     * Cache hit  → load rules from Redis (fast, ~0.1ms)
     * Cache miss → load from DB, store in Redis for 5 mins
     *
     * This reduces DB queries from "every event" to "every 5 minutes".
     * With events firing every 15s — that's a 20x reduction in DB load!
     */
    public void evaluate(ShipmentEventDto eventDto) {
        log.debug("🔍 Evaluating SLA rules for shipment: {} | Status: {}",
                eventDto.getTrackingNumber(), eventDto.getStatus());

        List<SlaRule> allRules = loadRules(eventDto.getSupplierId());

        if (allRules.isEmpty()) {
            log.debug("No active SLA rules found — skipping evaluation");
            return;
        }

        for (SlaRule rule : allRules) {
            evaluateRule(rule, eventDto);
        }
    }

    /**
     * loadRules — loads SLA rules from Redis cache or DB.
     *
     * Strategy:
     * 1. Try Redis cache first
     * 2. Cache hit → deserialize and return
     * 3. Cache miss → load from DB → serialize → store in Redis → return
     */
    private List<SlaRule> loadRules(Long supplierId) {
        try {
            // Step 1: Check Redis cache
            String cachedRules = redisService.getCachedSlaRules();

            if (cachedRules != null) {
                // Cache hit — deserialize JSON back to List<SlaRule>
                log.debug("✅ SLA rules cache hit — loading from Redis");
                return objectMapper.readValue(cachedRules,
                        new TypeReference<List<SlaRule>>() {});
            }
        } catch (Exception e) {
            // If deserialization fails — fall through to DB load
            log.warn("⚠️ Failed to deserialize cached rules — loading from DB | Error: {}",
                    e.getMessage());
        }

        // Step 2: Cache miss — load from DB
        log.debug("❌ SLA rules cache miss — loading from DB");
        List<SlaRule> globalRules   = slaRuleRepository.findBySupplierIsNullAndActiveTrue();
        List<SlaRule> supplierRules = slaRuleRepository.findBySupplierIdAndActiveTrue(supplierId);

        List<SlaRule> allRules = new ArrayList<>();
        allRules.addAll(globalRules);
        allRules.addAll(supplierRules);

        // Step 3: Store in Redis for next 5 minutes
        try {
            String rulesJson = objectMapper.writeValueAsString(allRules);
            redisService.cacheSlaRules(rulesJson);
        } catch (Exception e) {
            log.warn("⚠️ Failed to cache SLA rules in Redis | Error: {}", e.getMessage());
        }

        return allRules;
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
     * Updated in Day 4 to use Redis for deduplication instead of DB query.
     * Redis check is ~0.1ms vs DB query ~5-10ms — 50-100x faster!
     *
     * Flow:
     * 1. Check Redis — has this alert been fired in last 30 mins?
     *    YES → skip (duplicate), return immediately
     *    NO  → proceed to create alert
     * 2. Load supplier from DB
     * 3. Create and save AlertEvent to PostgreSQL
     * 4. Write dedup marker to Redis with 30-min TTL
     * 5. Cache shipment status in Redis for future reference
     *
     * @param rule     — the SLA rule that was breached
     * @param eventDto — the shipment event that caused the breach
     * @param message  — human-readable description of what went wrong
     */
    private void createAlert(SlaRule rule, ShipmentEventDto eventDto, String message) {

        // Step 1: Redis deduplication check
        // Check if we already fired this exact alert in the last 30 minutes
        // Key: "alert:dedup:{shipmentId}:{ruleType}"
        boolean isDuplicate = redisService.isDuplicateAlert(
                eventDto.getShipmentId(),
                rule.getRuleType().name()
        );

        if (isDuplicate) {
            log.debug("⏭️ Skipping duplicate alert | Shipment: {} | Rule: {} | (Redis dedup)",
                    eventDto.getTrackingNumber(), rule.getRuleType());
            return;
        }

        // Step 2: Load supplier from DB (needed for AlertEvent entity)
        Supplier supplier = supplierRepository.findById(eventDto.getSupplierId())
                .orElse(null);

        if (supplier == null) {
            log.warn("⚠️ Supplier not found for ID: {} — skipping alert",
                    eventDto.getSupplierId());
            return;
        }

        // Step 3: Build and save AlertEvent to PostgreSQL
        AlertEvent alert = new AlertEvent();
        alert.setSupplier(supplier);
        alert.setRuleType(rule.getRuleType());
        alert.setSeverity(rule.getSeverity());
        alert.setMessage(message);
        alert.setResolved(false);

        AlertEvent saved = alertEventRepository.save(alert);

        // Broadcast to all connected dashboard clients via WebSocket
        alertBroadcastService.broadcastAlert(saved);

        log.warn("🚨 ALERT CREATED | ID: {} | Severity: {} | Rule: {} | Shipment: {}",
                saved.getId(),
                saved.getSeverity(),
                saved.getRuleType(),
                eventDto.getTrackingNumber());

        // Trigger AI root cause analysis for CRITICAL alerts only
        // Run asynchronously so it doesn't slow down the Kafka consumer
        if (saved.getSeverity() == SlaRule.AlertSeverity.CRITICAL) {
            new Thread(() -> {
                try {
                    // Small delay to avoid rate limiting
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                String analysis = aiRootCauseService.analyzeAlert(saved);
                log.info("🤖 AI Root Cause Analysis | Alert ID: {} |\n{}",
                        saved.getId(), analysis);
                // Broadcast AI analysis to dashboard via WebSocket
                alertBroadcastService.broadcastAiAnalysis(saved.getId(), analysis);
            }).start();
        }

        // Step 4: Write dedup marker to Redis — expires in 30 minutes
        // This prevents the same alert from firing again for 30 mins
        redisService.markAlertFired(
                eventDto.getShipmentId(),
                rule.getRuleType().name()
        );

        // Step 5: Cache the shipment status in Redis
        // Next time this shipment comes through, we can read status from
        // Redis instead of DB — much faster
        redisService.cacheShipmentStatus(
                eventDto.getTrackingNumber(),
                eventDto.getStatus().name()
        );
    }
}