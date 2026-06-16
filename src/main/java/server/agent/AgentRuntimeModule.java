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
    private final AgentControlShell controlShell;
    private final AgentSpawnCoordinator spawnCoordinator;
    private final AgentPerceptionService perceptionService;
    private final AgentScriptRunner scriptRunner;

    public AgentRuntimeModule() {
        this(new AgentRegistry(new AgentRepository()), new AgentRuntimeService());
    }

    AgentRuntimeModule(AgentRegistry registry, AgentRuntimeService runtimeService) {
        this.registry = registry;
        this.runtimeService = runtimeService;
        this.controlShell = new AgentControlShell(runtimeService);
        this.spawnCoordinator = new AgentSpawnCoordinator(runtimeService, controlShell);
        this.perceptionService = new AgentPerceptionService();
        this.scriptRunner = new AgentScriptRunner();
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
        spawnCoordinator.releaseAll("Agent runtime module stopping");
        log.info("Agent runtime stopped with {} cached profiles and {} prepared characters",
                registry.enabledProfileCount(), spawnCoordinator.preparedCount());
    }

    public AgentRegistry registry() {
        return registry;
    }

    public AgentRuntimeService runtimeService() {
        return runtimeService;
    }

    public AgentControlShell controlShell() {
        return controlShell;
    }

    public AgentPerceptionService perceptionService() {
        return perceptionService;
    }

    public AgentSpawnCoordinator spawnCoordinator() {
        return spawnCoordinator;
    }

    public AgentScriptRunner scriptRunner() {
        return scriptRunner;
    }
}
