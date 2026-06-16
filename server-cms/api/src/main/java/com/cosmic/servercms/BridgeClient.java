package com.cosmic.servercms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class BridgeClient {
    private final RestClient client;

    public BridgeClient(@Value("${cosmic.bridge.url}") String url,
                        @Value("${cosmic.bridge.token}") String token) {
        client = RestClient.builder().baseUrl(url)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> health() {
        try {
            return client.get().uri("/internal/admin/health").retrieve().body(Map.class);
        } catch (Exception exception) {
            return Map.of("status", "OFFLINE", "detail", exception.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> agentAction(int profileId, String action) {
        try {
            return client.post()
                    .uri("/internal/admin/agents/{profileId}/{action}", profileId, action)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception exception) {
            return Map.of("status", "OFFLINE", "detail", exception.getClass().getSimpleName());
        }
    }

}
