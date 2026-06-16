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
        Instant loadedAt,
        Instant enteredAt
) {
    public int profileId() {
        return profile.id();
    }

    public int characterId() {
        return profile.characterId();
    }

    public boolean enteredWorld() {
        return enteredAt != null;
    }

    public AgentManagedCharacter withCharacter(Character nextCharacter, Instant nextLoadedAt) {
        return new AgentManagedCharacter(profile, session, client, nextCharacter, spawnPlan, nextLoadedAt, enteredAt);
    }

    public AgentManagedCharacter markEntered(Instant instant) {
        return new AgentManagedCharacter(profile, session, client, character, spawnPlan, loadedAt, instant);
    }
}
