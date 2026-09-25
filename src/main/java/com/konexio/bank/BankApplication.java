package com.konexio.bank;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Konexio Online Bank — a modular monolith: one application, one database, one
 * schema per module (README.md, "Architecture").
 *
 * <p>Each package directly under this one is a Spring Modulith application
 * module. {@code shared} declares itself OPEN, so every module may use it;
 * modules reach each other only through their {@code *Api} facades and events.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BankApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankApplication.class, args);
    }
}
