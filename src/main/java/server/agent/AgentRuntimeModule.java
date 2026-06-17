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
    private final AgentKnowledgeService knowledgeService;
    private final AgentScriptRunner scriptRunner;
    private final AgentScriptRepository scriptRepository;
    private final AgentGoalRepository goalRepository;
    private final AgentPlannerService plannerService;
    private final AgentPolicyRepository policyRepository;
    private final AgentIntentPolicyService intentPolicyService;
    private final AgentIntentDispatcher intentDispatcher;
    private final AgentPilotService pilotService;
    private final AgentTickScheduler tickScheduler;

    public AgentRuntimeModule() {
        this(new AgentRegistry(new AgentRepository()), new AgentRuntimeService());
    }

    AgentRuntimeModule(AgentRegistry registry, AgentRuntimeService runtimeService) {
        this.registry = registry;
        this.runtimeService = runtimeService;
        this.controlShell = new AgentControlShell(runtimeService);
        this.spawnCoordinator = new AgentSpawnCoordinator(runtimeService, controlShell);
        this.perceptionService = new AgentPerceptionService();
        this.knowledgeService = new AgentKnowledgeService();
        this.scriptRunner = new AgentScriptRunner();
        this.scriptRepository = new AgentScriptRepository();
        this.goalRepository = new AgentGoalRepository();
        this.plannerService = new AgentPlannerService(goalRepository, scriptRunner, scriptRepository);
        this.policyRepository = new AgentPolicyRepository();
        this.intentPolicyService = new AgentIntentPolicyService(policyRepository);
        this.intentDispatcher = new AgentIntentDispatcher(runtimeService, intentPolicyService);
        this.pilotService = new AgentPilotService(perceptionService, knowledgeService, plannerService, runtimeService, intentDispatcher, goalRepository);
        this.tickScheduler = new AgentTickScheduler(spawnCoordinator, pilotService);
    }

    @Override
    public String name() {
        return "AgentRuntime";
    }

    @Override
    public void start(RuntimeModuleContext context) throws Exception {
        registry.refreshEnabledProfiles();
        tickScheduler.start();
        log.info("Agent runtime registry loaded {} enabled profiles", registry.enabledProfileCount());
    }

    @Override
    public void stop(RuntimeModuleContext context) {
        tickScheduler.stop();
        spawnCoordinator.releaseAll("Agent runtime module stopping");
        log.info("Agent runtime stopped with {} cached profiles, {} prepared characters, and {} entered characters",
                registry.enabledProfileCount(), spawnCoordinator.preparedCount(), spawnCoordinator.enteredCount());
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

    public AgentPilotService pilotService() {
        return pilotService;
    }

    public AgentIntentDispatcher intentDispatcher() {
        return intentDispatcher;
    }

    public AgentIntentPolicyService intentPolicyService() {
        return intentPolicyService;
    }

    public AgentTickScheduler tickScheduler() {
        return tickScheduler;
    }
}
