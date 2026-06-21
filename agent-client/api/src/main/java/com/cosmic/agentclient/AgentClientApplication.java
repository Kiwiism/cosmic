package com.cosmic.agentclient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AgentClientApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentClientApplication.class, args);
    }
}
