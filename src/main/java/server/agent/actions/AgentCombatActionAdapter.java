package server.agent.actions;

import client.Character;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentPerceptionSnapshot;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Comparator;
import java.util.Optional;

public final class AgentCombatActionAdapter implements AgentActionAdapter {
    private static final int MAX_APPROACH_STEP_X = 180;
    private static final int MAX_APPROACH_STEP_Y = 120;
    private static final int ATTACK_RANGE_SQ = 150 * 150;

    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.COMBAT;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        if (type != AgentIntentType.ATTACK && type != AgentIntentType.GRIND) {
            return AgentActionResult.blockedByRuntime(capability(), type + " reached the combat adapter unexpectedly");
        }
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot select a combat target without an available perception snapshot");
        }

        Optional<AgentPerceptionSnapshot.AgentVisibleObject> target = selectMonster(context);
        if (target.isEmpty()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No alive visible monster is available for " + type,
                    combatDetailsJson(context, null, "NO_TARGET", null)
            );
        }

        AgentPerceptionSnapshot.AgentVisibleObject monster = target.get();
        if (monster.distanceSq() <= ATTACK_RANGE_SQ) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Combat target " + monsterLabel(monster) + " is in range, but attack execution is intentionally not enabled yet",
                    combatDetailsJson(context, monster, "ATTACK_READY", null)
            );
        }

        AgentActionResult approach = approachMonster(context, monster);
        return new AgentActionResult(
                approach.status(),
                capability(),
                approach.gameplayMutated()
                        ? "Moved toward combat target " + monsterLabel(monster)
                        : "Combat target " + monsterLabel(monster) + " did not require movement",
                approach.policyAllowed(),
                approach.gameplayMutated(),
                approach.dryRun(),
                combatDetailsJson(context, monster, "APPROACHING_TARGET", approach.detailsJson()),
                approach.completedAt()
        );
    }

    private Optional<AgentPerceptionSnapshot.AgentVisibleObject> selectMonster(AgentActionContext context) {
        String target = context.intent().argument();
        return context.perception().nearbyMonsters().stream()
                .filter(monster -> Boolean.TRUE.equals(monster.alive()))
                .filter(monster -> matchesTarget(monster, target))
                .min(Comparator.comparingLong(AgentPerceptionSnapshot.AgentVisibleObject::distanceSq));
    }

    private boolean matchesTarget(AgentPerceptionSnapshot.AgentVisibleObject monster, String target) {
        if (target == null || target.isBlank() || "nearest monster".equalsIgnoreCase(target.trim())) {
            return true;
        }
        String trimmed = target.trim();
        if (monster.name() != null && monster.name().equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (monster.templateId() != null && String.valueOf(monster.templateId()).equals(trimmed)) {
            return true;
        }
        return String.valueOf(monster.objectId()).equals(trimmed);
    }

    private AgentActionResult approachMonster(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject monster) {
        MapleMap map = context.managed().character().getMap();
        Character character = context.managed().character();
        if (map == null || character == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot approach combat target without an attached map");
        }

        Point start = character.getPosition();
        Point target = new Point(monster.x(), monster.y());
        Point step = boundedStep(start, target);
        Point grounded = map.getGroundBelow(step);
        Point destination = grounded == null ? step : grounded;
        long before = distanceSq(start, target);
        long after = distanceSq(destination, target);
        if (destination.equals(start)) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Combat approach is stuck at " + pointLabel(start),
                    movementDetailsJson(context, start, destination, target, "STUCK", grounded != null)
            );
        }
        if (after >= before) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "Combat approach could not reduce distance to " + monsterLabel(monster),
                    movementDetailsJson(context, start, destination, target, "NO_PROGRESS", grounded != null)
            );
        }

        map.movePlayer(character, destination);
        return AgentActionResult.ok(
                capability(),
                "Approached combat target " + monsterLabel(monster),
                true,
                movementDetailsJson(context, start, destination, target, "MOVED", grounded != null)
        );
    }

    private Point boundedStep(Point start, Point target) {
        int dx = clamp(target.x - start.x, -MAX_APPROACH_STEP_X, MAX_APPROACH_STEP_X);
        int dy = clamp(target.y - start.y, -MAX_APPROACH_STEP_Y, MAX_APPROACH_STEP_Y);
        return new Point(start.x + dx, start.y + dy);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private long distanceSq(Point a, Point b) {
        long dx = (long) a.x - b.x;
        long dy = (long) a.y - b.y;
        return dx * dx + dy * dy;
    }

    private String combatDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject monster,
            String state,
            String movementJson
    ) {
        return "{"
                + "\"combatState\":\"" + state + "\","
                + "\"intent\":\"" + context.intent().type().name() + "\","
                + "\"argument\":\"" + escapeJson(context.intent().argument()) + "\","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId() + ","
                + "\"agentPosition\":{\"x\":" + context.perception().x() + ",\"y\":" + context.perception().y() + "},"
                + "\"attackRangeSq\":" + ATTACK_RANGE_SQ + ","
                + "\"target\":" + (monster == null ? "null" : monsterJson(monster)) + ","
                + "\"movement\":" + (movementJson == null ? "null" : movementJson)
                + "}";
    }

    private String movementDetailsJson(
            AgentActionContext context,
            Point from,
            Point to,
            Point target,
            String state,
            boolean grounded
    ) {
        long before = distanceSq(from, target);
        long after = distanceSq(to, target);
        return "{"
                + "\"movementState\":\"" + state + "\","
                + "\"action\":\"APPROACH_COMBAT_TARGET\","
                + "\"world\":" + context.managed().client().getWorld() + ","
                + "\"channel\":" + context.managed().client().getChannel() + ","
                + "\"mapId\":" + context.managed().character().getMapId() + ","
                + "\"from\":{\"x\":" + from.x + ",\"y\":" + from.y + "},"
                + "\"to\":{\"x\":" + to.x + ",\"y\":" + to.y + "},"
                + "\"target\":{\"x\":" + target.x + ",\"y\":" + target.y + "},"
                + "\"stepLimit\":{\"x\":" + MAX_APPROACH_STEP_X + ",\"y\":" + MAX_APPROACH_STEP_Y + "},"
                + "\"grounded\":" + grounded + ","
                + "\"progressed\":" + (after < before) + ","
                + "\"distanceSqBefore\":" + before + ","
                + "\"distanceSqAfter\":" + after
                + "}";
    }

    private String monsterJson(AgentPerceptionSnapshot.AgentVisibleObject monster) {
        return "{"
                + "\"objectId\":" + monster.objectId() + ","
                + "\"monsterId\":" + nullableNumber(monster.templateId()) + ","
                + "\"name\":\"" + escapeJson(monster.name()) + "\","
                + "\"position\":{\"x\":" + monster.x() + ",\"y\":" + monster.y() + "},"
                + "\"distanceSq\":" + monster.distanceSq() + ","
                + "\"hp\":" + nullableNumber(monster.hp()) + ","
                + "\"maxHp\":" + nullableNumber(monster.maxHp()) + ","
                + "\"level\":" + nullableNumber(monster.level()) + ","
                + "\"alive\":" + nullableBoolean(monster.alive())
                + "}";
    }

    private String monsterLabel(AgentPerceptionSnapshot.AgentVisibleObject monster) {
        if (monster.name() != null && !monster.name().isBlank()) {
            return monster.name();
        }
        if (monster.templateId() != null) {
            return String.valueOf(monster.templateId());
        }
        return String.valueOf(monster.objectId());
    }

    private String pointLabel(Point point) {
        return "(" + point.x + "," + point.y + ")";
    }

    private String nullableNumber(Number value) {
        return value == null ? "null" : value.toString();
    }

    private String nullableBoolean(Boolean value) {
        return value == null ? "null" : value.toString();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
