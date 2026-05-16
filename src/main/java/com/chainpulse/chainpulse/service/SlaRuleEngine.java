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

@Slf4j
@Service
public class SlaRuleEngine {

    @Autowired private RedisService redisService;
    @Autowired private SlaRuleRepository slaRuleRepository;
    @Autowired private AlertEventRepository alertEventRepository;
    @Autowired private SupplierRepository supplierRepository;
    @Autowired private ShipmentRepository shipmentRepository;
    @Autowired private AlertBroadcastService alertBroadcastService;
    @Autowired private AiRootCauseService aiRootCauseService;

    private final ObjectMapper objectMapper;

    public SlaRuleEngine() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

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

    private List<SlaRule> loadRules(Long supplierId) {
        try {
            String cachedRules = redisService.getCachedSlaRules();
            if (cachedRules != null) {
                log.debug("✅ SLA rules cache hit — loading from Redis");
                return objectMapper.readValue(cachedRules, new TypeReference<List<SlaRule>>() {});
            }
        } catch (Exception e) {
            log.warn("⚠️ Failed to deserialize cached rules — loading from DB | Error: {}", e.getMessage());
        }

        log.debug("❌ SLA rules cache miss — loading from DB");
        List<SlaRule> globalRules   = slaRuleRepository.findBySupplierIsNullAndActiveTrue();
        List<SlaRule> supplierRules = slaRuleRepository.findBySupplierIdAndActiveTrue(supplierId);

        List<SlaRule> allRules = new ArrayList<>();
        allRules.addAll(globalRules);
        allRules.addAll(supplierRules);

        try {
            String rulesJson = objectMapper.writeValueAsString(allRules);
            redisService.cacheSlaRules(rulesJson);
        } catch (Exception e) {
            log.warn("⚠️ Failed to cache SLA rules in Redis | Error: {}", e.getMessage());
        }

        return allRules;
    }

    private void evaluateRule(SlaRule rule, ShipmentEventDto eventDto) {
        switch (rule.getRuleType()) {
            case MAX_TRANSIT_HOURS    -> evaluateMaxTransitHours(rule, eventDto);
            case CHECKPOINT_TIMEOUT   -> evaluateCheckpointTimeout(rule, eventDto);
            case DELIVERY_DEADLINE_MISS -> evaluateDeliveryDeadlineMiss(rule, eventDto);
            default -> log.warn("Unknown rule type: {}", rule.getRuleType());
        }
    }

    private void evaluateMaxTransitHours(SlaRule rule, ShipmentEventDto eventDto) {
        if (eventDto.getStatus() != ShipmentStatus.IN_TRANSIT &&
                eventDto.getStatus() != ShipmentStatus.DELAYED) return;
        if (eventDto.getDispatchedAt() == null) return;

        long hoursInTransit = ChronoUnit.HOURS.between(eventDto.getDispatchedAt(), LocalDateTime.now());
        log.debug("Shipment {} in transit for {} hours (threshold: {}h)",
                eventDto.getTrackingNumber(), hoursInTransit, rule.getThresholdValue());

        if (hoursInTransit > rule.getThresholdValue()) {
            String message = String.format(
                    "Shipment %s has been IN_TRANSIT for %d hours. SLA threshold is %d hours. Supplier: %s | Route: %s → %s",
                    eventDto.getTrackingNumber(), hoursInTransit, rule.getThresholdValue(),
                    eventDto.getSupplierName(), eventDto.getOrigin(), eventDto.getDestination());
            createAlert(rule, eventDto, message);
        }
    }

    private void evaluateCheckpointTimeout(SlaRule rule, ShipmentEventDto eventDto) {
        if (eventDto.getStatus() != ShipmentStatus.STUCK) return;
        if (eventDto.getDispatchedAt() == null) return;

        long hoursStuck = ChronoUnit.HOURS.between(eventDto.getDispatchedAt(), LocalDateTime.now());
        log.debug("Shipment {} STUCK for {} hours (threshold: {}h)",
                eventDto.getTrackingNumber(), hoursStuck, rule.getThresholdValue());

        if (hoursStuck > rule.getThresholdValue()) {
            String message = String.format(
                    "Shipment %s has been STUCK at a checkpoint for %d hours. Maximum allowed: %d hours. Supplier: %s | Route: %s → %s. Immediate action required!",
                    eventDto.getTrackingNumber(), hoursStuck, rule.getThresholdValue(),
                    eventDto.getSupplierName(), eventDto.getOrigin(), eventDto.getDestination());
            createAlert(rule, eventDto, message);
        }
    }

