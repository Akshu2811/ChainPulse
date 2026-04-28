package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.entity.AlertEvent;
import com.chainpulse.chainpulse.entity.Shipment.ShipmentStatus;
import com.chainpulse.chainpulse.entity.SlaRule;
import com.chainpulse.chainpulse.entity.SlaRule.AlertSeverity;
import com.chainpulse.chainpulse.entity.SlaRule.RuleType;
import com.chainpulse.chainpulse.entity.Supplier;
import com.chainpulse.chainpulse.kafka.dto.ShipmentEventDto;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import com.chainpulse.chainpulse.repository.SlaRuleRepository;
import com.chainpulse.chainpulse.repository.SupplierRepository;
import com.chainpulse.chainpulse.repository.ShipmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SlaRuleEngineTest — unit tests for the SLA breach detection logic.
 *
 * We use Mockito to mock all dependencies (DB, Redis, WebSocket)
 * so we only test the pure business logic in SlaRuleEngine.
 *
 * @ExtendWith(MockitoExtension.class) — activates Mockito in JUnit 5
 * @Mock — creates a fake/mock version of each dependency
 * @InjectMocks — creates a real SlaRuleEngine with mocks injected
 */
@ExtendWith(MockitoExtension.class)
class SlaRuleEngineTest {

    @Mock private SlaRuleRepository      slaRuleRepository;
    @Mock private AlertEventRepository   alertEventRepository;
    @Mock private SupplierRepository     supplierRepository;
    @Mock private ShipmentRepository     shipmentRepository;
    @Mock private RedisService           redisService;
    @Mock private AlertBroadcastService  alertBroadcastService;

    @InjectMocks
    private SlaRuleEngine slaRuleEngine;

    // ── Reusable test data ────────────────────────────────────────────────
    private Supplier      testSupplier;
    private SlaRule       maxTransitRule;
    private SlaRule       checkpointRule;
    private SlaRule       deadlineRule;

    @BeforeEach
    void setUp() {
        // Create a test supplier — BlueDart
        testSupplier = new Supplier();
        testSupplier.setId(1L);
        testSupplier.setName("BlueDart");
        testSupplier.setLocation("Mumbai");
        testSupplier.setSlaThresholdHours(48);

        // Rule 1: Fire WARNING if shipment in transit > 48 hours
        maxTransitRule = new SlaRule();
        maxTransitRule.setId(1L);
        maxTransitRule.setRuleType(RuleType.MAX_TRANSIT_HOURS);
        maxTransitRule.setThresholdValue(48);
        maxTransitRule.setSeverity(AlertSeverity.WARNING);
        maxTransitRule.setActive(true);

        // Rule 2: Fire CRITICAL if shipment STUCK > 4 hours
        checkpointRule = new SlaRule();
        checkpointRule.setId(2L);
        checkpointRule.setRuleType(RuleType.CHECKPOINT_TIMEOUT);
        checkpointRule.setThresholdValue(4);
        checkpointRule.setSeverity(AlertSeverity.CRITICAL);
        checkpointRule.setActive(true);

        // Rule 3: Fire CRITICAL if shipment missed delivery deadline
        deadlineRule = new SlaRule();
        deadlineRule.setId(3L);
        deadlineRule.setRuleType(RuleType.DELIVERY_DEADLINE_MISS);
        deadlineRule.setThresholdValue(0);
        deadlineRule.setSeverity(AlertSeverity.CRITICAL);
        deadlineRule.setActive(true);
    }

