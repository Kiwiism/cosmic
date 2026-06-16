package server.agent;

public record AgentSpawnPlan(
        boolean ready,
        AgentControlDecision controlDecision,
        AgentProfile profile,
        Integer world,
        Integer channel,
        Integer mapId,
        Integer spawnPoint
) {
    public static AgentSpawnPlan ready(AgentControlDecision decision, AgentProfile profile, AgentCharacterLocation location) {
        int channel = decision.channel() == null || decision.channel() < 1 ? 1 : decision.channel();
        return new AgentSpawnPlan(true, decision, profile, location.world(), channel, location.mapId(), location.spawnPoint());
    }

    public static AgentSpawnPlan denied(AgentControlDecision decision, AgentProfile profile) {
        return new AgentSpawnPlan(false, decision, profile, null, null, null, null);
    }
}
