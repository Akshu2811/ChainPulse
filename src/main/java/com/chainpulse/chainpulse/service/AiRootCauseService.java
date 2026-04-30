package com.chainpulse.chainpulse.service;

import com.chainpulse.chainpulse.entity.AlertEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AiRootCauseService — uses Spring AI + Gemini to analyze CRITICAL alerts.
 *
 * When a CRITICAL alert fires in ChainPulse, this service:
 * 1. Builds a detailed prompt with alert context
 * 2. Sends it to Gemini via Spring AI ChatClient
 * 3. Returns a root cause hypothesis + recommended actions
 *
 * This transforms ChainPulse from a "dumb alert engine" into an
 * "AI-powered supply chain intelligence system".
 *
 * Spring AI abstracts the Gemini API — if we ever want to switch
 * to OpenAI or Claude, we just change the config, not the code.
 */
@Slf4j
@Service
public class AiRootCauseService {

    /**
     * ChatClient — Spring AI's main interface for LLM interactions.
     * Auto-configured by Spring AI when API key is present.
     * Wraps Gemini API calls with retry, error handling, etc.
     */
    private final ChatClient chatClient;

    public AiRootCauseService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * analyzeAlert — generates AI root cause analysis for a CRITICAL alert.
     *
     * Takes alert details → builds a supply chain expert prompt →
     * sends to Gemini → returns structured analysis.
     *
     * @param alert — the CRITICAL alert to analyze
     * @return AI-generated root cause hypothesis and recommendations
     */
    public String analyzeAlert(AlertEvent alert) {
        try {
            log.info("🤖 Requesting AI root cause analysis | Alert ID: {} | Rule: {}",
                    alert.getId(), alert.getRuleType());

            // Build a rich, context-aware prompt
            String prompt = buildPrompt(alert);

            // Call Gemini via Spring AI — synchronous call
            String analysis = chatClient
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();

            log.info("✅ AI analysis complete | Alert ID: {} | Length: {} chars",
                    alert.getId(), analysis != null ? analysis.length() : 0);

            return analysis;

        }  catch (Exception e) {
            log.error("❌ AI analysis failed | Alert ID: {} | Error: {} | Cause: {}",
                    alert.getId(), e.getMessage(),
                    e.getCause() != null ? e.getCause().getMessage() : "none");
            return "AI analysis unavailable — " + e.getMessage();
        }
    }

    /**
     * buildPrompt — constructs a detailed supply chain expert prompt.
     *
     * The prompt gives Gemini full context about:
     * - What type of SLA breach occurred
     * - Which supplier and route is affected
     * - The full alert message
     * - What kind of analysis we need
     *
     * Good prompts = good AI output. This is the core of prompt engineering.
     */
    private String buildPrompt(AlertEvent alert) {
        String supplierName = alert.getSupplier() != null
                ? alert.getSupplier().getName() : "Unknown Supplier";
        String supplierLocation = alert.getSupplier() != null
                ? alert.getSupplier().getLocation() : "Unknown Location";

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
                [2-3 most likely causes for this specific disruption, considering Indian logistics context — 
                 traffic on national highways, port congestion, customs delays, weather, labour issues, etc.]
                
                IMMEDIATE ACTIONS:
                [2-3 specific actions the operations team should take RIGHT NOW]
                
                PREVENTION:
                [1-2 ways to prevent this type of disruption in future]
                
                Keep the response concise and actionable. Maximum 200 words.
                """,
                alert.getSeverity(),
                alert.getId(),
                alert.getRuleType(),
                alert.getSeverity(),
                supplierName,
                supplierLocation,
                alert.getMessage(),
                alert.getCreatedAt()
        );
    }
}