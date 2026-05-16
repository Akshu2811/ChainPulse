package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.entity.AlertEvent;
import com.chainpulse.chainpulse.entity.SlaRule;
import com.chainpulse.chainpulse.repository.AlertEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AiRootCauseService {

    private final ChatClient chatClient;

    @Autowired
    private AlertEventRepository alertEventRepository;

    @Autowired
    private AlertBroadcastService alertBroadcastService;

    public AiRootCauseService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public String analyzeAlert(AlertEvent alert) {
        try {
            // ── Skip if already analyzed ──────────────────────────────
            if (alert.getAiAnalysis() != null && !alert.getAiAnalysis().isBlank()) {
                log.info("⚡ AI analysis already exists | Alert ID: {} — skipping Gemini call", alert.getId());
                return alert.getAiAnalysis();
            }

            log.info("🤖 Requesting AI root cause analysis | Alert ID: {} | Rule: {}",
                    alert.getId(), alert.getRuleType());

            String analysis = chatClient
                    .prompt()
                    .user(buildPrompt(alert))
                    .call()
                    .content();

            log.info("✅ AI analysis complete | Alert ID: {} | Length: {} chars",
                    alert.getId(), analysis != null ? analysis.length() : 0);

            // ── Persist analysis to DB ────────────────────────────────
            if (analysis != null && !analysis.isBlank()) {
                alert.setAiAnalysis(analysis);
                alertEventRepository.save(alert);
                log.debug("💾 AI analysis saved to DB | Alert ID: {}", alert.getId());
            }

            return analysis;

        } catch (Exception e) {
            log.error("❌ AI analysis failed | Alert ID: {} | Error: {} | Cause: {}",
                    alert.getId(), e.getMessage(),
                    e.getCause() != null ? e.getCause().getMessage() : "none");
            return null; // Return null so UI doesn't show error text
        }
    }

    private String buildPrompt(AlertEvent alert) {
        String supplierName     = alert.getSupplier() != null ? alert.getSupplier().getName()     : "Unknown Supplier";
        String supplierLocation = alert.getSupplier() != null ? alert.getSupplier().getLocation() : "Unknown Location";

        return String.format("""
                You are an expert supply chain disruption analyst for Indian logistics operations.
                
                A %s alert has been triggered in our supply chain monitoring system.
                
                ALERT DETAILS:
                - Alert ID: %d
                - Rule Type: %s
                - Severity: %s
                - Supplier: %s (based in %s)
                - Alert Message: %s
                - Detected At: %s
                
                Please provide a concise analysis in the following format:
                
                ROOT CAUSE HYPOTHESIS:
                [2-3 most likely causes considering Indian logistics context —
                 traffic on national highways, E-way bill issues, RTO checkpoints, weather, etc.]
                
                IMMEDIATE ACTIONS:
                [2-3 specific actions the operations team should take RIGHT NOW]
                
                PREVENTION:
                [1-2 ways to prevent this type of disruption in future]
                
                Keep the response concise and actionable. Maximum 200 words.
                """,
                alert.getSeverity(), alert.getId(), alert.getRuleType(), alert.getSeverity(),
                supplierName, supplierLocation, alert.getMessage(), alert.getCreatedAt()
        );
    }



    /**
     * On startup — backfill any CRITICAL alerts that have no AI analysis.
     * Handles the edge case where app was stopped mid-analysis.
     * Runs async so it doesn't slow down startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingAnalysis() {
        new Thread(() -> {
            try {
                Thread.sleep(5000); // wait for app to fully start
                List<AlertEvent> missing = alertEventRepository
                        .findBySeverityAndAiAnalysisIsNullAndResolvedFalse(
                                SlaRule.AlertSeverity.CRITICAL
                        );

                if (missing.isEmpty()) {
                    log.info("✅ No missing AI analyses to backfill");
                    return;
                }

                log.info("🔄 Backfilling AI analysis for {} CRITICAL alerts...", missing.size());

                for (AlertEvent alert : missing) {
                    try {
                        String analysis = analyzeAlert(alert);
                        if (analysis != null && !analysis.isBlank()) {
                            alertBroadcastService.broadcastAiAnalysis(alert.getId(), analysis);
                        }
                        Thread.sleep(2000); // 2s gap between calls — avoid rate limiting
                    } catch (Exception e) {
                        log.warn("⚠️ Backfill failed for Alert ID: {} | {}", alert.getId(), e.getMessage());
                    }
                }

                log.info("✅ AI analysis backfill complete");
            } catch (Exception e) {
                log.error("❌ Backfill job failed: {}", e.getMessage());
            }
        }).start();
    }
}