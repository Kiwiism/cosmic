package com.cosmic.agentclient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class AgentRuntimePulseScheduler {
    private final AgentVirtualClientSystem virtualClientSystem;
    private final boolean enabled;

    AgentRuntimePulseScheduler(AgentVirtualClientSystem virtualClientSystem,
                               @Value("${cosmic.agent-runtime.pulse-enabled:true}") boolean enabled) {
        this.virtualClientSystem = virtualClientSystem;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${cosmic.agent-runtime.pulse-millis:50}")
    void pulse() {
        if (enabled) {
            virtualClientSystem.pulseLiveMovement();
        }
    }

    @Scheduled(fixedDelayString = "${cosmic.agent-runtime.decision-millis:250}")
    void decide() {
        if (enabled) {
            virtualClientSystem.pulseLiveDecisions();
        }
    }
}
