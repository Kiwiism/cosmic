package server.agent.actions;

import server.agent.AgentIntentCapability;

public interface AgentActionAdapter {
    AgentIntentCapability capability();

    AgentActionResult execute(AgentActionContext context);
}
