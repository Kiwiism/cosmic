package com.cosmic.agentclient;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/client")
public class AgentClientController {
    private final AgentVirtualClientSystem virtualClientSystem;

    public AgentClientController(AgentVirtualClientSystem virtualClientSystem) {
        this.virtualClientSystem = virtualClientSystem;
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", "agent-client");
        result.put("runtime", "headless-v83-client");
        result.put("gameplayPath", "tcp-client-packets");
        return result;
    }

    @GetMapping("/sessions/{profileId}")
    Map<String, Object> snapshot(@PathVariable int profileId) {
        return virtualClientSystem.snapshot(profileId);
    }

    @PostMapping("/sessions/{profileId}/{action:prepare|enter|tick|release}")
    Map<String, Object> runtimeAction(@PathVariable int profileId, @PathVariable String action) {
        return virtualClientSystem.perform(profileId, action);
    }

    @PostMapping("/sessions/{profileId}/manual/key/start")
    Map<String, Object> keyStart(@PathVariable int profileId, @Valid @RequestBody ManualKey body) {
        return virtualClientSystem.manualKey(profileId, body.key(), true);
    }

    @PostMapping("/sessions/{profileId}/manual/key/stop")
    Map<String, Object> keyStop(@PathVariable int profileId, @Valid @RequestBody ManualKey body) {
        return virtualClientSystem.manualKey(profileId, body.key(), false);
    }

    @PostMapping("/sessions/{profileId}/manual/action")
    Map<String, Object> manualAction(@PathVariable int profileId, @Valid @RequestBody ManualAction body) {
        return virtualClientSystem.manualAction(profileId, body.action(), body.payload() == null ? Map.of() : body.payload());
    }

    record ManualKey(@NotBlank String key) {}

    record ManualAction(@NotBlank String action, Map<String, Object> payload) {}
}
