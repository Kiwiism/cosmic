package server.agent;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative goal completion checks.
 *
 * This does not perform gameplay actions. It only marks goals complete when
 * the current snapshot already proves the target has been reached.
 */
public final class AgentGoalProgressEvaluator {
    private static final Pattern TARGET_LEVEL_PATTERN = Pattern.compile("\"targetLevel\"\\s*:\\s*(\\d+)");

    public AgentGoalProgressDecision evaluate(
            AgentPlan plan,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception,
            AgentKnowledgeSnapshot knowledge
    ) {
        AgentGoal goal = plan.goal();
        if (goal == null) {
            return AgentGoalProgressDecision.running("No goal selected");
        }
        if (dispatchResult.status() == AgentActionStatus.FAILED) {
            return AgentGoalProgressDecision.failed("Runtime dispatch failed: " + dispatchResult.message());
        }

        String goalType = goal.goalType() == null ? "" : goal.goalType().trim().toUpperCase(Locale.ROOT);
        return switch (goalType) {
            case "IDLE", "WAIT" -> timingGoal(dispatchResult);
            case "MOVE", "MOVE_TO_MAP", "TRAVEL" -> mapGoal(goal, perception);
            case "FOLLOW", "FOLLOW_CHARACTER", "COMPANION", "HANG_AROUND" ->
                    AgentGoalProgressDecision.running("Follow goals stay active while the target remains the companion objective");
            case "GRIND", "GRIND_TO_LEVEL" -> levelGoal(goal, knowledge);
            case "LOOT" -> detailsStateGoal(dispatchResult, "\"lootState\":\"PICKED_UP\"", "Requested loot was picked up");
            case "USE_ITEM", "USEITEM" ->
                    detailsStateGoal(dispatchResult, "\"inventoryState\":\"RECOVERY_USED\"", "Requested recovery item was consumed");
            case "NPC", "TALK_TO_NPC" ->
                    detailsStateGoal(dispatchResult, "\"npcState\":\"NPC_READY\"", "Requested NPC is in interaction range");
            case "SHOP" -> detailsAnyStateGoal(
                    dispatchResult,
                    "Requested shop is in interaction range",
                    "\"shopState\":\"SHOP_READY\"",
                    "\"shopState\":\"RECOVERY_SHOP_READY\"",
                    "\"shopState\":\"RECOVERY_BOUGHT\""
            );
            default -> AgentGoalProgressDecision.running("Goal requires a future executor before completion can be proven");
        };
    }

    private AgentGoalProgressDecision timingGoal(AgentIntentDispatchResult dispatchResult) {
        if (dispatchResult.status() == AgentActionStatus.OK) {
            return AgentGoalProgressDecision.completed("No-op timing goal was accepted by the runtime dispatcher");
        }
        return AgentGoalProgressDecision.running("Timing goal is still waiting for an accepted dispatch");
    }

    private AgentGoalProgressDecision mapGoal(AgentGoal goal, AgentPerceptionSnapshot perception) {
        if (goal.targetMap() != null && perception.mapId() == goal.targetMap()) {
            return AgentGoalProgressDecision.completed("Character is already on the target map");
        }
        return AgentGoalProgressDecision.running("Character is not on the target map yet");
    }

    private AgentGoalProgressDecision levelGoal(AgentGoal goal, AgentKnowledgeSnapshot knowledge) {
        Integer targetLevel = targetLevel(goal);
        if (targetLevel != null && knowledge.level() >= targetLevel) {
            return AgentGoalProgressDecision.completed("Character already reached target level " + targetLevel);
        }
        return AgentGoalProgressDecision.running(targetLevel == null
                ? "No target level configured in target_ref or parameters_json"
                : "Character level " + knowledge.level() + " is below target level " + targetLevel);
    }

    private AgentGoalProgressDecision detailsStateGoal(
            AgentIntentDispatchResult dispatchResult,
            String expectedNeedle,
            String completedReason
    ) {
        return detailsAnyStateGoal(dispatchResult, completedReason, expectedNeedle);
    }

    private AgentGoalProgressDecision detailsAnyStateGoal(
            AgentIntentDispatchResult dispatchResult,
            String completedReason,
            String... expectedNeedles
    ) {
        if (dispatchResult.status() != AgentActionStatus.OK) {
            return AgentGoalProgressDecision.running("Waiting for an accepted dispatch before completion can be proven");
        }
        String details = dispatchResult.detailsJson();
        if (details != null) {
            for (String expectedNeedle : expectedNeedles) {
                if (details.contains(expectedNeedle)) {
                    return AgentGoalProgressDecision.completed(completedReason);
                }
            }
        }
        return AgentGoalProgressDecision.running("Action accepted but completion state is not reached yet");
    }

    private Integer targetLevel(AgentGoal goal) {
        Integer fromRef = parsePositiveInt(goal.targetRef());
        if (fromRef != null) {
            return fromRef;
        }

        if (goal.parametersJson() == null) {
            return null;
        }
        Matcher matcher = TARGET_LEVEL_PATTERN.matcher(goal.parametersJson());
        return matcher.find() ? parsePositiveInt(matcher.group(1)) : null;
    }

    private Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
