package com.chainpulse.chainpulse;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Requires Kafka, Redis, PostgreSQL — run locally only")
class ChainPulseApplicationTests {

    @Test
    void contextLoads() {
    }

}