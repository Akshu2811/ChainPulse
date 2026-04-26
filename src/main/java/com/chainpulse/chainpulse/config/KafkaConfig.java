package com.chainpulse.chainpulse.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaConfig — sets up Kafka producer and consumer for ChainPulse.
 *
 * Spring Boot 4.0 removed JsonSerializer/JsonDeserializer from spring-kafka.
 * So we use plain String for both key and value.
 * ShipmentEventDto ↔ JSON conversion is done manually using
 * Jackson ObjectMapper inside the producer and consumer classes.
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // The Kafka topic — all shipment events flow through here
    public static final String SHIPMENT_EVENTS_TOPIC = "shipment-events";

    /**
     * Creates the Kafka topic "shipment-events" automatically on startup.
     * 1 partition, replication factor 1 — enough for local development.
     */
    @Bean
    public NewTopic shipmentEventsTopic() {
        return new NewTopic(SHIPMENT_EVENTS_TOPIC, 1, (short) 1);
    }

    /**
     * ProducerFactory — tells Kafka how to send messages.
     * Both key and value are plain Strings.
     * ShipmentEventDto → JSON string conversion happens in KafkaProducerService.
     */
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    /**
     * KafkaTemplate — the main tool used to SEND messages to Kafka.
     * Injected into KafkaProducerService via @Autowired.
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * ConsumerFactory — tells Kafka how to receive messages.
     * Both key and value come in as plain Strings.
     * JSON string → ShipmentEventDto conversion happens in ShipmentEventConsumer.
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "chainpulse-group");
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * KafkaListenerContainerFactory — connects consumerFactory
     * to @KafkaListener annotations in ShipmentEventConsumer.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String>
    kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }
}