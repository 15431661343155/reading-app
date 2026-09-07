package com.example.readingapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class ReadingAppBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadingAppBackendApplication.class, args);
    }

}
