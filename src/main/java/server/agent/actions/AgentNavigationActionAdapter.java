package server.agent.actions;

import constants.id.MapId;
import client.Character;
import server.agent.AgentCharacterLocationLookup;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentNavigationGraphService;
import server.agent.AgentNavigationRoute;
import server.agent.AgentPerceptionSnapshot;
import server.agent.AgentPortalEdge;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.Comparator;
import java.util.Optional;

public final class AgentNavigationActionAdapter implements AgentActionAdapter {
    private static final int MAX_LOCAL_STEP_X = 220;
    private static final int MAX_LOCAL_STEP_Y = 140;
    private static final int ARRIVAL_DISTANCE_SQ = 80 * 80;

    private final AgentNavigationGraphService navigationGraphService;
    private final AgentCharacterLocationLookup characterLocationLookup;

    public AgentNavigationActionAdapter(
            AgentNavigationGraphService navigationGraphService,
            AgentCharacterLocationLookup characterLocationLookup
    ) {
        this.navigationGraphService = navigationGraphService;
        this.characterLocationLookup = characterLocationLookup;
    }

    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.NAVIGATION;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        if (type == AgentIntentType.MOVE_TO_MAP) {
            return previewMoveToMap(context);
        }
        if (type == AgentIntentType.FOLLOW_CHARACTER) {
            return previewFollowCharacter(context);
        }
        if (type == AgentIntentType.USE_PORTAL) {
            return executeNamedPortal(context);
        }
        if (type == AgentIntentType.MOVE) {
            return executeLocalMove(context);
        }
        if (type == AgentIntentType.ROAM) {
            return executeRoam(context);
        }
        return AgentActionResult.blockedByRuntime(capability(),
                type + " reached the navigation adapter, but movement execution is not implemented yet");
    }

    private AgentActionResult previewMoveToMap(AgentActionContext context) {
        Integer targetMapId = parseMapId(context.intent().argument());
        if (targetMapId == null) {
            return AgentActionResult.blockedByRuntime(capability(), "MOVE_TO_MAP requires a numeric map id");
        }
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot preview route without an available perception snapshot");
        }

        AgentNavigationRoute route = navigationGraphService.findLoadedRoute(
                context.perception().world(),
                context.perception().channel(),
                context.perception().mapId(),
                targetMapId
        );
        if (!route.found()) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Navigation preview blocked: " + route.message(),
                    routeDetailsJson(route));
        }
        if (route.steps().isEmpty()) {
            return AgentActionResult.ok(capability(), "Navigation target is already the current map", false, routeDetailsJson(route));
        }

        AgentPortalEdge next = route.steps().get(0);
        return executePortalStep(context, route, next, "MOVE_TO_MAP");
    }

    private AgentActionResult previewFollowCharacter(AgentActionContext context) {
        String target = context.intent().argument();
        if (target == null || target.isBlank()) {
            return AgentActionResult.blockedByRuntime(capability(), "FOLLOW_CHARACTER requires a character name or id");
        }
        if (context.perception() == null || !context.perception().available()) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot preview follow behavior without an available perception snapshot");
        }

        AgentPerceptionSnapshot.AgentVisibleObject matched = context.perception().nearbyPlayers().stream()
                .filter(player -> matchesCharacter(player, target))
                .findFirst()
                .orElse(null);
        Optional<AgentCharacterLocationLookup.LocatedCharacter> located = characterLocationLookup.find(target);
        if (matched == null) {
            if (located.isEmpty()) {
                return AgentActionResult.blockedByRuntime(
                        capability(),
                        "Follow target '" + target + "' is not visible and could not be found in online storage or character records.",
                    followDetailsJson(context, target, null, null, null, "TARGET_UNKNOWN", proposedLocateAction(target))
                );
            }

            AgentNavigationRoute route = routeToLocatedTarget(context, located.get());
            if (route != null && route.found() && !route.steps().isEmpty()) {
                return executePortalStep(context, route, route.steps().get(0), "FOLLOW_CHARACTER");
            }
            String message = followLocationMessage(target, located.get(), route);
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    message,
                    followDetailsJson(context, target, null, located.get(), route, "TARGET_LOCATED", proposedFollowAction(context, located.get(), route))
            );
        }

        AgentActionResult approach = moveTowardVisibleObject(context, matched, "FOLLOW_CHARACTER", matched.name());
        return new AgentActionResult(
                approach.status(),
                capability(),
                approach.gameplayMutated()
                        ? "Moved toward visible follow target " + matched.name()
                        : "Follow target " + matched.name() + " is already nearby",
                approach.policyAllowed(),
                approach.gameplayMutated(),
                approach.dryRun(),
                followDetailsJson(context, target, matched, located.orElse(null), null, "TARGET_VISIBLE",
                        proposedApproachAction(context, matched), approach.detailsJson()),
                approach.completedAt()
        );
    }

    private AgentActionResult executeLocalMove(AgentActionContext context) {
        Point target = parsePoint(context.intent().argument());
        if (target == null) {
            return AgentActionResult.blockedByRuntime(capability(), "MOVE requires coordinates like '100 200' or '100,200'");
        }
        return moveTowardPoint(context, target, "MOVE", "requested coordinates");
    }

    private AgentActionResult executeRoam(AgentActionContext context) {
        MapleMap currentMap = currentMap(context);
        if (currentMap == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot roam without an attached map");
        }

        Optional<Portal> nearestPortal = currentMap.getPortals().stream()
                .filter(this::isSafeTraversalPortal)
                .filter(portal -> portal.getPosition() != null)
                .min(Comparator.comparingLong(portal -> distanceSq(context.managed().character().getPosition(), portal.getPosition())));
        if (nearestPortal.isEmpty()) {
            return AgentActionResult.blockedByRuntime(capability(), "No safe traversal portal is available for roam movement");
        }

        Portal portal = nearestPortal.get();
        return executePortalWhenNearby(context, currentMap, portal, "ROAM", null);
    }

    private AgentActionResult executeNamedPortal(AgentActionContext context) {
        String portalName = context.intent().argument();
        if (portalName == null || portalName.isBlank()) {
            return AgentActionResult.blockedByRuntime(capability(), "USE_PORTAL requires a portal name");
        }
        MapleMap currentMap = currentMap(context);
        if (currentMap == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot use portal without an attached map");
        }
        Portal portal = currentMap.getPortal(portalName.trim());
        if (portal == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Portal '" + portalName + "' was not found on map " + currentMap.getId());
        }
        return executePortalWhenNearby(context, currentMap, portal, "USE_PORTAL", null);
    }

    private AgentActionResult executePortalStep(
            AgentActionContext context,
            AgentNavigationRoute route,
            AgentPortalEdge next,
            String reason
    ) {
        MapleMap currentMap = currentMap(context);
        if (currentMap == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot execute route without an attached map",
                    routeDetailsJson(route, proposedPortalAction(next, false, "Agent character is not attached to a map")));
        }
        if (currentMap.getId() != next.fromMapId()) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Route is stale: agent is on map " + currentMap.getId() + " but next portal starts from " + next.fromMapId(),
                    routeDetailsJson(route, proposedPortalAction(next, false, "Route is stale")));
        }
        Portal portal = currentMap.getPortal(next.portalName());
        if (portal == null) {
            return AgentActionResult.blockedByRuntime(capability(),
                    "Route portal '" + next.portalName() + "' no longer exists on map " + currentMap.getId(),
                    routeDetailsJson(route, proposedPortalAction(next, false, "Portal is missing")));
        }
        return executePortalWhenNearby(context, currentMap, portal, reason, route);
    }

    private AgentActionResult executePortalWhenNearby(
            AgentActionContext context,
            MapleMap currentMap,
            Portal portal,
            String reason,
            AgentNavigationRoute route
    ) {
        Point portalPosition = portal.getPosition();
        Character character = context.managed().character();
        if (portalPosition != null && character != null && distanceSq(character.getPosition(), portalPosition) > ARRIVAL_DISTANCE_SQ) {
            AgentActionResult approach = moveTowardPoint(context, portalPosition, "APPROACH_PORTAL", "portal " + portal.getName());
            String movementJson = approach.detailsJson();
            String details = route == null
                    ? portalDetailsJson(context, currentMap, portal, false, "Agent must approach the portal before entering", movementJson)
                    : routeDetailsJson(route, proposedPortalAction(portalEdge(currentMap, portal), false,
                    "Agent must approach the portal before entering"), movementJson);
            return new AgentActionResult(
                    approach.status(),
                    capability(),
                    approach.gameplayMutated()
                            ? reason + " moved toward portal " + portal.getName()
                            : reason + " is waiting near portal " + portal.getName(),
                    approach.policyAllowed(),
                    approach.gameplayMutated(),
                    approach.dryRun(),
                    details,
                    approach.completedAt()
            );
        }
        return executePortal(context, currentMap, portal, reason, route);
    }

    private AgentActionResult executePortal(
            AgentActionContext context,
            MapleMap currentMap,
            Portal portal,
            String reason,
            AgentNavigationRoute route
    ) {
        if (!isSafeTraversalPortal(portal)) {
            String details = route == null
                    ? portalDetailsJson(context, currentMap, portal, false, "Portal is closed, scripted, or not a normal map traversal portal")
                    : routeDetailsJson(route, proposedPortalAction(portalEdge(currentMap, portal), false,
                    "Portal is closed, scripted, or not a normal map traversal portal"));
            return AgentActionResult.blockedByRuntime(capability(),
                    "Portal '" + portal.getName() + "' is not safe for agent movement execution", details);
        }

        int beforeMapId = currentMap.getId();
        AgentPortalEdge edge = portalEdge(currentMap, portal);
        portal.enterPortal(context.managed().client());
        MapleMap afterMap = currentMap(context);
        int afterMapId = afterMap == null ? context.managed().character().getMapId() : afterMap.getId();
        boolean changed = afterMapId != beforeMapId;
        if (!changed) {
            String details = route == null
                    ? portalDetailsJson(context, currentMap, portal, false, "Portal execution returned without changing maps")
                    : routeDetailsJson(route, proposedPortalAction(edge, false, "Portal execution returned without changing maps"));
            return AgentActionResult.blockedByRuntime(capability(),
                    "Portal '" + portal.getName() + "' did not move the agent", details);
        }

        String actionJson = proposedPortalAction(edge, true, "Executed by agent navigation adapter");
        String details = route == null
                ? portalDetailsJson(context, currentMap, portal, true, "Executed by agent navigation adapter")
                : routeDetailsJson(route, actionJson);
        return AgentActionResult.ok(capability(),
                reason + " used portal " + portal.getName() + " from map " + beforeMapId + " to " + afterMapId,
                true,
                addResultMap(details, beforeMapId, afterMapId));
    }

    private MapleMap currentMap(AgentActionContext context) {
        return context.managed().character() == null ? null : context.managed().character().getMap();
    }

    private AgentActionResult moveTowardVisibleObject(
            AgentActionContext context,
            AgentPerceptionSnapshot.AgentVisibleObject target,
            String action,
            String targetLabel
    ) {
        return moveTowardPoint(context, new Point(target.x(), target.y()), action, targetLabel);
    }

    private AgentActionResult moveTowardPoint(AgentActionContext context, Point target, String action, String targetLabel) {
        MapleMap currentMap = currentMap(context);
        Character character = context.managed().character();
        if (currentMap == null || character == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot move without an attached map");
        }

        Point start = character.getPosition();
        long distanceSq = distanceSq(start, target);
        if (distanceSq <= ARRIVAL_DISTANCE_SQ) {
            return AgentActionResult.ok(capability(),
                    action + " target is already nearby: " + targetLabel,
                    false,
                    localMoveDetailsJson(context, start, start, target, action, targetLabel, "ALREADY_NEARBY"));
        }

        Point next = boundedStep(start, target);
        Point grounded = currentMap.getGroundBelow(next);
        Point destination = grounded == null ? next : grounded;
        long distanceSqAfter = distanceSq(destination, target);
        if (destination.equals(start)) {
            return AgentActionResult.blockedByRuntime(capability(),
                    action + " could not move from " + pointLabel(start) + " toward " + targetLabel
                            + "; the bounded step resolved to the current position.",
                    localMoveDetailsJson(context, start, destination, target, action, targetLabel, "STUCK", grounded != null));
        }
        if (distanceSqAfter >= distanceSq) {
            return AgentActionResult.blockedByRuntime(capability(),
                    action + " could not make progress from " + pointLabel(start) + " toward " + targetLabel
                            + "; ground snapping would increase or preserve the remaining distance.",
                    localMoveDetailsJson(context, start, destination, target, action, targetLabel, "NO_PROGRESS", grounded != null));
        }
        currentMap.movePlayer(character, destination);
        return AgentActionResult.ok(capability(),
                action + " moved from " + pointLabel(start) + " toward " + targetLabel + " at " + pointLabel(target),
                true,
                localMoveDetailsJson(context, start, destination, target, action, targetLabel, "MOVED", grounded != null));
    }

    private Point boundedStep(Point start, Point target) {
        int dx = clamp(target.x - start.x, -MAX_LOCAL_STEP_X, MAX_LOCAL_STEP_X);
        int dy = clamp(target.y - start.y, -MAX_LOCAL_STEP_Y, MAX_LOCAL_STEP_Y);
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

    private Point parsePoint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.trim().split("[,\\s]+");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new Point(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isSafeTraversalPortal(Portal portal) {
        return portal.getPortalStatus()
                && (portal.getType() == Portal.MAP_PORTAL || portal.getType() == Portal.TELEPORT_PORTAL)
                && portal.getTargetMapId() != MapId.NONE
                && portal.getTargetMapId() >= 0
                && (portal.getScriptName() == null || portal.getScriptName().isBlank());
    }

    private AgentPortalEdge portalEdge(MapleMap currentMap, Portal portal) {
        Point position = portal.getPosition();
        return new AgentPortalEdge(
                0,
                0,
                currentMap.getId(),
                currentMap.getMapName(),
                portal.getId(),
                portal.getName(),
                portal.getType(),
                position == null ? 0 : position.x,
                position == null ? 0 : position.y,
                portal.getTargetMapId(),
                portal.getTarget(),
                portal.getPortalStatus(),
                portal.getScriptName() != null && !portal.getScriptName().isBlank()
        );
    }

    private AgentNavigationRoute routeToLocatedTarget(
            AgentActionContext context,
            AgentCharacterLocationLookup.LocatedCharacter located
    ) {
        if (located.world() != context.perception().world() || located.channel() != context.perception().channel()) {
            return null;
        }
        return navigationGraphService.findLoadedRoute(
                context.perception().world(),
                context.perception().channel(),
                context.perception().mapId(),
                located.mapId()
        );
    }

    private String followLocationMessage(
            String target,
            AgentCharacterLocationLookup.LocatedCharacter located,
            AgentNavigationRoute route
    ) {
        if (route == null) {
            return "Follow target '" + target + "' located at world " + located.world()
                    + ", channel " + located.channel() + ", map " + located.mapId()
                    + ". Cross-world/channel routing is not implemented yet.";
        }
        if (!route.found()) {
            return "Follow target '" + target + "' located at map " + located.mapId()
                    + ", but route preview is unavailable: " + route.message();
        }
        if (route.steps().isEmpty()) {
            return "Follow target '" + target + "' is on this map but not visible in the nearby snapshot.";
        }
        AgentPortalEdge next = route.steps().get(0);
        return "Follow target '" + target + "' located at map " + located.mapId()
                + ". Route preview has " + route.steps().size() + " loaded step(s); next "
                + next.portalName() + " -> map " + next.toMapId() + ".";
    }

    private boolean matchesCharacter(AgentPerceptionSnapshot.AgentVisibleObject player, String target) {
        String trimmed = target.trim();
        if (player.name() != null && player.name().equalsIgnoreCase(trimmed)) {
            return true;
        }
        if (player.templateId() == null) {
            return false;
        }
        try {
            return player.templateId() == Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private Integer parseMapId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String routeDetailsJson(AgentNavigationRoute route) {
        return routeDetailsJson(route, "null");
    }

    private String routeDetailsJson(AgentNavigationRoute route, String proposedActionJson) {
        return routeDetailsJson(route, proposedActionJson, null);
    }

    private String routeDetailsJson(AgentNavigationRoute route, String proposedActionJson, String movementJson) {
        StringBuilder builder = new StringBuilder("{");
        builder.append("\"routeState\":\"").append(route.found() ? "READY" : "UNAVAILABLE").append("\",")
                .append("\"world\":").append(route.world()).append(',')
                .append("\"channel\":").append(route.channel()).append(',')
                .append("\"fromMapId\":").append(route.fromMapId()).append(',')
                .append("\"toMapId\":").append(route.toMapId()).append(',')
                .append("\"found\":").append(route.found()).append(',')
                .append("\"message\":\"").append(escapeJson(route.message())).append("\",")
                .append("\"stepCount\":").append(route.steps().size()).append(',')
                .append("\"nextStep\":").append(route.steps().isEmpty() ? "null" : edgeJson(route.steps().get(0))).append(',')
                .append("\"proposedAction\":").append(proposedActionJson).append(',')
                .append("\"movement\":").append(movementJson == null ? "null" : movementJson).append(',')
                .append("\"steps\":[");
        for (int i = 0; i < route.steps().size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(edgeJson(route.steps().get(i)));
        }
        return builder.append("]}").toString();
    }

    private String edgeJson(AgentPortalEdge edge) {
        return "{"
                + "\"fromMapId\":" + edge.fromMapId() + ","
                + "\"fromMapName\":\"" + escapeJson(edge.fromMapName()) + "\","
                + "\"portalId\":" + edge.portalId() + ","
                + "\"portalName\":\"" + escapeJson(edge.portalName()) + "\","
                + "\"portalType\":" + edge.portalType() + ","
                + "\"position\":{\"x\":" + edge.x() + ",\"y\":" + edge.y() + "},"
                + "\"toMapId\":" + edge.toMapId() + ","
                + "\"targetPortalName\":\"" + escapeJson(edge.targetPortalName()) + "\","
                + "\"open\":" + edge.open() + ","
                + "\"scripted\":" + edge.scripted()
                + "}";
    }

    private String followDetailsJson(
            AgentActionContext context,
            String requestedTarget,
            AgentPerceptionSnapshot.AgentVisibleObject matched,
            AgentCharacterLocationLookup.LocatedCharacter located,
            AgentNavigationRoute route,
            String state,
            String proposedActionJson
    ) {
        return followDetailsJson(context, requestedTarget, matched, located, route, state, proposedActionJson, null);
    }

    private String followDetailsJson(
            AgentActionContext context,
            String requestedTarget,
            AgentPerceptionSnapshot.AgentVisibleObject matched,
            AgentCharacterLocationLookup.LocatedCharacter located,
            AgentNavigationRoute route,
            String state,
            String proposedActionJson,
            String movementJson
    ) {
        return "{"
                + "\"followState\":\"" + state + "\","
                + "\"world\":" + context.perception().world() + ","
                + "\"channel\":" + context.perception().channel() + ","
                + "\"mapId\":" + context.perception().mapId() + ","
                + "\"agentPosition\":{\"x\":" + context.perception().x() + ",\"y\":" + context.perception().y() + "},"
                + "\"requestedTarget\":\"" + escapeJson(requestedTarget) + "\","
                + "\"visiblePlayers\":" + context.perception().nearbyPlayers().size() + ","
                + "\"target\":" + (matched == null ? "null" : visiblePlayerJson(matched)) + ","
                + "\"locatedTarget\":" + (located == null ? "null" : locatedJson(located)) + ","
                + "\"proposedAction\":" + proposedActionJson + ","
                + "\"movement\":" + (movementJson == null ? "null" : movementJson) + ","
                + "\"route\":" + (route == null ? "null" : routeDetailsJson(route, proposedActionJson))
                + "}";
    }

    private String proposedPortalAction(AgentPortalEdge next) {
        return proposedPortalAction(next, false, "Movement execution is disabled for this proposed action");
    }

    private String proposedPortalAction(AgentPortalEdge next, boolean executable, String reason) {
        return "{"
                + "\"type\":\"USE_PORTAL\","
                + "\"executable\":" + executable + ","
                + "\"reason\":\"" + escapeJson(reason) + "\","
                + "\"portalName\":\"" + escapeJson(next.portalName()) + "\","
                + "\"fromMapId\":" + next.fromMapId() + ","
                + "\"toMapId\":" + next.toMapId() + ","
                + "\"position\":{\"x\":" + next.x() + ",\"y\":" + next.y() + "}"
                + "}";
    }

    private String portalDetailsJson(AgentActionContext context, MapleMap currentMap, Portal portal, boolean executable, String reason) {
        return portalDetailsJson(context, currentMap, portal, executable, reason, null);
    }

    private String portalDetailsJson(
            AgentActionContext context,
            MapleMap currentMap,
            Portal portal,
            boolean executable,
            String reason,
            String movementJson
    ) {
        return "{"
                + "\"routeState\":\"DIRECT_PORTAL\","
                + "\"world\":" + context.managed().client().getWorld() + ","
                + "\"channel\":" + context.managed().client().getChannel() + ","
                + "\"fromMapId\":" + currentMap.getId() + ","
                + "\"toMapId\":" + portal.getTargetMapId() + ","
                + "\"found\":true,"
                + "\"message\":\"Direct portal execution\","
                + "\"stepCount\":1,"
                + "\"nextStep\":" + edgeJson(portalEdge(currentMap, portal)) + ","
                + "\"proposedAction\":" + proposedPortalAction(portalEdge(currentMap, portal), executable, reason) + ","
                + "\"movement\":" + (movementJson == null ? "null" : movementJson) + ","
                + "\"steps\":[" + edgeJson(portalEdge(currentMap, portal)) + "]"
                + "}";
    }

    private String localMoveDetailsJson(
            AgentActionContext context,
            Point from,
            Point to,
            Point target,
            String action,
            String targetLabel,
            String state
    ) {
        return localMoveDetailsJson(context, from, to, target, action, targetLabel, state, false);
    }

    private String localMoveDetailsJson(
            AgentActionContext context,
            Point from,
            Point to,
            Point target,
            String action,
            String targetLabel,
            String state,
            boolean grounded
    ) {
        long before = distanceSq(from, target);
        long after = distanceSq(to, target);
        return "{"
                + "\"movementState\":\"" + state + "\","
                + "\"action\":\"" + escapeJson(action) + "\","
                + "\"targetLabel\":\"" + escapeJson(targetLabel) + "\","
                + "\"world\":" + context.managed().client().getWorld() + ","
                + "\"channel\":" + context.managed().client().getChannel() + ","
                + "\"mapId\":" + context.managed().character().getMapId() + ","
                + "\"from\":{\"x\":" + from.x + ",\"y\":" + from.y + "},"
                + "\"to\":{\"x\":" + to.x + ",\"y\":" + to.y + "},"
                + "\"target\":{\"x\":" + target.x + ",\"y\":" + target.y + "},"
                + "\"stepLimit\":{\"x\":" + MAX_LOCAL_STEP_X + ",\"y\":" + MAX_LOCAL_STEP_Y + "},"
                + "\"grounded\":" + grounded + ","
                + "\"progressed\":" + (after < before) + ","
                + "\"distanceSqBefore\":" + before + ","
                + "\"distanceSqAfter\":" + after
                + "}";
    }

    private String pointLabel(Point point) {
        return "(" + point.x + "," + point.y + ")";
    }

    private String addResultMap(String json, int beforeMapId, int afterMapId) {
        if (json == null || json.length() < 2 || !json.endsWith("}")) {
            return json;
        }
        return json.substring(0, json.length() - 1)
                + ",\"result\":{\"beforeMapId\":" + beforeMapId + ",\"afterMapId\":" + afterMapId + "}}";
    }

    private String proposedLocateAction(String target) {
        return "{"
                + "\"type\":\"LOCATE_TARGET\","
                + "\"executable\":false,"
                + "\"reason\":\"Target is not known yet; locator must find an online or saved character location\","
                + "\"target\":\"" + escapeJson(target) + "\""
                + "}";
    }

    private String proposedFollowAction(
            AgentActionContext context,
            AgentCharacterLocationLookup.LocatedCharacter located,
            AgentNavigationRoute route
    ) {
        if (route == null) {
            return "{"
                    + "\"type\":\"CHANGE_CONTEXT\","
                    + "\"executable\":false,"
                    + "\"reason\":\"Target is outside the current world or channel\","
                    + "\"world\":" + located.world() + ","
                    + "\"channel\":" + located.channel() + ","
                    + "\"mapId\":" + located.mapId()
                    + "}";
        }
        if (!route.found()) {
            return "{"
                    + "\"type\":\"ROUTE_UNAVAILABLE\","
                    + "\"executable\":false,"
                    + "\"reason\":\"" + escapeJson(route.message()) + "\","
                    + "\"targetMapId\":" + located.mapId()
                    + "}";
        }
        if (route.steps().isEmpty()) {
            return "{"
                    + "\"type\":\"SCAN_MAP_FOR_TARGET\","
                    + "\"executable\":false,"
                    + "\"reason\":\"Target is on the current map but not in nearby perception\","
                    + "\"mapId\":" + context.perception().mapId()
                    + "}";
        }
        return proposedPortalAction(route.steps().get(0));
    }

    private String proposedApproachAction(AgentActionContext context, AgentPerceptionSnapshot.AgentVisibleObject matched) {
        int dx = matched.x() - context.perception().x();
        int dy = matched.y() - context.perception().y();
        return "{"
                + "\"type\":\"APPROACH_TARGET\","
                + "\"executable\":true,"
                + "\"reason\":\"Bounded in-map approach movement is available\","
                + "\"targetCharacterId\":" + matched.templateId() + ","
                + "\"targetName\":\"" + escapeJson(matched.name()) + "\","
                + "\"delta\":{\"x\":" + dx + ",\"y\":" + dy + "},"
                + "\"targetPosition\":{\"x\":" + matched.x() + ",\"y\":" + matched.y() + "},"
                + "\"distanceSq\":" + matched.distanceSq()
                + "}";
    }

    private String visiblePlayerJson(AgentPerceptionSnapshot.AgentVisibleObject player) {
        return "{"
                + "\"characterId\":" + player.templateId() + ","
                + "\"objectId\":" + player.objectId() + ","
                + "\"name\":\"" + escapeJson(player.name()) + "\","
                + "\"level\":" + nullableNumber(player.level()) + ","
                + "\"position\":{\"x\":" + player.x() + ",\"y\":" + player.y() + "},"
                + "\"distanceSq\":" + player.distanceSq()
                + "}";
    }

    private String locatedJson(AgentCharacterLocationLookup.LocatedCharacter located) {
        return "{"
                + "\"characterId\":" + located.characterId() + ","
                + "\"name\":\"" + escapeJson(located.name()) + "\","
                + "\"world\":" + located.world() + ","
                + "\"channel\":" + located.channel() + ","
                + "\"mapId\":" + located.mapId() + ","
                + "\"position\":{\"x\":" + located.x() + ",\"y\":" + located.y() + "},"
                + "\"online\":" + located.online()
                + "}";
    }

    private String nullableNumber(Integer value) {
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
