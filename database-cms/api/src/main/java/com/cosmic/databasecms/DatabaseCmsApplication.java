package com.cosmic.databasecms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DatabaseCmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(DatabaseCmsApplication.class, args);
    }
}
