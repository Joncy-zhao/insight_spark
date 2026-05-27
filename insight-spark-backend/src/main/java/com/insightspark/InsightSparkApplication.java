package com.insightspark;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class InsightSparkApplication {
    public static void main(String[] args) {
        SpringApplication.run(InsightSparkApplication.class, args);
    }
}
