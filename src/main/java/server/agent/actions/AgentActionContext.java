package server.agent.actions;

import server.agent.AgentIntent;
import server.agent.AgentIntentPolicyDecision;
import server.agent.AgentManagedCharacter;
import server.agent.AgentPerceptionSnapshot;

import java.time.Instant;

public record AgentActionContext(
        AgentManagedCharacter managed,
        AgentIntent intent,
        AgentPerceptionSnapshot perception,
        AgentIntentPolicyDecision policyDecision,
        String scriptSource,
        Instant requestedAt
) {
    public AgentActionContext {
        requestedAt = requestedAt == null ? Instant.now() : requestedAt;
    }

    public int profileId() {
        return managed.profileId();
    }

    public long sessionId() {
        return managed.session().id();
    }
}
