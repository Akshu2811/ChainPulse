package com.chainpulse.chainpulse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig — sets up real-time WebSocket communication for ChainPulse.
 *
 * Without WebSocket, the browser has to keep asking the server:
 * "Any new alerts? Any new alerts? Any new alerts?" (polling — inefficient)
 *
 * With WebSocket, the server PUSHES alerts to the browser the moment they happen.
 * Like a phone call vs sending a letter — instant, two-way communication.
 *
 * We use STOMP (Simple Text Oriented Messaging Protocol) on top of WebSocket.
 * STOMP adds structure — topics, subscriptions, message routing.
 *
 * How it works:
 * 1. Browser connects to ws://localhost:8080/ws
 * 2. Browser subscribes to /topic/alerts and /topic/stats
 * 3. When ChainPulse creates a new alert → server pushes to /topic/alerts
 * 4. Browser receives it instantly and updates the dashboard
 */
@Configuration
@EnableWebSocketMessageBroker   // Activates WebSocket with STOMP message broker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * configureMessageBroker — sets up the message routing rules.
     *
     * /topic  → prefix for server-to-browser broadcasts
     *           e.g. /topic/alerts, /topic/stats
     *           Browser subscribes to these to receive messages.
     *
     * /app    → prefix for browser-to-server messages
     *           e.g. /app/ping (heartbeat)
     *           Not heavily used in ChainPulse since data flows server→browser.
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable a simple in-memory message broker for /topic destinations
        registry.enableSimpleBroker("/topic");

        // Messages sent from browser to server must start with /app
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * registerStompEndpoints — registers the WebSocket connection endpoint.
     *
     * Browser connects to: ws://localhost:8080/ws
     * SockJS fallback: if WebSocket not supported → falls back to HTTP polling
     *
     * setAllowedOriginPatterns("*") — allows connections from any origin
     * (needed for local development where frontend may be on different port)
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // Allow all origins (dev mode)
                .withSockJS();                   // Enable SockJS fallback
    }
}