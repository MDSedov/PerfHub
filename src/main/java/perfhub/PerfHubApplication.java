package com.example.chronicler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ChroniclerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChroniclerApplication.class, args);
    }
}
