package server.agent.actions;

import server.agent.AgentIntentCapability;
import server.agent.AgentPerceptionSnapshot;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.Comparator;
import java.util.Optional;

public final class AgentLootActionAdapter implements AgentActionAdapter {
    private static final int MAX_APPROACH_STEP_X = 180;
    private static final int MAX_APPROACH_STEP_Y = 120;
    private static final int PICKUP_READY_X_DISTANCE = 80;
    private static final int PICKUP_READY_Y_DISTANCE = 60;
    private static final int MAX_PICKUP_X_DISTANCE = 800;
    private static final int MAX_PICKUP_Y_DISTANCE = 600;

    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.LOOT;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot loot without an available perception snapshot");
        }
        if (context.managed().character() == null || context.managed().character().getMap() == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot loot without an attached character map");
        }

        Optional<AgentPerceptionSnapshot.AgentVisibleObject> selected = selectDrop(context);
        if (selected.isEmpty()) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "No visible drop matched loot target '" + safeTarget(context) + "'",
                    lootDetailsJson(context, null, false, "NO_MATCH", "No visible drop matched the requested target"));
        }

        AgentPerceptionSnapshot.AgentVisibleObject drop = selected.get();
        int dx = drop.x() - context.perception().x();
        int dy = drop.y() - context.perception().y();
        if (Math.abs(dx) > MAX_PICKUP_X_DISTANCE || Math.abs(dy) > MAX_PICKUP_Y_DISTANCE) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Drop " + drop.objectId() + " is visible but outside safe pickup bounds",
                    lootDetailsJson(context, drop, false, "APPROACH_DROP", "Move closer before pickup"));
        }
        if (Math.abs(dx) > PICKUP_READY_X_DISTANCE || Math.abs(dy) > PICKUP_READY_Y_DISTANCE) {
            AgentActionResult approach = approachDrop(context, drop);
            return new AgentActionResult(
                    approach.status(),
                    capability(),
                    approach.gameplayMutated()
                            ? "Moved toward drop " + dropLabel(drop)
                            : "Drop " + dropLabel(drop) + " is close enough for pickup",
                    approach.policyAllowed(),
                    approach.gameplayMutated(),
                    approach.dryRun(),
                    lootDetailsJson(context, drop, false, "APPROACHING_DROP", "Move closer before pickup", approach.detailsJson()),
                    approach.completedAt()
            );
        }

        MapleMap map = context.managed().character().getMap();
        MapObject object = map.getMapObject(drop.objectId());
        if (!(object instanceof MapItem mapItem)) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Drop " + drop.objectId() + " is no longer available on map " + map.getId(),
                    lootDetailsJson(context, drop, false, "STALE_DROP", "Map object is no longer a drop"));
        }
        if (mapItem.isPickedUp()) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Drop " + drop.objectId() + " was already picked up",
                    lootDetailsJson(context, drop, false, "STALE_DROP", "Drop is already picked up"));
        }

        context.managed().character().pickupItem(object);
        boolean pickedUp = mapItem.isPickedUp() || map.getMapObject(drop.objectId()) == null;
        if (!pickedUp) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Pickup attempted for drop " + drop.objectId() + " but the server rejected it",
                    lootDetailsJson(context, drop, false, "SERVER_REJECTED", "Normal pickup rules rejected the item"));
        }

        return AgentActionResult.ok(capability(),
                "Picked up " + dropLabel(drop) + " from map " + context.perception().mapId(),
                true,
                lootDetailsJson(context, drop, true, "PICKED_UP", "Normal pickup rules accepted the item"));
    }

    private AgentActionResult approachDrop(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject drop) {
        MapleMap map = context.managed().character().getMap();
        Point start = context.managed().character().getPosition();
        Point target = new Point(drop.x(), drop.y());
        Point step = boundedStep(start, target);
        Point grounded = map.getGroundBelow(step);
        Point destination = grounded == null ? step : grounded;
        long before = distanceSq(start, target);
        long after = distanceSq(destination, target);
        if (destination.equals(start)) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Loot approach is stuck at " + pointLabel(start),
                    movementDetailsJson(context, start, destination, target, "STUCK", grounded != null));
        }
        if (after >= before) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Loot approach could not reduce distance to " + dropLabel(drop),
                    movementDetailsJson(context, start, destination, target, "NO_PROGRESS", grounded != null));
        }

        map.movePlayer(context.managed().character(), destination);
        return AgentActionResult.ok(capability(),
                "Approached drop " + dropLabel(drop),
                true,
                movementDetailsJson(context, start, destination, target, "MOVED", grounded != null));
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

    private Optional<AgentPerceptionSnapshot.AgentVisibleObject> selectDrop(AgentActionContext context) {
        String target = safeTarget(context);
        return context.perception().nearbyDrops().stream()
                .filter(drop -> Boolean.TRUE.equals(drop.alive()))
                .filter(drop -> matchesTarget(drop, target))
                .min(Comparator.comparingLong(AgentPerceptionSnapshot.AgentVisibleObject::distanceSq));
    }

    private boolean matchesTarget(AgentPerceptionSnapshot.AgentVisibleObject drop, String target) {
        if (target.isBlank()
                || target.equalsIgnoreCase("nearest")
                || target.equalsIgnoreCase("nearest drop")
                || target.equalsIgnoreCase("drop")) {
            return true;
        }
        if (target.equalsIgnoreCase("meso") || target.equalsIgnoreCase("mesos")) {
            return drop.meso() != null && drop.meso() > 0;
        }
        if (drop.name() != null && drop.name().equalsIgnoreCase(target)) {
            return true;
        }
        Integer numeric = parseInt(target);
        if (numeric == null) {
            return false;
        }
        return drop.objectId() == numeric
                || (drop.templateId() != null && drop.templateId().equals(numeric))
                || (drop.meso() != null && drop.meso().equals(numeric));
    }

    private String safeTarget(AgentActionContext context) {
        String target = context.intent().argument();
        return target == null ? "" : target.trim();
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String lootDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject drop,
            boolean pickedUp,
            String state,
            String reason
    ) {
        return lootDetailsJson(context, drop, pickedUp, state, reason, null);
    }

    private String lootDetailsJson(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject drop,
            boolean pickedUp,
            String state,
            String reason,
            String movementJson
    ) {
        return "{"
                + "\"lootState\":\"" + state + "\","
                + "\"pickedUp\":" + pickedUp + ","
                + "\"reason\":\"" + escapeJson(reason) + "\","
                + "\"requestedTarget\":\"" + escapeJson(safeTarget(context)) + "\","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId() + ","
                + "\"agentPosition\":{\"x\":" + context.perception().x() + ",\"y\":" + context.perception().y() + "},"
                + "\"drop\":" + dropJson(context, drop) + ","
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
                + "\"action\":\"APPROACH_DROP\","
                + "\"world\":" + context.managed().client().getWorld() + ","
                + "\"channel\":" + context.managed().client().getChannel() + ","
                + "\"mapId\":" + context.managed().character().getMapId() + ","
                + "\"from\":{\"x\":" + from.x + ",\"y\":" + from.y + "},"
                + "\"to\":{\"x\":" + to.x + ",\"y\":" + to.y + "},"
                + "\"target\":{\"x\":" + target.x + ",\"y\":" + target.y + "},"
                + "\"stepLimit\":{\"x\":" + MAX_APPROACH_STEP_X + ",\"y\":" + MAX_APPROACH_STEP_Y + "},"
                + "\"pickupReady\":{\"x\":" + PICKUP_READY_X_DISTANCE + ",\"y\":" + PICKUP_READY_Y_DISTANCE + "},"
                + "\"grounded\":" + grounded + ","
                + "\"progressed\":" + (after < before) + ","
                + "\"distanceSqBefore\":" + before + ","
                + "\"distanceSqAfter\":" + after
                + "}";
    }

    private String dropJson(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject drop) {
        if (drop == null) {
            return "null";
        }
        int dx = drop.x() - context.perception().x();
        int dy = drop.y() - context.perception().y();
        return "{"
                + "\"objectId\":" + drop.objectId() + ","
                + "\"itemId\":" + nullableNumber(drop.templateId()) + ","
                + "\"name\":\"" + escapeJson(drop.name()) + "\","
                + "\"quantity\":" + nullableNumber(drop.quantity()) + ","
                + "\"meso\":" + nullableNumber(drop.meso()) + ","
                + "\"position\":{\"x\":" + drop.x() + ",\"y\":" + drop.y() + "},"
                + "\"delta\":{\"x\":" + dx + ",\"y\":" + dy + "},"
                + "\"distanceSq\":" + drop.distanceSq()
                + "}";
    }

    private String dropLabel(AgentPerceptionSnapshot.AgentVisibleObject drop) {
        if (drop.meso() != null && drop.meso() > 0) {
            return drop.meso() + " mesos";
        }
        if (drop.templateId() != null) {
            return "item " + drop.templateId();
        }
        return "drop " + drop.objectId();
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
