package com.assignment.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AssignmentGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssignmentGatewayApplication.class, args);
    }
}
