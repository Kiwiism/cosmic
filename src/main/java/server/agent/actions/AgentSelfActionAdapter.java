package server.agent.actions;

import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;

public final class AgentSelfActionAdapter implements AgentActionAdapter {
    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.SELF;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        return switch (type) {
            case IDLE -> AgentActionResult.ok(capability(), "Idle intent accepted as a no-op", false);
            case WAIT -> AgentActionResult.ok(capability(), "Wait intent accepted as a no-op", false);
            default -> AgentActionResult.blockedByRuntime(capability(), "Self adapter cannot execute " + type);
        };
    }
}
