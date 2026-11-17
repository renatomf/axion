package dev.finaxis.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the core service.
 *
 * <p>{@code @SpringBootApplication} turns on component scanning for this package and its
 * subpackages, and auto-configuration based on the libraries on the classpath. Feature modules
 * live in subpackages of {@code dev.finaxis.core}, so they are picked up automatically.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
