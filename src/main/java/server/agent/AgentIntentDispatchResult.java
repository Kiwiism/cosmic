package server.agent;

import server.agent.actions.AgentActionResult;

import java.time.Instant;

public record AgentIntentDispatchResult(
        AgentIntent intent,
        AgentActionStatus status,
        String message,
        AgentIntentCapability capability,
        boolean policyAllowed,
        boolean gameplayMutated,
        boolean dryRun,
        Instant dispatchedAt
) {
    public static AgentIntentDispatchResult ok(AgentIntent intent, String message) {
        AgentIntentCapability capability = AgentIntentCapability.fromIntent(intent.type());
        return new AgentIntentDispatchResult(intent, AgentActionStatus.OK, message, capability, true, false, true, Instant.now());
    }

    public static AgentIntentDispatchResult blocked(AgentIntent intent, String message) {
        AgentIntentCapability capability = AgentIntentCapability.fromIntent(intent.type());
        return new AgentIntentDispatchResult(intent, AgentActionStatus.BLOCKED, message, capability, false, false, true, Instant.now());
    }

    public static AgentIntentDispatchResult blockedByRuntime(AgentIntent intent, String message) {
        AgentIntentCapability capability = AgentIntentCapability.fromIntent(intent.type());
        return new AgentIntentDispatchResult(intent, AgentActionStatus.BLOCKED, message, capability, true, false, true, Instant.now());
    }

    public static AgentIntentDispatchResult fromActionResult(AgentIntent intent, AgentActionResult actionResult) {
        return new AgentIntentDispatchResult(
                intent,
                actionResult.status(),
                actionResult.message(),
                actionResult.capability(),
                actionResult.policyAllowed(),
                actionResult.gameplayMutated(),
                actionResult.dryRun(),
                actionResult.completedAt()
        );
    }
}
