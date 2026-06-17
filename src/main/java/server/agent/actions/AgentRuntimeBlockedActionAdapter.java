package server.agent.actions;

import server.agent.AgentIntentCapability;

public final class AgentRuntimeBlockedActionAdapter implements AgentActionAdapter {
    private final AgentIntentCapability capability;
    private final String actionFamily;

    public AgentRuntimeBlockedActionAdapter(AgentIntentCapability capability, String actionFamily) {
        this.capability = capability;
        this.actionFamily = actionFamily;
    }

    @Override
    public AgentIntentCapability capability() {
        return capability;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        return AgentActionResult.blockedByRuntime(capability,
                actionFamily + " capability is policy-enabled but its gameplay adapter is not implemented yet");
    }
}
