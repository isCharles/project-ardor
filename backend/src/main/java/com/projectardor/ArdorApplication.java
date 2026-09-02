package com.projectardor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ArdorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArdorApplication.class, args);
    }
}
