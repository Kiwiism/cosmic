package server.agent;

import java.sql.SQLException;
import java.util.Optional;

public final class AgentSpawnPlanner {
    private final AgentRepository repository;
    private final AgentControlGuard controlGuard;

    public AgentSpawnPlanner(AgentRepository repository, AgentControlGuard controlGuard) {
        this.repository = repository;
        this.controlGuard = controlGuard;
    }

    public AgentSpawnPlan plan(AgentProfile profile) throws SQLException {
        AgentControlDecision decision = controlGuard.canRuntimeControl(profile);
        if (!decision.allowed()) {
            return AgentSpawnPlan.denied(decision, profile);
        }

        Optional<AgentCharacterLocation> location = repository.findCharacterLocation(profile.characterId());
        if (location.isEmpty()) {
            return AgentSpawnPlan.denied(
                    AgentControlDecision.denied(
                            AgentControlDenyReason.CHARACTER_NOT_FOUND,
                            "Character location could not be loaded"
                    ),
                    profile
            );
        }

        return AgentSpawnPlan.ready(decision, profile, location.get());
    }
}
