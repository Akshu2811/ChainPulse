package com.chainpulse.chainpulse.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * RedisService — handles all Redis read/write operations for ChainPulse.
 *
 * Think of Redis as a super-fast whiteboard that auto-erases after a set time.
 * This service writes to and reads from that whiteboard.
 *
 * Two main use cases:
 *
 * 1. ALERT DEDUPLICATION
 *    Problem: The simulator fires events every 5 seconds. Without deduplication,
 *    the same shipment could generate hundreds of alerts in minutes.
 *    Solution: Before creating an alert, check Redis. If we already fired
 *    this alert in the last 30 minutes → skip it. Otherwise → create it
 *    and write a marker to Redis with 30-minute TTL.
 *
 *    Key format:  "alert:dedup:{shipmentId}:{ruleType}"
 *    Value:       "1" (just a marker — we only care if key exists)
 *    TTL:         30 minutes (auto-erased after 30 min)
 *
 * 2. SLA STATE CACHE
 *    Problem: Every Kafka event triggers DB queries to load SLA rules.
 *    Solution: Cache the last known shipment status in Redis.
 *    Key format:  "sla:state:{trackingNumber}"
 *    Value:       "DELAYED" / "STUCK" / "IN_TRANSIT" etc.
 *    TTL:         24 hours
 *
 * 3. SUPPLIER HEALTH CACHE
 *    Problem: Supplier health score requires counting alerts in DB — expensive.
 *    Solution: Cache the result for 5 minutes.
 *    Key format:  "supplier:health:{supplierId}"
 *    Value:       "AT_RISK" / "HEALTHY" / "CRITICAL"
 *    TTL:         5 minutes
 */
@Slf4j
@Service
public class RedisService {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    // ── Key prefixes — keeps Redis keys organized and readable ────────────────
    private static final String ALERT_DEDUP_PREFIX    = "alert:dedup:";
    private static final String SLA_STATE_PREFIX      = "sla:state:";
    private static final String SUPPLIER_HEALTH_PREFIX = "supplier:health:";

    // ── TTL constants ─────────────────────────────────────────────────────────
    private static final Duration ALERT_DEDUP_TTL     = Duration.ofMinutes(30);
    private static final Duration SLA_STATE_TTL       = Duration.ofHours(24);
    private static final Duration SUPPLIER_HEALTH_TTL = Duration.ofMinutes(5);

    // ══════════════════════════════════════════════════════════════════════════
    // ALERT DEDUPLICATION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * isDuplicateAlert — checks if we already fired this alert recently.
     *
     * Returns true  → alert already exists in Redis → SKIP creating it
     * Returns false → alert not in Redis → OK to create it
     *
     * @param shipmentId — the shipment that triggered the alert
     * @param ruleType   — which rule was breached (e.g. "MAX_TRANSIT_HOURS")
     */
    public boolean isDuplicateAlert(Long shipmentId, String ruleType) {
        String key = ALERT_DEDUP_PREFIX + shipmentId + ":" + ruleType;
        Boolean exists = redisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * markAlertFired — writes a deduplication marker to Redis.
     * Called AFTER successfully creating an AlertEvent in PostgreSQL.
     * The key auto-expires after 30 minutes.
     *
     * @param shipmentId — the shipment that triggered the alert
     * @param ruleType   — which rule was breached
     */
    public void markAlertFired(Long shipmentId, String ruleType) {
        String key = ALERT_DEDUP_PREFIX + shipmentId + ":" + ruleType;
        redisTemplate.opsForValue().set(key, "1", ALERT_DEDUP_TTL);
        log.debug("🔴 Alert dedup marker set | Key: {} | TTL: 30min", key);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SLA STATE CACHE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * getShipmentStatus — retrieves cached shipment status from Redis.
     * Returns null if not cached (cache miss).
     *
     * @param trackingNumber — e.g. "BD-2291"
     */
    public String getShipmentStatus(String trackingNumber) {
        String key = SLA_STATE_PREFIX + trackingNumber;
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * cacheShipmentStatus — stores the latest shipment status in Redis.
     * Overwrites any existing value for this tracking number.
     *
     * @param trackingNumber — e.g. "BD-2291"
     * @param status         — e.g. "DELAYED"
     */
    public void cacheShipmentStatus(String trackingNumber, String status) {
        String key = SLA_STATE_PREFIX + trackingNumber;
        redisTemplate.opsForValue().set(key, status, SLA_STATE_TTL);
        log.debug("📦 Shipment status cached | Key: {} | Status: {}", key, status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SUPPLIER HEALTH CACHE
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * getSupplierHealth — retrieves cached supplier health from Redis.
     * Returns null if not cached (cache miss — needs DB query).
     *
     * @param supplierId — e.g. 1 for BlueDart
     */
    public String getSupplierHealth(Long supplierId) {
        String key = SUPPLIER_HEALTH_PREFIX + supplierId;
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * cacheSupplierHealth — stores supplier health score in Redis.
     * Auto-expires after 5 minutes so it stays fresh.
     *
     * @param supplierId — e.g. 1 for BlueDart
     * @param status     — "HEALTHY", "AT_RISK", or "CRITICAL"
     */
    public void cacheSupplierHealth(Long supplierId, String status) {
        String key = SUPPLIER_HEALTH_PREFIX + supplierId;
        redisTemplate.opsForValue().set(key, status, SUPPLIER_HEALTH_TTL);
        log.debug("🏭 Supplier health cached | Key: {} | Status: {}", key, status);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * deleteKey — manually removes a key from Redis.
     * Useful for testing or forcing a cache refresh.
     *
     * @param key — the full Redis key to delete
     */
    public void deleteKey(String key) {
        redisTemplate.delete(key);
        log.debug("🗑️ Redis key deleted | Key: {}", key);
    }

    /**
     * keyExists — checks if any key exists in Redis.
     *
     * @param key — the full Redis key to check
     */
    public boolean keyExists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // ══════════════════════════════════════════════════════════════════════════
// SLA RULES CACHE
// ══════════════════════════════════════════════════════════════════════════

    private static final String SLA_RULES_CACHE_KEY = "sla:rules:active";
    private static final Duration SLA_RULES_TTL = Duration.ofMinutes(5);

    /**
     * cacheSlaRules — stores serialized SLA rules in Redis.
     * Called after loading rules from DB.
     * TTL 5 minutes — rules don't change often.
     *
     * @param rulesJson — JSON string of all active rules
     */
    public void cacheSlaRules(String rulesJson) {
        redisTemplate.opsForValue().set(SLA_RULES_CACHE_KEY, rulesJson, SLA_RULES_TTL);
        log.debug("📋 SLA rules cached | TTL: 5min");
    }

    /**
     * getCachedSlaRules — retrieves cached SLA rules from Redis.
     * Returns null on cache miss — caller must load from DB.
     */
    public String getCachedSlaRules() {
        return redisTemplate.opsForValue().get(SLA_RULES_CACHE_KEY);
    }

    /**
     * evictSlaRulesCache — clears the SLA rules cache.
     * Called when a rule is created, updated, or toggled
     * so the engine picks up the latest rules immediately.
     */
    public void evictSlaRulesCache() {
        redisTemplate.delete(SLA_RULES_CACHE_KEY);
        log.info("🗑️ SLA rules cache evicted — will reload from DB on next event");
    }
}