package com.chainpulse.chainpulse.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI chainPulseOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("ChainPulse API")
                        .description("""
                    **ChainPulse** — AI-Powered Supply Chain Disruption Alert Engine
                    
                    Real-time supply chain monitoring system built with:
                    - **Spring Boot 4** + **Kafka** for event streaming
                    - **Redis** for caching and deduplication
                    - **WebSocket** for live dashboard updates
                    - **Spring AI + Gemini 2.5 Flash** for AI root cause analysis
                    
                    When a shipment breaches an SLA rule, ChainPulse:
                    1. Fires an alert via Kafka
                    2. Persists it to PostgreSQL
                    3. Broadcasts it to the dashboard via WebSocket
                    4. Generates AI root cause analysis using Gemini
                    """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Akshitha")
                                .url("https://github.com/Akshu2811/ChainPulse"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .tags(List.of(
                        new Tag().name("alert-controller")
                                .description("Alert management — view, filter, resolve alerts"),
                        new Tag().name("shipment-controller")
                                .description("Shipment tracking — create and monitor shipments"),
                        new Tag().name("supplier-controller")
                                .description("Supplier health — SLA compliance and health scores"),
                        new Tag().name("sla-rule-controller")
                                .description("SLA rules — create and manage breach rules"),
                        new Tag().name("stats-controller")
                                .description("Dashboard stats — real-time metrics")
                ));
    }
}