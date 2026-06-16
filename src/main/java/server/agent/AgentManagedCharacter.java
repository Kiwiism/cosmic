package server.agent;

import client.Character;
import client.Client;

import java.time.Instant;

public record AgentManagedCharacter(
        AgentProfile profile,
        AgentRuntimeSession session,
        Client client,
        Character character,
        AgentSpawnPlan spawnPlan,
        Instant loadedAt
) {
    public int profileId() {
        return profile.id();
    }

    public int characterId() {
        return profile.characterId();
    }
}
