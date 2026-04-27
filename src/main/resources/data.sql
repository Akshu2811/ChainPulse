-- ============================================================
-- ChainPulse — Seed Data
-- Runs automatically on startup (Spring Boot loads data.sql)
-- Uses INSERT ... WHERE NOT EXISTS to avoid duplicate inserts
-- on every restart.
-- ============================================================

-- ── Suppliers ────────────────────────────────────────────────
-- 5 real Indian logistics companies with SLA thresholds.
-- sla_threshold_hours = max allowed transit hours before alert fires.

INSERT INTO suppliers (name, location, contact_email, sla_threshold_hours, created_at)
SELECT 'BlueDart', 'Mumbai', 'ops@bluedart.com', 48, NOW()
    WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE name = 'BlueDart');

INSERT INTO suppliers (name, location, contact_email, sla_threshold_hours, created_at)
SELECT 'Delhivery', 'Delhi', 'ops@delhivery.com', 36, NOW()
    WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE name = 'Delhivery');

INSERT INTO suppliers (name, location, contact_email, sla_threshold_hours, created_at)
SELECT 'DTDC', 'Chennai', 'ops@dtdc.com', 72, NOW()
    WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE name = 'DTDC');

INSERT INTO suppliers (name, location, contact_email, sla_threshold_hours, created_at)
SELECT 'Ecom Express', 'Kolkata', 'ops@ecomexpress.in', 48, NOW()
    WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE name = 'Ecom Express');

INSERT INTO suppliers (name, location, contact_email, sla_threshold_hours, created_at)
SELECT 'Shadowfax', 'Bangalore', 'ops@shadowfax.in', 24, NOW()
    WHERE NOT EXISTS (SELECT 1 FROM suppliers WHERE name = 'Shadowfax');

-- ── SLA Rules ─────────────────────────────────────────────────
-- Global rules (supplier_id = NULL) — apply to ALL suppliers.
-- Rule 1: Fire WARNING if any shipment is IN_TRANSIT for more than 48 hours.
-- Rule 2: Fire CRITICAL if any shipment is STUCK for more than 4 hours.
-- Rule 3: Fire CRITICAL if any shipment misses its expected delivery time.

INSERT INTO sla_rules (supplier_id, rule_type, threshold_value, severity, active, created_at)
SELECT NULL, 'MAX_TRANSIT_HOURS', 48, 'WARNING', true, NOW()
    WHERE NOT EXISTS (
    SELECT 1 FROM sla_rules WHERE rule_type = 'MAX_TRANSIT_HOURS' AND supplier_id IS NULL
);

INSERT INTO sla_rules (supplier_id, rule_type, threshold_value, severity, active, created_at)
SELECT NULL, 'CHECKPOINT_TIMEOUT', 4, 'CRITICAL', true, NOW()
    WHERE NOT EXISTS (
    SELECT 1 FROM sla_rules WHERE rule_type = 'CHECKPOINT_TIMEOUT' AND supplier_id IS NULL
);

INSERT INTO sla_rules (supplier_id, rule_type, threshold_value, severity, active, created_at)
SELECT NULL, 'DELIVERY_DEADLINE_MISS', 0, 'CRITICAL', true, NOW()
    WHERE NOT EXISTS (
    SELECT 1 FROM sla_rules WHERE rule_type = 'DELIVERY_DEADLINE_MISS' AND supplier_id IS NULL
);