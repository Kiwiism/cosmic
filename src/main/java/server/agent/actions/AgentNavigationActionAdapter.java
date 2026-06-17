package server.agent.actions;

import server.agent.AgentCharacterLocationLookup;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentNavigationGraphService;
import server.agent.AgentNavigationRoute;
import server.agent.AgentPerceptionSnapshot;
import server.agent.AgentPortalEdge;

import java.util.Optional;

public final class AgentNavigationActionAdapter implements AgentActionAdapter {
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
        return AgentActionResult.blockedByRuntime(capability(),
                "Navigation preview found " + route.steps().size() + " loaded portal step(s); next "
                        + next.portalName() + " -> map " + next.toMapId()
                        + ". Gameplay movement remains disabled until the movement adapter is implemented.",
                routeDetailsJson(route, proposedPortalAction(route.steps().get(0))));
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
            String message = followLocationMessage(target, located.get(), route);
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    message,
                    followDetailsJson(context, target, null, located.get(), route, "TARGET_LOCATED", proposedFollowAction(context, located.get(), route))
            );
        }

        return AgentActionResult.ok(
                capability(),
                "Follow target " + matched.name() + " is visible at distanceSq " + matched.distanceSq()
                        + ". Gameplay movement remains disabled until the movement adapter is implemented.",
                false,
                followDetailsJson(context, target, matched, located.orElse(null), null, "TARGET_VISIBLE", proposedApproachAction(context, matched))
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
                + next.portalName() + " -> map " + next.toMapId()
                + ". Gameplay movement remains disabled until the movement adapter is implemented.";
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
                + "\"route\":" + (route == null ? "null" : routeDetailsJson(route, proposedActionJson))
                + "}";
    }

    private String proposedPortalAction(AgentPortalEdge next) {
        return "{"
                + "\"type\":\"USE_PORTAL\","
                + "\"executable\":false,"
                + "\"reason\":\"Movement execution is disabled; this is a dry-run proposal only\","
                + "\"portalName\":\"" + escapeJson(next.portalName()) + "\","
                + "\"fromMapId\":" + next.fromMapId() + ","
                + "\"toMapId\":" + next.toMapId() + ","
                + "\"position\":{\"x\":" + next.x() + ",\"y\":" + next.y() + "}"
                + "}";
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
                + "\"executable\":false,"
                + "\"reason\":\"Movement execution is disabled; this is a dry-run proposal only\","
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
