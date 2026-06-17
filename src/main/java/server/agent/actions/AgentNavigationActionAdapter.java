package server.agent.actions;

import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;
import server.agent.AgentNavigationGraphService;
import server.agent.AgentNavigationRoute;
import server.agent.AgentPortalEdge;

public final class AgentNavigationActionAdapter implements AgentActionAdapter {
    private final AgentNavigationGraphService navigationGraphService;

    public AgentNavigationActionAdapter(AgentNavigationGraphService navigationGraphService) {
        this.navigationGraphService = navigationGraphService;
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
            return AgentActionResult.blockedByRuntime(capability(), "Navigation preview blocked: " + route.message());
        }
        if (route.steps().isEmpty()) {
            return AgentActionResult.ok(capability(), "Navigation target is already the current map", false);
        }

        AgentPortalEdge next = route.steps().get(0);
        return AgentActionResult.blockedByRuntime(capability(),
                "Navigation preview found " + route.steps().size() + " loaded portal step(s); next "
                        + next.portalName() + " -> map " + next.toMapId()
                        + ". Gameplay movement remains disabled until the movement adapter is implemented.");
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
}
