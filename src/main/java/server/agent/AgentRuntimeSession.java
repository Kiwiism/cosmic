package server.agent;

import java.time.Instant;

public record AgentRuntimeSession(
        long id,
        int agentProfileId,
        int characterId,
        int world,
        int channel,
        int mapId,
        AgentRuntimeState state,
        Long currentGoalId,
        String currentTask,
        Instant startedAt,
        Instant lastTickAt,
        Instant endedAt,
        String stopReason
) {
}