    private void evaluateDeliveryDeadlineMiss(SlaRule rule, ShipmentEventDto eventDto) {
        if (eventDto.getStatus() == ShipmentStatus.DELIVERED ||
                eventDto.getStatus() == ShipmentStatus.CANCELLED) return;
        if (eventDto.getExpectedAt() == null) return;

        if (LocalDateTime.now().isAfter(eventDto.getExpectedAt())) {
            long hoursOverdue = ChronoUnit.HOURS.between(eventDto.getExpectedAt(), LocalDateTime.now());
            String message = String.format(
                    "Shipment %s missed its delivery deadline! Was expected at: %s (overdue by %d hours). Current status: %s | Supplier: %s | Route: %s → %s",
                    eventDto.getTrackingNumber(), eventDto.getExpectedAt(), hoursOverdue,
                    eventDto.getStatus(), eventDto.getSupplierName(),
                    eventDto.getOrigin(), eventDto.getDestination());
            createAlert(rule, eventDto, message);
        }
    }

    private void createAlert(SlaRule rule, ShipmentEventDto eventDto, String message) {

        // Step 1: Redis dedup check
        boolean isDuplicate = redisService.isDuplicateAlert(
                eventDto.getShipmentId(), rule.getRuleType().name());
        if (isDuplicate) {
            log.debug("⏭️ Skipping duplicate alert | Shipment: {} | Rule: {} | (Redis dedup)",
                    eventDto.getTrackingNumber(), rule.getRuleType());
            return;
        }

        // Step 2: Load supplier
        Supplier supplier = supplierRepository.findById(eventDto.getSupplierId()).orElse(null);
        if (supplier == null) {
            log.warn("⚠️ Supplier not found for ID: {} — skipping alert", eventDto.getSupplierId());
            return;
        }

        // Step 3: Save alert to DB
        AlertEvent alert = new AlertEvent();
        alert.setSupplier(supplier);
        alert.setRuleType(rule.getRuleType());
        alert.setSeverity(rule.getSeverity());
        alert.setMessage(message);
        alert.setResolved(false);

        AlertEvent saved = alertEventRepository.save(alert);

        alertBroadcastService.broadcastAlert(saved);

        log.warn("🚨 ALERT CREATED | ID: {} | Severity: {} | Rule: {} | Shipment: {}",
                saved.getId(), saved.getSeverity(), saved.getRuleType(), eventDto.getTrackingNumber());

        // Step 4: Trigger AI analysis for CRITICAL alerts only — async, non-blocking
        if (saved.getSeverity() == SlaRule.AlertSeverity.CRITICAL) {
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // small delay to avoid rate limiting
                } catch (InterruptedException ignored) {}

                String analysis = aiRootCauseService.analyzeAlert(saved);

                // ── Only broadcast if analysis was successfully generated ──
                if (analysis != null && !analysis.isBlank()
                        && !analysis.startsWith("AI analysis unavailable")) {
                    log.info("🤖 AI Root Cause Analysis | Alert ID: {} |\n{}", saved.getId(), analysis);
                    alertBroadcastService.broadcastAiAnalysis(saved.getId(), analysis);
                } else {
                    log.warn("⚠️ AI analysis not broadcast for Alert ID: {} — empty or failed", saved.getId());
                }
            }).start();
        }

        // Step 5: Redis dedup marker + shipment status cache
        redisService.markAlertFired(eventDto.getShipmentId(), rule.getRuleType().name());
        redisService.cacheShipmentStatus(eventDto.getTrackingNumber(), eventDto.getStatus().name());
    }
}