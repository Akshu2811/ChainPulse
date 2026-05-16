package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.repository.AlertEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/**
 * AlertCleanupService — auto-deletes old resolved alerts to keep DB lean.
 *
 * Strategy:
 * - Resolved alerts older than 30 days → delete
 * - Unresolved alerts older than 90 days → delete (stale/forgotten)
 * - Runs once every day at 2 AM
 *
 * This keeps the DB from growing unboundedly while preserving
 * recent history for trend charts and reporting.
 */
@Slf4j
@Service
public class AlertCleanupService {

    @Autowired
    private AlertEventRepository alertEventRepository;

    /**
     * Runs daily at 2 AM.
     * Deletes resolved alerts older than 30 days.
     * Deletes unresolved alerts older than 90 days.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupOldAlerts() {
        LocalDateTime thirtyDaysAgo  = LocalDateTime.now().minusDays(30);
        LocalDateTime ninetyDaysAgo  = LocalDateTime.now().minusDays(90);

        // Delete resolved alerts older than 30 days
        int resolvedDeleted = alertEventRepository
                .deleteByResolvedTrueAndCreatedAtBefore(thirtyDaysAgo);

        // Delete stale unresolved alerts older than 90 days
        int staleDeleted = alertEventRepository
                .deleteByResolvedFalseAndCreatedAtBefore(ninetyDaysAgo);

        log.info("🧹 Alert cleanup complete | Resolved deleted: {} | Stale deleted: {}",
                resolvedDeleted, staleDeleted);
    }
}