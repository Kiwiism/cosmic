package server.agent;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class AgentPlannerService {
    private final AgentGoalRepository goalRepository;
    private final AgentScriptRunner scriptRunner;
    private final AgentScriptRepository scriptRepository;
    private final AgentNavigationGraphService navigationGraphService;
    private final AgentRuntimeRepository runtimeRepository;

    public AgentPlannerService(
            AgentGoalRepository goalRepository,
            AgentScriptRunner scriptRunner,
            AgentScriptRepository scriptRepository,
            AgentNavigationGraphService navigationGraphService,
            AgentRuntimeRepository runtimeRepository
    ) {
        this.goalRepository = goalRepository;
        this.scriptRunner = scriptRunner;
        this.scriptRepository = scriptRepository;
        this.navigationGraphService = navigationGraphService;
        this.runtimeRepository = runtimeRepository;
    }

    public AgentPlan plan(AgentManagedCharacter managed, AgentPerceptionSnapshot perception, AgentKnowledgeSnapshot knowledge) throws SQLException {
        Optional<AgentGoal> goal = goalRepository.findNextActiveGoal(managed.profileId());
        if (goal.isPresent()) {
            return planGoal(goal.get(), perception, knowledge);
        }
        Optional<AgentPlan> scriptPlan = planConfiguredScript(managed.profile(), managed.session());
        if (scriptPlan.isPresent()) {
            return scriptPlan.get();
        }
        return planBehaviorFallback(managed, perception, knowledge);
    }

    private AgentPlan planGoal(AgentGoal goal, AgentPerceptionSnapshot perception, AgentKnowledgeSnapshot knowledge) {
        String goalType = goal.goalType() == null ? "" : goal.goalType().trim().toUpperCase(Locale.ROOT);
        AgentIntent intent = switch (goalType) {
            case "IDLE" -> AgentIntent.idle(1000);
            case "WAIT" -> AgentIntent.waitFor(1000);
            case "CHAT", "SAY" -> AgentIntent.say(valueOr(goal.targetRef(), "Hello."));
            case "ROAM" -> AgentIntent.roam(valueOr(goal.targetRef(), "goal:" + goal.id()));
            case "FOLLOW", "FOLLOW_CHARACTER", "COMPANION", "HANG_AROUND" ->
                    AgentIntent.followCharacter(valueOr(goal.targetRef(), "nearest player"));
            case "MOVE", "MOVE_TO_MAP", "TRAVEL" -> goal.targetMap() == null
                    ? AgentIntent.roam(valueOr(goal.targetRef(), "find route"))
                    : AgentIntent.moveToMap(String.valueOf(goal.targetMap()));
            case "NPC", "TALK_TO_NPC" -> AgentIntent.npc(valueOr(goal.targetRef(), "nearest"));
            case "SHOP" -> AgentIntent.shop(valueOr(goal.targetRef(), "nearest"));
            case "GRIND", "GRIND_TO_LEVEL" -> AgentIntent.grind(valueOr(goal.targetRef(), bestVisibleMonster(perception)));
            case "LOOT" -> AgentIntent.loot(valueOr(goal.targetRef(), bestVisibleDrop(perception)));
            case "USE_ITEM" -> AgentIntent.useItem(valueOr(goal.targetRef(), "potion"));
            case "EQUIP" -> AgentIntent.equip(valueOr(goal.targetRef(), "best available"));
            default -> AgentIntent.waitFor(1000);
        };
        String reason = "Selected goal #" + goal.id() + " (" + goal.goalType() + ") at priority " + goal.priority()
                + " for level " + knowledge.level() + " job " + knowledge.jobId()
                + routeReason(goal, perception);
        return new AgentPlan(intent, goal, "agent_goals:" + goal.id(), reason);
    }

    private Optional<AgentPlan> planConfiguredScript(AgentProfile profile, AgentRuntimeSession session) throws SQLException {
        Optional<ScriptBody> scriptBody = resolveConfiguredScriptBody(profile);
        if (scriptBody.isEmpty()) {
            return Optional.empty();
        }
        List<AgentIntent> intents = scriptRunner.parse(scriptBody.get().body());
        int index = nextScriptIndex(session, intents.size());
        AgentIntent intent = intents.get(index);
        String source = scriptBody.get().source() + "#step " + (index + 1) + "/" + intents.size();
        return Optional.of(new AgentPlan(intent, null, source, "No active goal; using " + source));
    }

    private AgentPlan planBehaviorFallback(
            AgentManagedCharacter managed,
            AgentPerceptionSnapshot perception,
            AgentKnowledgeSnapshot knowledge
    ) throws SQLException {
        String behavior = normalizedBehavior(managed.profile());
        long previousPlans = managed.session() == null ? 0L : runtimeRepository.countSessionActions(managed.session().id(), "INTENT_PLAN");
        AgentIntent intent = switch (behavior) {
            case "GRINDER", "GRIND", "TRAINER", "TRAIN" -> grindBehavior(perception);
            case "LOOTER", "LOOT" -> lootBehavior(perception);
            case "COMPANION", "FOLLOWER", "FOLLOW", "HANG_AROUND", "HANGAROUND" ->
                    companionBehavior(managed, perception);
            case "TOWN_IDLER", "TOWNIDLER", "TOWN", "SOCIAL", "IDLER" -> townIdleBehavior(perception, previousPlans);
            case "ROAMER", "ROAM" -> AgentIntent.roam("behavior:" + behavior);
            default -> AgentIntent.idle(30_000L);
        };
        return new AgentPlan(
                intent,
                null,
                "behavior_profile:" + behavior,
                "No active goal or script; selected behavior " + behavior
                        + " for level " + knowledge.level() + " job " + knowledge.jobId()
        );
    }

    private AgentIntent grindBehavior(AgentPerceptionSnapshot perception) {
        if (perception != null && perception.available() && !perception.nearbyDrops().isEmpty()) {
            return AgentIntent.loot(bestVisibleDrop(perception));
        }
        if (perception != null && perception.available() && !perception.nearbyMonsters().isEmpty()) {
            return AgentIntent.grind(bestVisibleMonster(perception));
        }
        return AgentIntent.roam("behavior:grinder find monsters");
    }

    private AgentIntent lootBehavior(AgentPerceptionSnapshot perception) {
        if (perception != null && perception.available() && !perception.nearbyDrops().isEmpty()) {
            return AgentIntent.loot(bestVisibleDrop(perception));
        }
        return AgentIntent.roam("behavior:looter find drops");
    }

    private AgentIntent companionBehavior(AgentManagedCharacter managed, AgentPerceptionSnapshot perception) {
        String target = bestVisiblePlayer(perception, managed.characterId());
        if (target != null) {
            return AgentIntent.followCharacter(target);
        }
        return AgentIntent.roam("behavior:companion find players");
    }

    private AgentIntent townIdleBehavior(AgentPerceptionSnapshot perception, long previousPlans) {
        if (previousPlans % 4 == 0 && perception != null && perception.available() && perception.players() > 1) {
            return AgentIntent.roam("behavior:town idler social drift");
        }
        return AgentIntent.waitFor(5_000L);
    }

    private int nextScriptIndex(AgentRuntimeSession session, int scriptLength) throws SQLException {
        if (session == null || scriptLength <= 1) {
            return 0;
        }
        long previousPlans = runtimeRepository.countSessionActions(session.id(), "INTENT_PLAN");
        return (int) (previousPlans % scriptLength);
    }

    private String bestVisibleMonster(AgentPerceptionSnapshot perception) {
        return perception.nearbyMonsters().stream()
                .findFirst()
                .map(monster -> monster.templateId() == null ? monster.name() : String.valueOf(monster.templateId()))
                .orElse("nearest monster");
    }

    private String bestVisibleDrop(AgentPerceptionSnapshot perception) {
        return perception.nearbyDrops().stream()
                .findFirst()
                .map(drop -> drop.templateId() == null ? "meso" : String.valueOf(drop.templateId()))
                .orElse("nearest drop");
    }

    private String bestVisiblePlayer(AgentPerceptionSnapshot perception, int ownCharacterId) {
        if (perception == null || !perception.available()) {
            return null;
        }
        return perception.nearbyPlayers().stream()
                .filter(player -> player.templateId() == null || player.templateId() != ownCharacterId)
                .findFirst()
                .map(player -> player.templateId() == null ? player.name() : String.valueOf(player.templateId()))
                .orElse(null);
    }

    private String routeReason(AgentGoal goal, AgentPerceptionSnapshot perception) {
        if (goal.targetMap() == null || perception == null || !perception.available()) {
            return "";
        }

        String goalType = goal.goalType() == null ? "" : goal.goalType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("MOVE", "MOVE_TO_MAP", "TRAVEL").contains(goalType)) {
            return "";
        }

        try {
            AgentNavigationRoute route = navigationGraphService.findLoadedRoute(
                    perception.world(),
                    perception.channel(),
                    perception.mapId(),
                    goal.targetMap()
            );
            if (!route.found()) {
                return "; route preview unavailable: " + route.message();
            }
            if (route.steps().isEmpty()) {
                return "; route preview: already on target map";
            }

            AgentPortalEdge nextStep = route.steps().get(0);
            return "; route preview: " + route.steps().size() + " loaded portal step(s), next portal "
                    + nextStep.portalName() + " -> map " + nextStep.toMapId();
        } catch (RuntimeException e) {
            return "; route preview failed safely: " + e.getClass().getSimpleName();
        }
    }

    private Optional<ScriptBody> resolveConfiguredScriptBody(AgentProfile profile) throws SQLException {
        String scriptName = profile.scriptName();
        if (scriptName != null && scriptName.strip().startsWith(AgentPilotService.INLINE_SCRIPT_PREFIX)) {
            return Optional.of(new ScriptBody(
                    "inline script_name",
                    scriptName.strip().substring(AgentPilotService.INLINE_SCRIPT_PREFIX.length()).strip()
            ));
        }
        if (scriptName == null || scriptName.isBlank()) {
            return Optional.empty();
        }

        Optional<AgentScript> script = scriptRepository.findEnabledByName(scriptName);
        if (script.isPresent()) {
            AgentScript agentScript = script.get();
            return Optional.of(new ScriptBody("agent_scripts:" + agentScript.name() + "@v" + agentScript.version(), agentScript.body()));
        }

        return Optional.empty();
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizedBehavior(AgentProfile profile) {
        String value = profile.behaviorProfile();
        if (value == null || value.isBlank() || "default".equalsIgnoreCase(value.trim())) {
            value = profile.defaultMode();
        }
        if (value == null || value.isBlank()) {
            return "IDLE";
        }
        return value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }

    private record ScriptBody(String source, String body) {
    }
}
