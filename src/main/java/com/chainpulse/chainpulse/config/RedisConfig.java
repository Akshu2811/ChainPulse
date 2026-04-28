package com.chainpulse.chainpulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * RedisConfig — sets up the Redis connection for ChainPulse.
 *
 * Redis is like a super-fast sticky notepad in memory.
 * We use it for two things:
 * 1. SLA state cache — remembering the last known status of each shipment
 *    so we don't hit PostgreSQL every time a Kafka event arrives.
 * 2. Alert deduplication — remembering which alerts we already fired
 *    in the last 30 minutes so we don't spam the same alert repeatedly.
 *
 * RedisTemplate<String, String> — both key and value are plain Strings.
 * Key example:   "sla:state:BD-2291"
 * Value example: "DELAYED"
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate — the main tool for reading/writing to Redis.
     * We configure both key and value serializers as String
     * so data is stored as human-readable text in Redis.
     *
     * Without this config, Spring uses Java serialization by default
     * which stores unreadable binary data — hard to debug.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(
            RedisConnectionFactory connectionFactory) {

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Use String serializer for keys — e.g. "sla:state:BD-2291"
        template.setKeySerializer(new StringRedisSerializer());

        // Use String serializer for values — e.g. "DELAYED"
        template.setValueSerializer(new StringRedisSerializer());

        // Use String serializer for hash keys and values too
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());

        return template;
    }
}