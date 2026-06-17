package server.agent;

import java.sql.SQLException;
import java.time.Instant;

/**
 * Dry-run pilot boundary for future agent control.
 *
 * This service reads perception and script data, chooses the next intent, and
 * logs that decision. It deliberately does not move, chat, attack, trade, loot,
 * or call gameplay handlers.
 */
public final class AgentPilotService {
    static final String INLINE_SCRIPT_PREFIX = "inline:";

    private final AgentPerceptionService perceptionService;
    private final AgentKnowledgeService knowledgeService;
    private final AgentPlannerService plannerService;
    private final AgentRuntimeService runtimeService;
    private final AgentIntentDispatcher intentDispatcher;
    private final AgentGoalRepository goalRepository;

    public AgentPilotService(
            AgentPerceptionService perceptionService,
            AgentKnowledgeService knowledgeService,
            AgentPlannerService plannerService,
            AgentRuntimeService runtimeService,
            AgentIntentDispatcher intentDispatcher,
            AgentGoalRepository goalRepository
    ) {
        this.perceptionService = perceptionService;
        this.knowledgeService = knowledgeService;
        this.plannerService = plannerService;
        this.runtimeService = runtimeService;
        this.intentDispatcher = intentDispatcher;
        this.goalRepository = goalRepository;
    }

    public AgentPilotTickResult dryRunTick(AgentManagedCharacter managed) throws SQLException {
        if (!managed.enteredWorld()) {
            throw new IllegalStateException("Agent must enter the world before pilot ticks can run");
        }

        AgentPerceptionSnapshot perception = perceptionService.snapshot(managed);
        AgentKnowledgeSnapshot knowledge = knowledgeService.snapshot(managed.character());
        AgentPlan plan = plannerService.plan(managed, perception, knowledge);
        AgentIntent intent = plan.intent();

        String message = "Planned " + intent.type() + " intent from " + plan.source() + ": " + plan.reason();
        runtimeService.logPlannedIntent(managed, intent, perception, knowledge, plan, message);
        AgentIntentDispatchResult dispatchResult = intentDispatcher.dispatch(managed, intent, perception, plan.source());
        if (plan.hasGoal()) {
            goalRepository.recordPlanningTick(plan.goal(), intent, dispatchResult, perception, plan.reason());
        }
        runtimeService.rememberPilotTick(managed, intent, dispatchResult, perception, knowledge, plan);
        runtimeService.heartbeat(managed.session(), message);

        return new AgentPilotTickResult(
                managed.profileId(),
                managed.session().id(),
                intent,
                dispatchResult,
                perception,
                plan.source(),
                message,
                Instant.now()
        );
    }
}
