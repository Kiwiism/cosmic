package server.agent.actions;

import server.agent.AgentIntentCapability;
import server.agent.AgentNavigationGraphService;

import java.util.EnumMap;
import java.util.Map;

public final class AgentActionService {
    private final Map<AgentIntentCapability, AgentActionAdapter> adapters = new EnumMap<>(AgentIntentCapability.class);

    public AgentActionService(AgentNavigationGraphService navigationGraphService) {
        register(new AgentSelfActionAdapter());
        register(new AgentNavigationActionAdapter(navigationGraphService));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.CHAT, "Chat"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.COMBAT, "Combat"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.LOOT, "Loot"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.NPC, "NPC"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.SHOP, "Shop"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.TRADE, "Trade"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.PARTY, "Party"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.INVENTORY, "Inventory"));
        register(new AgentRuntimeBlockedActionAdapter(AgentIntentCapability.SCRIPT, "Script"));
    }

    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentCapability capability = context.policyDecision().capability();
        AgentActionAdapter adapter = adapters.get(capability);
        if (adapter == null) {
            return AgentActionResult.blockedByRuntime(capability, "No agent action adapter is registered for " + capability);
        }
        return adapter.execute(context);
    }

    private void register(AgentActionAdapter adapter) {
        adapters.put(adapter.capability(), adapter);
    }
}
