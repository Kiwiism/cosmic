package com.cosmic.agentclient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.awt.Point;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class AgentNavigationExecutionSystem {
    private static final int WALK_STEP_PIXELS = 12;
    private static final int WALK_DURATION_MILLIS = 160;
    private static final int JUMP_DURATION_MILLIS = 260;
    private static final int DROP_DURATION_MILLIS = 220;
    private static final int CLIMB_DURATION_MILLIS = 220;
    private static final int ARRIVAL_DISTANCE_PIXELS = 18;
    private static final int LOCAL_TARGET_DISTANCE_PIXELS = 35;
    private static final int LOCAL_TARGET_VERTICAL_PIXELS = 45;
    private static final int MAX_DIRECT_VERTICAL_GAP_PIXELS = 42;
    private static final int EDGE_STEP_PIXELS = 18;
    private static final int ROUTE_MAX_DEPTH = 24;
    private static final int ROUTE_MAX_MAPS = 512;
    private static final int INVALID_TARGET_MAP = 999999999;
    private static final int STANCE_STAND = 4;
    private static final int STANCE_WALK_RIGHT = 2;
    private static final int STANCE_WALK_LEFT = 3;
    private static final int STANCE_JUMP = 6;
    private static final int STANCE_DROP = 10;
    private static final int STANCE_CLIMB = 16;

    private final JdbcTemplate cmsJdbc;
    private final AgentPhysicsSystem physicsSystem;
    private final AgentMapGeometryRepository geometryRepository;
    private final AgentNavigationGraphBuilder graphBuilder;

    public AgentNavigationExecutionSystem(JdbcTemplate cmsJdbc, AgentPhysicsSystem physicsSystem,
                                          AgentMapGeometryRepository geometryRepository,
                                          AgentNavigationGraphBuilder graphBuilder) {
        this.cmsJdbc = cmsJdbc;
        this.physicsSystem = physicsSystem;
        this.geometryRepository = geometryRepository;
        this.graphBuilder = graphBuilder;
    }

    NavigationStep nextStep(NavigationContext context) {
        GroundState ground = resolveGround(context);
        boolean hasMapTarget = context.targetMapId() != null && context.targetMapId() != context.mapId();
        Optional<RoutePlan> routePlan = hasMapTarget
                ? routeToTargetMap(context.mapId(), context.targetMapId())
                : Optional.empty();
        if (hasMapTarget && routePlan.flatMap(RoutePlan::nextPortal).isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("targetMapId", context.targetMapId());
            detail.put("currentMapId", context.mapId());
            detail.put("route", routePlan.map(RoutePlan::describe).orElse(null));
            detail.put("source", "cosmic_agent_cms.agent_map_portals");
            detail.put("next", "Import/expand map catalog links or add scripted travel support for this route.");
            return new NavigationStep(context.x(), ground.y(), ground.footholdId(), STANCE_STAND,
                    WALK_DURATION_MILLIS, context.direction(), "ROUTE_BLOCKED",
                    "No route from " + context.mapId() + " to " + context.targetMapId(), detail);
        }
        if (!hasMapTarget && context.localTarget().isPresent()) {
            return stepTowardLocalTarget(context, ground, context.localTarget().get());
        }
        Optional<PortalTarget> portalTarget = hasMapTarget
                ? routePlan.flatMap(RoutePlan::nextPortal)
                : nearestUsablePortal(context);
        if (portalTarget.isPresent()) {
            return stepTowardPortal(context, ground, portalTarget.get(), routePlan);
        }

        AgentPhysicsSystem.MovementStep fallback = physicsSystem.nextBoundedProbe(
                context.x(),
                ground.y(),
                context.originX() == null ? context.x() : context.originX(),
                ground.footholdId(),
                context.direction()
        );
        return NavigationStep.fromPhysics(fallback, "LOCAL_IDLE_PATROL", "No usable local portal target in Agent CMS map catalog",
                Map.of("catalog", "agent_map_portals", "mapId", context.mapId()));
    }

    private GroundState resolveGround(NavigationContext context) {
        Optional<Foothold> byObserved = context.foothold() > 0 ? foothold(context.mapId(), context.foothold()) : Optional.empty();
        Optional<Foothold> nearest = byObserved
                .filter(foothold -> foothold.spans(context.x()))
                .or(() -> nearestFoothold(context.mapId(), context.x(), context.y()));
        if (nearest.isEmpty()) {
            return new GroundState(context.foothold(), context.y(), false, Map.of("source", "observed-or-fallback"));
        }
        Foothold foothold = nearest.get();
        int projectedY = foothold.yAt(context.x());
        Map<String, Object> detail = foothold.describe();
        detail.put("projectedY", projectedY);
        return new GroundState(foothold.id(), projectedY, true, detail);
    }

    private Optional<Foothold> foothold(int mapId, int footholdId) {
        List<Foothold> footholds = cmsJdbc.query("""
                        SELECT foothold_id, x1, y1, x2, y2, prev_foothold_id, next_foothold_id, forbid_fall_down
                        FROM agent_map_footholds
                        WHERE map_id = ? AND foothold_id = ?
                        """,
                (rs, rowNum) -> new Foothold(
                        rs.getInt("foothold_id"),
                        rs.getInt("x1"),
                        rs.getInt("y1"),
                        rs.getInt("x2"),
                        rs.getInt("y2"),
                        rs.getInt("prev_foothold_id"),
                        rs.getInt("next_foothold_id"),
                        rs.getInt("forbid_fall_down") != 0
                ),
                mapId,
                footholdId
        );
        return footholds.stream().findFirst();
    }

    private Optional<Foothold> nearestFoothold(int mapId, int x, int y) {
        List<Foothold> footholds = cmsJdbc.query("""
                        SELECT foothold_id, x1, y1, x2, y2, prev_foothold_id, next_foothold_id, forbid_fall_down
                        FROM agent_map_footholds
                        WHERE map_id = ?
                          AND LEAST(x1, x2) - 16 <= ?
                          AND GREATEST(x1, x2) + 16 >= ?
                          AND x1 <> x2
                        ORDER BY ABS(((y1 + y2) / 2) - ?), ABS(((x1 + x2) / 2) - ?)
                        LIMIT 8
                        """,
                (rs, rowNum) -> new Foothold(
                        rs.getInt("foothold_id"),
                        rs.getInt("x1"),
                        rs.getInt("y1"),
                        rs.getInt("x2"),
                        rs.getInt("y2"),
                        rs.getInt("prev_foothold_id"),
                        rs.getInt("next_foothold_id"),
                        rs.getInt("forbid_fall_down") != 0
                ),
                mapId,
                x,
                x,
                y,
                x
        );
        return footholds.stream()
                .min((left, right) -> Integer.compare(Math.abs(left.yAt(x) - y), Math.abs(right.yAt(x) - y)));
    }

    private Optional<PortalTarget> nearestUsablePortal(NavigationContext context) {
        List<PortalTarget> portals = portalTargetsForMap(context.mapId()).stream()
                .sorted((left, right) -> Integer.compare(
                        Math.abs(left.x() - context.x()) + Math.abs(left.y() - context.y()),
                        Math.abs(right.x() - context.x()) + Math.abs(right.y() - context.y())))
                .limit(24)
                .toList();
        return portals.stream()
                .filter(portal -> !isSpawnOnlyPortal(portal))
                .filter(portal -> Math.abs(portal.y() - context.y()) <= MAX_DIRECT_VERTICAL_GAP_PIXELS)
                .filter(portal -> Math.abs(portal.x() - context.x()) > ARRIVAL_DISTANCE_PIXELS)
                .findFirst();
    }

    private List<PortalTarget> portalTargetsForMap(int mapId) {
        return cmsJdbc.query("""
                        SELECT map_id, portal_index, portal_name, portal_type, target_map_id, target_portal_name, x, y, source_path
                        FROM agent_map_portals
                        WHERE map_id = ?
                        ORDER BY portal_index
                        LIMIT 160
                        """,
                (rs, rowNum) -> new PortalTarget(
                        rs.getInt("map_id"),
                        rs.getInt("portal_index"),
                        rs.getString("portal_name"),
                        rs.getInt("portal_type"),
                        rs.getInt("target_map_id"),
                        rs.getString("target_portal_name"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getString("source_path")
                ),
                mapId
        );
    }

    private Optional<RoutePlan> routeToTargetMap(int startMapId, int targetMapId) {
        if (startMapId == targetMapId) {
            return Optional.of(new RoutePlan(List.of(), 0));
        }
        ArrayDeque<RouteNode> queue = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(new RouteNode(startMapId, List.of()));
        visited.add(startMapId);
        int explored = 0;
        while (!queue.isEmpty() && explored < ROUTE_MAX_MAPS) {
            RouteNode node = queue.removeFirst();
            explored++;
            if (node.path().size() >= ROUTE_MAX_DEPTH) {
                continue;
            }
            for (PortalTarget portal : portalTargetsForMap(node.mapId())) {
                if (!isTravelPortal(portal)) {
                    continue;
                }
                List<PortalTarget> nextPath = new ArrayList<>(node.path());
                nextPath.add(portal);
                if (portal.targetMapId() == targetMapId) {
                    return Optional.of(new RoutePlan(List.copyOf(nextPath), explored));
                }
                if (visited.add(portal.targetMapId())) {
                    queue.addLast(new RouteNode(portal.targetMapId(), List.copyOf(nextPath)));
                }
            }
        }
        return Optional.of(new RoutePlan(List.of(), explored));
    }

    private boolean isSpawnOnlyPortal(PortalTarget portal) {
        String name = portal.name() == null ? "" : portal.name().toLowerCase();
        return name.startsWith("sp") || portal.type() == 0 && portal.targetMapId() == INVALID_TARGET_MAP;
    }

    private boolean isTravelPortal(PortalTarget portal) {
        return !isSpawnOnlyPortal(portal) && portal.targetMapId() != INVALID_TARGET_MAP
                && portal.targetMapId() != portal.mapId();
    }

    private NavigationStep stepTowardPortal(NavigationContext context, GroundState ground, PortalTarget portal,
                                            Optional<RoutePlan> routePlan) {
        Optional<GraphPath> graphPath = routeToPortalRegion(context.mapId(), ground.footholdId(), portal.index());
        if (graphPath.isPresent() && !graphPath.get().edges().isEmpty()) {
            return stepAlongGraphPath(context, ground, portal, routePlan, graphPath.get());
        }

        int dx = portal.x() - context.x();
        Optional<Foothold> targetGround = nearestFoothold(context.mapId(), portal.x(), portal.y());
        if (targetGround.isPresent() && ground.grounded()) {
            Foothold foothold = targetGround.get();
            int targetY = foothold.yAt(portal.x());
            int verticalGap = Math.abs(targetY - ground.y());
            if (verticalGap > MAX_DIRECT_VERTICAL_GAP_PIXELS || !foothold.connectedTo(ground.footholdId())) {
                Map<String, Object> detail = portal.describe();
                detail.put("currentGround", ground.detail());
                detail.put("targetGround", foothold.describe());
                detail.put("route", routePlan.map(RoutePlan::describe).orElse(null));
                detail.put("verticalGap", verticalGap);
                return stepAcrossEdge(context, ground, foothold, portal, detail);
            }
        }
        if (Math.abs(dx) <= ARRIVAL_DISTANCE_PIXELS) {
            Map<String, Object> detail = portal.describe();
            detail.put("distanceX", dx);
            detail.put("distanceY", portal.y() - ground.y());
            detail.put("currentGround", ground.detail());
            detail.put("route", routePlan.map(RoutePlan::describe).orElse(null));
            detail.put("next", "Virtual client should send the portal enter packet after this approach step.");
            return new NavigationStep(context.x(), ground.y(), ground.footholdId(), STANCE_STAND,
                    WALK_DURATION_MILLIS, context.direction(), "AT_PORTAL_TARGET",
                    portal.label(), detail);
        }

        int direction = dx > 0 ? 1 : -1;
        int nextX = context.x() + (direction * Math.min(WALK_STEP_PIXELS, Math.abs(dx)));
        int stance = direction > 0 ? STANCE_WALK_RIGHT : STANCE_WALK_LEFT;
        GroundState nextGround = nearestFoothold(context.mapId(), nextX, ground.y())
                .map(foothold -> new GroundState(foothold.id(), foothold.yAt(nextX), true, foothold.describe()))
                .orElse(ground);
        Map<String, Object> detail = portal.describe();
        detail.put("distanceX", dx);
        detail.put("distanceY", portal.y() - ground.y());
        detail.put("currentGround", ground.detail());
        detail.put("nextGround", nextGround.detail());
        detail.put("route", routePlan.map(RoutePlan::describe).orElse(null));
        detail.put("source", "cosmic_agent_cms.agent_map_portals");
        detail.put("calibration", "WALK stance/duration candidate; confirm in live v83 client.");
        return new NavigationStep(nextX, nextGround.y(), nextGround.footholdId(), stance,
                WALK_DURATION_MILLIS, direction, "FOOTHOLD_PORTAL_APPROACH", portal.label(), detail);
    }

    private NavigationStep stepTowardLocalTarget(NavigationContext context, GroundState ground, LocalTarget target) {
        Optional<Foothold> targetGround = nearestFoothold(context.mapId(), target.x(), target.y());
        if (targetGround.isEmpty()) {
            Map<String, Object> detail = target.describe();
            detail.put("currentGround", ground.detail());
            detail.put("source", "cosmic_agent_cms.agent_map_footholds");
            detail.put("next", "Import foothold geometry or choose a target with a nearby foothold.");
            return new NavigationStep(context.x(), ground.y(), ground.footholdId(), STANCE_STAND,
                    WALK_DURATION_MILLIS, context.direction(), "LOCAL_TARGET_BLOCKED",
                    "No foothold found near " + target.label(), detail);
        }

        Foothold foothold = targetGround.get();
        int targetY = foothold.yAt(target.x());
        int dx = target.x() - context.x();
        int dy = targetY - ground.y();
        if (Math.abs(dx) <= LOCAL_TARGET_DISTANCE_PIXELS && Math.abs(dy) <= LOCAL_TARGET_VERTICAL_PIXELS) {
            Map<String, Object> detail = target.describe();
            detail.put("currentGround", ground.detail());
            detail.put("targetGround", foothold.describe());
            detail.put("projectedTargetY", targetY);
            return new NavigationStep(context.x(), ground.y(), ground.footholdId(), STANCE_STAND,
                    WALK_DURATION_MILLIS, context.direction(), "AT_LOCAL_TARGET",
                    target.label(), detail);
        }

        if (ground.grounded() && foothold.connectedTo(ground.footholdId())) {
            return stepDirectlyTowardLocalTarget(context, ground, foothold, target, targetY);
        }

        Optional<GraphPath> graphPath = routeToFootholdRegion(context.mapId(), ground.footholdId(), foothold.id());
        PortalTarget pseudoTarget = PortalTarget.localTarget(context.mapId(), target, targetY);
        if (graphPath.isPresent() && !graphPath.get().edges().isEmpty()) {
            return stepAlongGraphPath(context, ground, pseudoTarget, Optional.empty(), graphPath.get());
        }

        Map<String, Object> detail = target.describe();
        detail.put("currentGround", ground.detail());
        detail.put("targetGround", foothold.describe());
        detail.put("projectedTargetY", targetY);
        detail.put("next", "No graph route found; falling back to nearest ladder/rope/jump/drop edge.");
        return stepAcrossEdge(context, ground, foothold, pseudoTarget, detail);
    }

    private NavigationStep stepDirectlyTowardLocalTarget(NavigationContext context, GroundState ground,
                                                        Foothold targetGround, LocalTarget target, int targetY) {
        int dx = target.x() - context.x();
        int direction = dx > 0 ? 1 : -1;
        int nextX = context.x() + (direction * Math.min(WALK_STEP_PIXELS, Math.abs(dx)));
        int nextY = targetGround.spans(nextX) ? targetGround.yAt(nextX) : ground.y();
        Map<String, Object> detail = target.describe();
        detail.put("currentGround", ground.detail());
        detail.put("targetGround", targetGround.describe());
        detail.put("distanceX", dx);
        detail.put("distanceY", targetY - ground.y());
        detail.put("projectedTargetY", targetY);
        detail.put("source", "cosmic_agent_cms.agent_map_footholds");
        return new NavigationStep(nextX, nextY, targetGround.id(),
                direction > 0 ? STANCE_WALK_RIGHT : STANCE_WALK_LEFT, WALK_DURATION_MILLIS,
                direction, "FOOTHOLD_LOCAL_TARGET_APPROACH", target.label(), detail);
    }

    private Optional<GraphPath> routeToPortalRegion(int mapId, int footholdId, int portalIndex) {
        if (footholdId <= 0) {
            return Optional.empty();
        }
        AgentNavigationGraph graph = graphBuilder.build(geometryRepository.load(mapId));
        Optional<AgentNavigationGraph.Region> startRegion = graph.regions().stream()
                .filter(region -> region.type() == AgentNavigationGraph.RegionType.FOOTHOLD)
                .filter(region -> region.sourceId() != null && region.sourceId() == footholdId)
                .findFirst();
        Optional<AgentNavigationGraph.Region> portalRegion = graph.regions().stream()
                .filter(region -> region.type() == AgentNavigationGraph.RegionType.PORTAL)
                .filter(region -> region.sourceId() != null && region.sourceId() == portalIndex)
                .findFirst();
        if (startRegion.isEmpty() || portalRegion.isEmpty()) {
            return Optional.empty();
        }
        if (startRegion.get().id() == portalRegion.get().id()) {
            return Optional.of(new GraphPath(List.of(), 0));
        }

        ArrayDeque<GraphRouteNode> queue = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(new GraphRouteNode(startRegion.get().id(), List.of()));
        visited.add(startRegion.get().id());
        int explored = 0;
        while (!queue.isEmpty() && explored < ROUTE_MAX_MAPS) {
            GraphRouteNode node = queue.removeFirst();
            explored++;
            for (AgentNavigationGraph.Edge edge : graph.outgoingEdges(node.regionId()).stream()
                    .sorted(Comparator.comparingInt(AgentNavigationGraph.Edge::cost))
                    .toList()) {
                List<AgentNavigationGraph.Edge> nextPath = new ArrayList<>(node.path());
                nextPath.add(edge);
                if (edge.toRegionId() == portalRegion.get().id()) {
                    return Optional.of(new GraphPath(List.copyOf(nextPath), explored));
                }
                if (visited.add(edge.toRegionId())) {
                    queue.addLast(new GraphRouteNode(edge.toRegionId(), List.copyOf(nextPath)));
                }
            }
        }
        return Optional.empty();
    }

    private Optional<GraphPath> routeToFootholdRegion(int mapId, int startFootholdId, int targetFootholdId) {
        if (startFootholdId <= 0 || targetFootholdId <= 0) {
            return Optional.empty();
        }
        AgentNavigationGraph graph = graphBuilder.build(geometryRepository.load(mapId));
        Optional<AgentNavigationGraph.Region> startRegion = graph.regions().stream()
                .filter(region -> region.type() == AgentNavigationGraph.RegionType.FOOTHOLD)
                .filter(region -> region.sourceId() != null && region.sourceId() == startFootholdId)
                .findFirst();
        Optional<AgentNavigationGraph.Region> targetRegion = graph.regions().stream()
                .filter(region -> region.type() == AgentNavigationGraph.RegionType.FOOTHOLD)
                .filter(region -> region.sourceId() != null && region.sourceId() == targetFootholdId)
                .findFirst();
        if (startRegion.isEmpty() || targetRegion.isEmpty()) {
            return Optional.empty();
        }
        if (startRegion.get().id() == targetRegion.get().id()) {
            return Optional.of(new GraphPath(List.of(), 0));
        }

        ArrayDeque<GraphRouteNode> queue = new ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(new GraphRouteNode(startRegion.get().id(), List.of()));
        visited.add(startRegion.get().id());
        int explored = 0;
        while (!queue.isEmpty() && explored < ROUTE_MAX_MAPS) {
            GraphRouteNode node = queue.removeFirst();
            explored++;
            for (AgentNavigationGraph.Edge edge : graph.outgoingEdges(node.regionId()).stream()
                    .sorted(Comparator.comparingInt(AgentNavigationGraph.Edge::cost))
                    .toList()) {
                List<AgentNavigationGraph.Edge> nextPath = new ArrayList<>(node.path());
                nextPath.add(edge);
                if (edge.toRegionId() == targetRegion.get().id()) {
                    return Optional.of(new GraphPath(List.copyOf(nextPath), explored));
                }
                if (visited.add(edge.toRegionId())) {
                    queue.addLast(new GraphRouteNode(edge.toRegionId(), List.copyOf(nextPath)));
                }
            }
        }
        return Optional.empty();
    }

    private NavigationStep stepAlongGraphPath(NavigationContext context, GroundState ground, PortalTarget portal,
                                              Optional<RoutePlan> routePlan, GraphPath graphPath) {
        AgentNavigationGraph.Edge edge = graphPath.edges().getFirst();
        Map<String, Object> detail = portal.describe();
        detail.put("currentGround", ground.detail());
        detail.put("route", routePlan.map(RoutePlan::describe).orElse(null));
        detail.put("localGraph", graphPath.describe());
        detail.put("graphEdge", describeEdge(edge));
        return switch (edge.type()) {
            case WALK -> stepTowardGraphPoint(context, ground, portal, edge, detail, "GRAPH_WALK_APPROACH");
            case PORTAL -> {
                if (Math.abs(context.x() - edge.start().x) <= ARRIVAL_DISTANCE_PIXELS
                        && Math.abs(ground.y() - edge.start().y) <= MAX_DIRECT_VERTICAL_GAP_PIXELS) {
                    detail.put("next", "Virtual client should send the portal enter packet after this graph approach step.");
                    yield new NavigationStep(context.x(), ground.y(), ground.footholdId(), STANCE_STAND,
                            WALK_DURATION_MILLIS, context.direction(), "AT_PORTAL_TARGET", portal.label(), detail);
                }
                yield stepTowardGraphPoint(context, ground, portal, edge, detail, "GRAPH_PORTAL_APPROACH");
            }
            case JUMP -> stepJumpOrDropEdge(context, ground, portal, edge, detail, true);
            case DROP -> stepJumpOrDropEdge(context, ground, portal, edge, detail, false);
            case CLIMB -> stepGraphClimbEdge(context, ground, portal, edge, detail);
        };
    }

    private NavigationStep stepTowardGraphPoint(NavigationContext context, GroundState ground, PortalTarget portal,
                                                AgentNavigationGraph.Edge edge, Map<String, Object> detail,
                                                String mode) {
        Point target = Math.abs(context.x() - edge.start().x) > ARRIVAL_DISTANCE_PIXELS
                ? edge.start()
                : edge.end();
        int dx = target.x - context.x();
        if (Math.abs(dx) <= ARRIVAL_DISTANCE_PIXELS) {
            return new NavigationStep(target.x, target.y, ground.footholdId(), STANCE_STAND,
                    WALK_DURATION_MILLIS, context.direction(), mode, portal.label(), detail);
        }
        int direction = dx > 0 ? 1 : -1;
        int nextX = context.x() + (direction * Math.min(WALK_STEP_PIXELS, Math.abs(dx)));
        Optional<Foothold> foothold = foothold(context.mapId(), ground.footholdId());
        int nextY = foothold.filter(candidate -> candidate.spans(nextX))
                .map(candidate -> candidate.yAt(nextX))
                .orElse(ground.y());
        detail.put("graphTargetX", target.x);
        detail.put("graphTargetY", target.y);
        return new NavigationStep(nextX, nextY, ground.footholdId(),
                direction > 0 ? STANCE_WALK_RIGHT : STANCE_WALK_LEFT, WALK_DURATION_MILLIS,
                direction, mode, portal.label(), detail);
    }

    private NavigationStep stepJumpOrDropEdge(NavigationContext context, GroundState ground, PortalTarget portal,
                                              AgentNavigationGraph.Edge edge, Map<String, Object> detail,
                                              boolean jump) {
        if (Math.abs(context.x() - edge.start().x) > ARRIVAL_DISTANCE_PIXELS) {
            return stepTowardGraphPoint(context, ground, portal, edge, detail,
                    jump ? "GRAPH_JUMP_APPROACH" : "GRAPH_DROP_APPROACH");
        }
        int direction = edge.end().x >= edge.start().x ? 1 : -1;
        int nextX = context.x() + (direction * Math.min(EDGE_STEP_PIXELS, Math.abs(edge.end().x - context.x())));
        int nextY = context.y() + Integer.compare(edge.end().y, context.y())
                * Math.min(EDGE_STEP_PIXELS, Math.abs(edge.end().y - context.y()));
        detail.put("edgeType", jump ? "JUMP" : "DROP");
        detail.put("calibration", (jump ? "JUMP" : "DROP") + " stance/duration candidate; tune after live client observation.");
        return new NavigationStep(nextX, nextY, ground.footholdId(),
                jump ? STANCE_JUMP : STANCE_DROP,
                jump ? JUMP_DURATION_MILLIS : DROP_DURATION_MILLIS,
                direction,
                jump ? "GRAPH_JUMP_EXECUTION" : "GRAPH_DROP_EXECUTION",
                portal.label(),
                detail);
    }

    private NavigationStep stepGraphClimbEdge(NavigationContext context, GroundState ground, PortalTarget portal,
                                              AgentNavigationGraph.Edge edge, Map<String, Object> detail) {
        int dx = edge.start().x - context.x();
        if (Math.abs(dx) > ARRIVAL_DISTANCE_PIXELS) {
            return stepTowardGraphPoint(context, ground, portal, edge, detail, "GRAPH_CLIMB_APPROACH");
        }
        int verticalDirection = edge.end().y < context.y() ? -1 : 1;
        int nextY = context.y() + (verticalDirection * Math.min(EDGE_STEP_PIXELS, Math.abs(edge.end().y - context.y())));
        detail.put("edgeType", "CLIMB");
        detail.put("calibration", "CLIMB stance/duration candidate; tune after live client observation.");
        return new NavigationStep(edge.start().x, nextY, ground.footholdId(), STANCE_CLIMB, CLIMB_DURATION_MILLIS,
                context.direction(), "GRAPH_CLIMB_EXECUTION", portal.label(), detail);
    }

    private Map<String, Object> describeEdge(AgentNavigationGraph.Edge edge) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromRegionId", edge.fromRegionId());
        detail.put("toRegionId", edge.toRegionId());
        detail.put("type", edge.type().name());
        detail.put("startX", edge.start().x);
        detail.put("startY", edge.start().y);
        detail.put("endX", edge.end().x);
        detail.put("endY", edge.end().y);
        detail.put("cost", edge.cost());
        detail.put("label", edge.label());
        return detail;
    }

    private NavigationStep stepAcrossEdge(NavigationContext context, GroundState ground, Foothold targetGround,
                                          PortalTarget portal, Map<String, Object> detail) {
        Optional<LadderRope> ladderRope = nearestLadderRope(context.mapId(), context.x(), ground.y(), portal.x(), targetGround.yAt(portal.x()));
        if (ladderRope.isPresent()) {
            return stepViaLadderRope(context, ground, ladderRope.get(), targetGround.yAt(portal.x()), portal, detail);
        }

        int targetY = targetGround.yAt(portal.x());
        int dx = portal.x() - context.x();
        int direction = dx > 0 ? 1 : -1;
        int nextX = context.x() + (direction * Math.min(WALK_STEP_PIXELS, Math.abs(dx)));
        if (targetY < ground.y() - MAX_DIRECT_VERTICAL_GAP_PIXELS) {
            int nextY = Math.max(targetY, ground.y() - EDGE_STEP_PIXELS);
            detail.put("edgeType", "JUMP");
            detail.put("calibration", "JUMP stance/duration candidate; tune after live client observation.");
            return new NavigationStep(nextX, nextY, ground.footholdId(), STANCE_JUMP, JUMP_DURATION_MILLIS,
                    direction, "JUMP_EDGE_EXECUTION", portal.label(), detail);
        }

        int nextY = Math.min(targetY, ground.y() + EDGE_STEP_PIXELS);
        detail.put("edgeType", "DROP");
        detail.put("calibration", "DROP stance/duration candidate; tune after live client observation.");
        return new NavigationStep(nextX, nextY, ground.footholdId(), STANCE_DROP, DROP_DURATION_MILLIS,
                direction, "DROP_EDGE_EXECUTION", portal.label(), detail);
    }

    private Optional<LadderRope> nearestLadderRope(int mapId, int currentX, int currentY, int targetX, int targetY) {
        int minX = Math.min(currentX, targetX) - 64;
        int maxX = Math.max(currentX, targetX) + 64;
        int minY = Math.min(currentY, targetY) - 16;
        int maxY = Math.max(currentY, targetY) + 16;
        List<LadderRope> ladders = cmsJdbc.query("""
                        SELECT object_index, is_ladder, is_upper_foothold, x, y1, y2, page, source_path
                        FROM agent_map_ladder_ropes
                        WHERE map_id = ?
                          AND x BETWEEN ? AND ?
                          AND LEAST(y1, y2) <= ?
                          AND GREATEST(y1, y2) >= ?
                        ORDER BY ABS(x - ?), ABS(((y1 + y2) / 2) - ?)
                        LIMIT 8
                        """,
                (rs, rowNum) -> new LadderRope(
                        rs.getInt("object_index"),
                        rs.getInt("is_ladder") != 0,
                        rs.getInt("is_upper_foothold") != 0,
                        rs.getInt("x"),
                        rs.getInt("y1"),
                        rs.getInt("y2"),
                        rs.getInt("page"),
                        rs.getString("source_path")
                ),
                mapId,
                minX,
                maxX,
                maxY,
                minY,
                currentX,
                currentY
        );
        return ladders.stream().findFirst();
    }

    private NavigationStep stepViaLadderRope(NavigationContext context, GroundState ground, LadderRope ladderRope,
                                             int targetY, PortalTarget portal, Map<String, Object> detail) {
        int dx = ladderRope.x() - context.x();
        if (Math.abs(dx) > ARRIVAL_DISTANCE_PIXELS) {
            int direction = dx > 0 ? 1 : -1;
            int nextX = context.x() + (direction * Math.min(WALK_STEP_PIXELS, Math.abs(dx)));
            GroundState nextGround = nearestFoothold(context.mapId(), nextX, ground.y())
                    .map(foothold -> new GroundState(foothold.id(), foothold.yAt(nextX), true, foothold.describe()))
                    .orElse(ground);
            detail.put("edgeType", "CLIMB_APPROACH");
            detail.put("ladderRope", ladderRope.describe());
            return new NavigationStep(nextX, nextGround.y(), nextGround.footholdId(),
                    direction > 0 ? STANCE_WALK_RIGHT : STANCE_WALK_LEFT, WALK_DURATION_MILLIS,
                    direction, "CLIMB_EDGE_APPROACH", portal.label(), detail);
        }

        int verticalDirection = targetY < context.y() ? -1 : 1;
        int nextY = context.y() + (verticalDirection * Math.min(EDGE_STEP_PIXELS, Math.abs(targetY - context.y())));
        detail.put("edgeType", ladderRope.isLadder() ? "LADDER" : "ROPE");
        detail.put("ladderRope", ladderRope.describe());
        detail.put("calibration", "CLIMB stance/duration candidate; tune after live client observation.");
        return new NavigationStep(ladderRope.x(), nextY, ground.footholdId(), STANCE_CLIMB, CLIMB_DURATION_MILLIS,
                context.direction(), "CLIMB_EDGE_EXECUTION", portal.label(), detail);
    }

    record NavigationContext(int profileId, int characterId, int mapId, int x, int y, int foothold, int stance,
                             Integer originX, int direction, Integer targetMapId,
                             Integer targetX, Integer targetY, String targetLabel) {
        NavigationContext(int profileId, int characterId, int mapId, int x, int y, int foothold, int stance,
                          Integer originX, int direction, Integer targetMapId) {
            this(profileId, characterId, mapId, x, y, foothold, stance, originX, direction, targetMapId,
                    null, null, null);
        }

        Optional<LocalTarget> localTarget() {
            if (targetX == null || targetY == null) {
                return Optional.empty();
            }
            String label = targetLabel == null || targetLabel.isBlank()
                    ? "local target " + targetX + "," + targetY
                    : targetLabel;
            return Optional.of(new LocalTarget(targetX, targetY, label));
        }
    }

    record NavigationStep(int x, int y, int foothold, int stance, int durationMillis, int nextDirection,
                          String mode, String target, Map<String, Object> detail) {
        private static NavigationStep fromPhysics(AgentPhysicsSystem.MovementStep step, String mode, String target,
                                                  Map<String, Object> detail) {
            return new NavigationStep(step.x(), step.y(), step.foothold(), step.stance(), step.durationMillis(),
                    step.nextDirection(), mode, target, detail);
        }

        Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>(
                    AgentMovementPacketFactory.describeNavigationMove(mode, x, y, x, y, foothold, stance, durationMillis));
            result.put("navigationMode", mode);
            result.put("target", target);
            result.put("nextDirection", nextDirection);
            result.put("detail", detail);
            return result;
        }

        boolean requiresPortalEntry() {
            return "AT_PORTAL_TARGET".equals(mode);
        }

        boolean blocked() {
            return mode != null && mode.endsWith("_BLOCKED");
        }

        Optional<String> portalName() {
            Object value = detail.get("portalName");
            if (value == null || String.valueOf(value).isBlank()) {
                return Optional.empty();
            }
            return Optional.of(String.valueOf(value));
        }
    }

    private record PortalTarget(int mapId, int index, String name, int type, int targetMapId, String targetPortalName,
                                int x, int y, String sourcePath) {
        private static PortalTarget localTarget(int mapId, LocalTarget target, int projectedY) {
            return new PortalTarget(mapId, -1, target.label(), 0, mapId, "local-target",
                    target.x(), projectedY, "cosmic_agent_cms.agent_map_footholds");
        }

        private String label() {
            return name + " -> " + targetMapId + ":" + targetPortalName;
        }

        private Map<String, Object> describe() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("mapId", mapId);
            detail.put("portalIndex", index);
            detail.put("portalName", name);
            detail.put("portalType", type);
            detail.put("targetMapId", targetMapId);
            detail.put("targetPortalName", targetPortalName);
            detail.put("x", x);
            detail.put("y", y);
            detail.put("sourcePath", sourcePath);
            return detail;
        }
    }

    private record LocalTarget(int x, int y, String label) {
        private Map<String, Object> describe() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("targetX", x);
            detail.put("targetY", y);
            detail.put("targetLabel", label);
            return detail;
        }
    }

    private record RouteNode(int mapId, List<PortalTarget> path) {
    }

    private record GraphRouteNode(int regionId, List<AgentNavigationGraph.Edge> path) {
    }

    private record GraphPath(List<AgentNavigationGraph.Edge> edges, int exploredRegions) {
        private Map<String, Object> describe() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("edgeCount", edges.size());
            detail.put("exploredRegions", exploredRegions);
            detail.put("path", edges.stream()
                    .map(edge -> edge.type() + ":" + edge.label() + " " + edge.start().x + "," + edge.start().y
                            + " -> " + edge.end().x + "," + edge.end().y)
                    .toList());
            return detail;
        }
    }

    private record RoutePlan(List<PortalTarget> portals, int exploredMaps) {
        private Optional<PortalTarget> nextPortal() {
            return portals.isEmpty() ? Optional.empty() : Optional.of(portals.getFirst());
        }

        private Map<String, Object> describe() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("routeDepth", portals.size());
            detail.put("exploredMaps", exploredMaps);
            detail.put("nextPortal", nextPortal().map(PortalTarget::describe).orElse(null));
            detail.put("path", portals.stream().map(PortalTarget::label).toList());
            return detail;
        }
    }

    private record LadderRope(int index, boolean isLadder, boolean isUpperFoothold, int x, int y1, int y2, int page,
                              String sourcePath) {
        private Map<String, Object> describe() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("objectIndex", index);
            detail.put("type", isLadder ? "ladder" : "rope");
            detail.put("isUpperFoothold", isUpperFoothold);
            detail.put("x", x);
            detail.put("y1", y1);
            detail.put("y2", y2);
            detail.put("page", page);
            detail.put("sourcePath", sourcePath);
            return detail;
        }
    }

    private record GroundState(int footholdId, int y, boolean grounded, Map<String, Object> detail) {
    }

    private record Foothold(int id, int x1, int y1, int x2, int y2, int prev, int next, boolean forbidFallDown) {
        private boolean spans(int x) {
            return x >= Math.min(x1, x2) - 8 && x <= Math.max(x1, x2) + 8 && x1 != x2;
        }

        private int yAt(int x) {
            if (x1 == x2) {
                return Math.min(y1, y2);
            }
            double ratio = (x - x1) / (double) (x2 - x1);
            return (int) Math.round(y1 + ((y2 - y1) * ratio));
        }

        private boolean connectedTo(int footholdId) {
            return id == footholdId || prev == footholdId || next == footholdId || footholdId == 0;
        }

        private Map<String, Object> describe() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("footholdId", id);
            detail.put("x1", x1);
            detail.put("y1", y1);
            detail.put("x2", x2);
            detail.put("y2", y2);
            detail.put("prev", prev);
            detail.put("next", next);
            detail.put("forbidFallDown", forbidFallDown);
            return detail;
        }
    }
}
