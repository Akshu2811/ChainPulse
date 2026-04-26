package com.chainpulse.chainpulse.repository;

import com.chainpulse.chainpulse.entity.SlaRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * SlaRuleRepository — handles all database operations for SlaRule.
 * The SLA rule engine uses this to load rules during breach evaluation.
 */
@Repository
public interface SlaRuleRepository extends JpaRepository<SlaRule, Long> {

    // Get all active rules — used by SlaRuleEngine every time
    // a Kafka event is consumed
    List<SlaRule> findByActiveTrue();

    // Get all active rules for a specific supplier
    // e.g. load BlueDart-specific rules
    List<SlaRule> findBySupplierIdAndActiveTrue(Long supplierId);

    // Get all active global rules (supplier is null = applies to everyone)
    List<SlaRule> findBySupplierIsNullAndActiveTrue();
}