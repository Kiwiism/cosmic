package server.agent;

import java.time.Instant;
import java.util.List;

public record AgentPerceptionSnapshot(
        boolean available,
        int world,
        int channel,
        int mapId,
        int x,
        int y,
        int players,
        int monsters,
        int drops,
        int npcs,
        int reactors,
        List<AgentVisibleObject> nearbyPlayers,
        List<AgentVisibleObject> nearbyMonsters,
        List<AgentVisibleObject> nearbyDrops,
        List<AgentVisibleObject> nearbyNpcs,
        List<AgentVisibleObject> nearbyReactors,
        String message,
        Instant capturedAt
) {
    public AgentPerceptionSnapshot {
        nearbyPlayers = List.copyOf(nearbyPlayers == null ? List.of() : nearbyPlayers);
        nearbyMonsters = List.copyOf(nearbyMonsters == null ? List.of() : nearbyMonsters);
        nearbyDrops = List.copyOf(nearbyDrops == null ? List.of() : nearbyDrops);
        nearbyNpcs = List.copyOf(nearbyNpcs == null ? List.of() : nearbyNpcs);
        nearbyReactors = List.copyOf(nearbyReactors == null ? List.of() : nearbyReactors);
    }

    public static AgentPerceptionSnapshot unavailable(AgentSpawnPlan plan, String message) {
        return new AgentPerceptionSnapshot(
                false,
                plan.world() == null ? -1 : plan.world(),
                plan.channel() == null ? -1 : plan.channel(),
                plan.mapId() == null ? -1 : plan.mapId(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                message,
                Instant.now()
        );
    }

    public record AgentVisibleObject(
            String type,
            int objectId,
            Integer templateId,
            String name,
            int x,
            int y,
            long distanceSq,
            Integer hp,
            Integer maxHp,
            Integer level,
            Integer quantity,
            Integer meso,
            Boolean alive,
            Integer state
    ) {
    }
}
