package server.agent;

public record AgentCharacterLocation(
        int characterId,
        int world,
        int mapId,
        int spawnPoint
) {
}
