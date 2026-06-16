package server.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.runtime.RuntimeModule;
import server.runtime.RuntimeModuleContext;

/**
 * Dormant module boundary for future server-side agents.
 *
 * This module only loads enabled agent profiles into an in-memory registry.
 * It does not log characters in, spawn actors, move, chat, fight, trade, or
 * mutate gameplay state.
 */
public final class AgentRuntimeModule implements RuntimeModule {
    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeModule.class);

    private final AgentRegistry registry;
    private final AgentRuntimeService runtimeService;

    public AgentRuntimeModule() {
        this(new AgentRegistry(new AgentRepository()), new AgentRuntimeService());
    }

    AgentRuntimeModule(AgentRegistry registry, AgentRuntimeService runtimeService) {
        this.registry = registry;
        this.runtimeService = runtimeService;
    }

    @Override
    public String name() {
        return "AgentRuntime";
    }

    @Override
    public void start(RuntimeModuleContext context) throws Exception {
        registry.refreshEnabledProfiles();
        log.info("Agent runtime registry loaded {} enabled profiles", registry.enabledProfileCount());
    }

    @Override
    public void stop(RuntimeModuleContext context) {
        log.info("Agent runtime stopped with {} cached profiles", registry.enabledProfileCount());
    }

    public AgentRegistry registry() {
        return registry;
    }

    public AgentRuntimeService runtimeService() {
        return runtimeService;
    }
}