    // ── Helper: build a ShipmentEventDto ─────────────────────────────────
    private ShipmentEventDto buildEvent(ShipmentStatus status,
                                        LocalDateTime dispatchedAt,
                                        LocalDateTime expectedAt) {
        return new ShipmentEventDto(
                100L,           // shipmentId
                1L,             // supplierId
                "BlueDart",     // supplierName
                "BD-1234",      // trackingNumber
                status,
                "Mumbai",
                "Hyderabad",
                dispatchedAt,
                expectedAt,
                LocalDateTime.now()  // eventTimestamp
        );
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST 1 — MAX_TRANSIT_HOURS breach detected
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should create alert when shipment in transit exceeds threshold")
    void testMaxTransitHoursBreachDetected() {
        // GIVEN: shipment dispatched 52 hours ago (threshold is 48h)
        ShipmentEventDto event = buildEvent(
                ShipmentStatus.IN_TRANSIT,
                LocalDateTime.now().minusHours(52),  // dispatched 52h ago
                LocalDateTime.now().plusHours(2)     // expected in future
        );

        // Mock: load global rules + no supplier-specific rules
        when(slaRuleRepository.findBySupplierIsNullAndActiveTrue())
                .thenReturn(List.of(maxTransitRule));
        when(slaRuleRepository.findBySupplierIdAndActiveTrue(1L))
                .thenReturn(List.of());

        // Mock: not a duplicate alert
        when(redisService.isDuplicateAlert(100L, "MAX_TRANSIT_HOURS"))
                .thenReturn(false);

        // Mock: supplier found
        when(supplierRepository.findById(1L))
                .thenReturn(Optional.of(testSupplier));

        // Mock: alert saved successfully
        AlertEvent savedAlert = new AlertEvent();
        savedAlert.setId(1L);
        savedAlert.setSupplier(testSupplier);
        savedAlert.setRuleType(RuleType.MAX_TRANSIT_HOURS);
        savedAlert.setSeverity(AlertSeverity.WARNING);
        when(alertEventRepository.save(any(AlertEvent.class)))
                .thenReturn(savedAlert);

        // WHEN: evaluate the event
        slaRuleEngine.evaluate(event);

        // THEN: alert should be created and saved
        verify(alertEventRepository, times(1)).save(any(AlertEvent.class));
        verify(redisService, times(1))
                .markAlertFired(100L, "MAX_TRANSIT_HOURS");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST 2 — MAX_TRANSIT_HOURS NOT breached
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should NOT create alert when shipment in transit is within threshold")
    void testMaxTransitHoursNotBreached() {
        // GIVEN: shipment dispatched only 40 hours ago (threshold is 48h)
        ShipmentEventDto event = buildEvent(
                ShipmentStatus.IN_TRANSIT,
                LocalDateTime.now().minusHours(40),  // dispatched 40h ago — within limit
                LocalDateTime.now().plusHours(8)     // expected in future
        );

        when(slaRuleRepository.findBySupplierIsNullAndActiveTrue())
                .thenReturn(List.of(maxTransitRule));
        when(slaRuleRepository.findBySupplierIdAndActiveTrue(1L))
                .thenReturn(List.of());

        // WHEN
        slaRuleEngine.evaluate(event);

        // THEN: NO alert should be created
        verify(alertEventRepository, never()).save(any(AlertEvent.class));
        verify(redisService, never()).markAlertFired(anyLong(), anyString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST 3 — CHECKPOINT_TIMEOUT breach detected
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should create CRITICAL alert when shipment stuck beyond threshold")
    void testCheckpointTimeoutBreachDetected() {
        // GIVEN: shipment STUCK, dispatched 6 hours ago (threshold is 4h)
        ShipmentEventDto event = buildEvent(
                ShipmentStatus.STUCK,
                LocalDateTime.now().minusHours(6),   // stuck for 6h — exceeds 4h limit
                LocalDateTime.now().plusHours(10)
        );

        when(slaRuleRepository.findBySupplierIsNullAndActiveTrue())
                .thenReturn(List.of(checkpointRule));
        when(slaRuleRepository.findBySupplierIdAndActiveTrue(1L))
                .thenReturn(List.of());

        when(redisService.isDuplicateAlert(100L, "CHECKPOINT_TIMEOUT"))
                .thenReturn(false);
        when(supplierRepository.findById(1L))
                .thenReturn(Optional.of(testSupplier));

        AlertEvent savedAlert = new AlertEvent();
        savedAlert.setId(2L);
        savedAlert.setSupplier(testSupplier);
        savedAlert.setRuleType(RuleType.CHECKPOINT_TIMEOUT);
        savedAlert.setSeverity(AlertSeverity.CRITICAL);
        when(alertEventRepository.save(any(AlertEvent.class)))
                .thenReturn(savedAlert);

        // WHEN
        slaRuleEngine.evaluate(event);

        // THEN: CRITICAL alert should be created
        verify(alertEventRepository, times(1)).save(any(AlertEvent.class));
        verify(redisService, times(1))
                .markAlertFired(100L, "CHECKPOINT_TIMEOUT");
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST 4 — DELIVERY_DEADLINE_MISS detected
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should create CRITICAL alert when shipment misses delivery deadline")
    void testDeliveryDeadlineMissDetected() {
        // GIVEN: shipment expected 3 hours ago but still IN_TRANSIT
        ShipmentEventDto event = buildEvent(
                ShipmentStatus.IN_TRANSIT,
                LocalDateTime.now().minusHours(50),
                LocalDateTime.now().minusHours(3)   // deadline was 3 hours ago!
        );

        when(slaRuleRepository.findBySupplierIsNullAndActiveTrue())
                .thenReturn(List.of(deadlineRule));
        when(slaRuleRepository.findBySupplierIdAndActiveTrue(1L))
                .thenReturn(List.of());

        when(redisService.isDuplicateAlert(100L, "DELIVERY_DEADLINE_MISS"))
                .thenReturn(false);
        when(supplierRepository.findById(1L))
                .thenReturn(Optional.of(testSupplier));

        AlertEvent savedAlert = new AlertEvent();
        savedAlert.setId(3L);
        savedAlert.setSupplier(testSupplier);
        savedAlert.setRuleType(RuleType.DELIVERY_DEADLINE_MISS);
        savedAlert.setSeverity(AlertSeverity.CRITICAL);
        when(alertEventRepository.save(any(AlertEvent.class)))
                .thenReturn(savedAlert);

        // WHEN
        slaRuleEngine.evaluate(event);

        // THEN: alert should be created
        verify(alertEventRepository, times(1)).save(any(AlertEvent.class));
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST 5 — Redis deduplication prevents double alert
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should NOT create duplicate alert when Redis dedup key exists")
    void testRedisDedupPreventsDoubleAlert() {
        // GIVEN: shipment in transit 52h — would normally trigger alert
        ShipmentEventDto event = buildEvent(
                ShipmentStatus.IN_TRANSIT,
                LocalDateTime.now().minusHours(52),
                LocalDateTime.now().plusHours(2)
        );

        when(slaRuleRepository.findBySupplierIsNullAndActiveTrue())
                .thenReturn(List.of(maxTransitRule));
        when(slaRuleRepository.findBySupplierIdAndActiveTrue(1L))
                .thenReturn(List.of());

        // Mock: Redis says alert already fired — DUPLICATE!
        when(redisService.isDuplicateAlert(100L, "MAX_TRANSIT_HOURS"))
                .thenReturn(true);

        // WHEN
        slaRuleEngine.evaluate(event);

        // THEN: NO alert created, NO Redis marker set
        verify(alertEventRepository, never()).save(any(AlertEvent.class));
        verify(redisService, never()).markAlertFired(anyLong(), anyString());
    }

    // ══════════════════════════════════════════════════════════════════════
    // TEST 6 — No rules = no alerts
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Should not evaluate anything when no active rules exist")
    void testNoRulesNoAlerts() {
        // GIVEN: no active rules in DB
        ShipmentEventDto event = buildEvent(
                ShipmentStatus.DELAYED,
                LocalDateTime.now().minusHours(60),
                LocalDateTime.now().minusHours(5)
        );

        when(slaRuleRepository.findBySupplierIsNullAndActiveTrue())
                .thenReturn(List.of());
        when(slaRuleRepository.findBySupplierIdAndActiveTrue(1L))
                .thenReturn(List.of());

        // WHEN
        slaRuleEngine.evaluate(event);

        // THEN: nothing happens — no DB queries, no alerts
        verify(alertEventRepository, never()).save(any(AlertEvent.class));
        verify(redisService, never()).isDuplicateAlert(anyLong(), anyString());
    }
}