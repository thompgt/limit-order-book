package io.github.thompgt.lob.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class EngineMetricsTest {

    @Autowired
    private MeterRegistry registry;

    @Test
    void theEngineGaugesAreRegisteredAndReadable() {
        // Each of these answers a question you would actually ask at 3am, so a
        // silently missing gauge is worth failing a build over.
        assertThat(registry.get("lob.engine.queue.depth").gauge().value()).isNotNaN();
        assertThat(registry.get("lob.stream.dropped").gauge().value()).isZero();
        assertThat(registry.get("lob.pool.allocations").gauge().value()).isNotNaN();
        assertThat(registry.get("lob.orders.live").gauge().value()).isNotNaN();
    }

    @Test
    void thePoolReportsWhatItPreallocatedRatherThanGrowingIntoIt() {
        // Invariant 2 in the deployed service: the pool is filled at startup, so
        // allocations should not climb while orders are being traded. A rising
        // line here is the zero-allocation claim failing in production.
        double available = registry.get("lob.pool.available").gauge().value();
        assertThat(available).isGreaterThan(0.0);
    }
}
