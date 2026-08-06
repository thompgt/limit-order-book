package io.github.thompgt.lob.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the order-book service.
 *
 * <p>Spring lives here and only here. The matching engine itself is plain Java
 * in {@code engine-core}; this module adapts it to HTTP and WebSocket and
 * feeds it from a single-consumer command queue so the engine stays
 * single-threaded under concurrent requests.
 */
@SpringBootApplication
@EnableScheduling // the depth ticker samples the book on a clock
public class LobApplication {

    public static void main(String[] args) {
        SpringApplication.run(LobApplication.class, args);
    }
}
