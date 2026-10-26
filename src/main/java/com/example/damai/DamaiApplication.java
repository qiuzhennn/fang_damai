package com.example.damai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DamaiApplication {

    public static void main(String[] args) {
        SpringApplication.run(DamaiApplication.class, args);
    }
}
