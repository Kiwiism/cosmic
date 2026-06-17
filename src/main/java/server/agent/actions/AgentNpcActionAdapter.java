package server.agent.actions;

import client.Character;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentPerceptionSnapshot;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Comparator;
import java.util.Optional;

public final class AgentNpcActionAdapter implements AgentActionAdapter {
    private static final int MAX_APPROACH_STEP_X = 180;
    private static final int MAX_APPROACH_STEP_Y = 120;
    private static final int INTERACTION_RANGE_SQ = 120 * 120;

    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.NPC;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        if (type != AgentIntentType.NPC) {
            return AgentActionResult.blockedByRuntime(capability(), type + " reached the NPC adapter unexpectedly");
        }
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot select an NPC without an available perception snapshot");
        }

        Optional<AgentPerceptionSnapshot.AgentVisibleObject> target = selectNpc(context);
        if (target.isEmpty()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No visible NPC is available for " + type,
                    npcDetailsJson(context, null, "NO_TARGET", null)
            );
        }

        AgentPerceptionSnapshot.AgentVisibleObject npc = target.get();
        if (npc.distanceSq() <= INTERACTION_RANGE_SQ) {
            return AgentActionResult.ok(
                    capability(),
                    "NPC " + npcLabel(npc) + " is ready for future dialog interaction",
                    false,
                    npcDetailsJson(context, npc, "NPC_READY", null)
            );
        }

        AgentActionResult approach = approachNpc(context, npc);
        return new AgentActionResult(
                approach.status(),
                capability(),
                approach.gameplayMutated()
                        ? "Moved toward NPC " + npcLabel(npc)
                        : "NPC " + npcLabel(npc) + " did not require movement",
                approach.policyAllowed(),
                approach.gameplayMutated(),
                approach.dryRun(),
                npcDetailsJson(context, npc, "APPROACHING_NPC", approach.detailsJson()),
                approach.completedAt()
        );
    }

    private Optional<AgentPerceptionSnapshot.AgentVisibleObject> selectNpc(AgentActionContext context) {
        String target = context.intent().argument();
        return context.perception().nearbyNpcs().stream()
                .filter(npc -> matchesTarget(npc, target))
                .min(Comparator.comparingLong(AgentPerceptionSnapshot.AgentVisibleObject::distanceSq));
    }

    private boolean matchesTarget(AgentPerceptionSnapshot.AgentVisibleObject npc, String target) {
        if (target == null || target.isBlank() || "nearest".equalsIgnoreCase(target.trim())) {
            return true;
        }
        String trimmed = target.trim();
        if (npc.name() != null && npc.name().equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (npc.templateId() != null && String.valueOf(npc.templateId()).equals(trimmed)) {
            return true;
        }
        return String.valueOf(npc.objectId()).equals(trimmed);
    }

    private AgentActionResult approachNpc(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject npc) {
        MapleMap map = context.managed().character().getMap();
        Character character = context.managed().character();
        if (map == null || character == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot approach NPC without an attached map");
        }

        Point start = character.getPosition();
        Point target = new Point(npc.x(), npc.y());
        Point step = boundedStep(start, target);
        Point grounded = map.getGroundBelow(step);
        Point destination = grounded == null ? step : grounded;
        long before = distanceSq(start, target);
        long after = distanceSq(destination, target);
        if (destination.equals(start)) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "NPC approach is stuck at " + pointLabel(start),
                    movementDetailsJson(context, start, destination, target, "STUCK", grounded != null)
            );
        }
        if (after >= before) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "NPC approach could not reduce distance to " + npcLabel(npc),
                    movementDetailsJson(context, start, destination, target, "NO_PROGRESS", grounded != null)
            );
        }

        map.movePlayer(character, destination);
        return AgentActionResult.ok(
                capability(),
                "Approached NPC " + npcLabel(npc),
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

    private String npcDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject npc,
            String state,
            String movementJson
    ) {
        return "{"
                + "\"npcState\":\"" + state + "\","
                + "\"intent\":\"" + context.intent().type().name() + "\","
                + "\"argument\":\"" + escapeJson(context.intent().argument()) + "\","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId() + ","
                + "\"agentPosition\":{\"x\":" + context.perception().x() + ",\"y\":" + context.perception().y() + "},"
                + "\"interactionRangeSq\":" + INTERACTION_RANGE_SQ + ","
                + "\"target\":" + (npc == null ? "null" : npcJson(npc)) + ","
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
                + "\"action\":\"APPROACH_NPC\","
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

    private String npcJson(AgentPerceptionSnapshot.AgentVisibleObject npc) {
        return "{"
                + "\"objectId\":" + npc.objectId() + ","
                + "\"npcId\":" + nullableNumber(npc.templateId()) + ","
                + "\"name\":\"" + escapeJson(npc.name()) + "\","
                + "\"position\":{\"x\":" + npc.x() + ",\"y\":" + npc.y() + "},"
                + "\"distanceSq\":" + npc.distanceSq()
                + "}";
    }

    private String npcLabel(AgentPerceptionSnapshot.AgentVisibleObject npc) {
        if (npc.name() != null && !npc.name().isBlank()) {
            return npc.name();
        }
        if (npc.templateId() != null) {
            return String.valueOf(npc.templateId());
        }
        return String.valueOf(npc.objectId());
    }

    private String pointLabel(Point point) {
        return "(" + point.x + "," + point.y + ")";
    }

    private String nullableNumber(Number value) {
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
