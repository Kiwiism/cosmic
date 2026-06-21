package com.cosmic.agentclient;

import org.springframework.stereotype.Service;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
class AgentNavigationGraphBuilder {
    private static final int WALK_STITCH_PIXELS = 12;
    private static final int JUMP_HORIZONTAL_PIXELS = 118;
    private static final int JUMP_UP_PIXELS = 96;
    private static final int JUMP_DOWN_PIXELS = 72;
    private static final int DROP_SCAN_PIXELS = 360;
    private static final int CLIMB_ATTACH_PIXELS = 18;
    private static final int PORTAL_ATTACH_PIXELS = 24;

    AgentNavigationGraph build(AgentMapGeometry geometry) {
        List<AgentNavigationGraph.Region> regions = new ArrayList<>();
        Map<Integer, Integer> footholdRegionById = new HashMap<>();
        Map<Integer, Integer> ladderRegionByIndex = new HashMap<>();

        int regionId = 1;
        for (AgentMapGeometry.Foothold foothold : geometry.footholds()) {
            if (!foothold.walkable()) {
                continue;
            }
            int id = regionId++;
            footholdRegionById.put(foothold.id(), id);
            regions.add(new AgentNavigationGraph.Region(
                    id,
                    AgentNavigationGraph.RegionType.FOOTHOLD,
                    foothold.id(),
                    new Point((foothold.minX() + foothold.maxX()) / 2, foothold.yAt((foothold.minX() + foothold.maxX()) / 2)),
                    foothold.minX(),
                    foothold.maxX(),
                    Math.min(foothold.y1(), foothold.y2()),
                    Math.max(foothold.y1(), foothold.y2())
            ));
        }

        for (AgentMapGeometry.LadderRope ladderRope : geometry.ladderRopes()) {
            int id = regionId++;
            ladderRegionByIndex.put(ladderRope.index(), id);
            regions.add(new AgentNavigationGraph.Region(
                    id,
                    AgentNavigationGraph.RegionType.LADDER_ROPE,
                    ladderRope.index(),
                    new Point(ladderRope.x(), ladderRope.centerY()),
                    ladderRope.x(),
                    ladderRope.x(),
                    ladderRope.topY(),
                    ladderRope.bottomY()
            ));
        }

        for (AgentMapGeometry.Portal portal : geometry.portals()) {
            int id = regionId++;
            regions.add(new AgentNavigationGraph.Region(
                    id,
                    AgentNavigationGraph.RegionType.PORTAL,
                    portal.index(),
                    new Point(portal.x(), portal.y()),
                    portal.x(),
                    portal.x(),
                    portal.y(),
                    portal.y()
            ));
        }

        List<AgentNavigationGraph.Edge> edges = new ArrayList<>();
        addWalkEdges(geometry, footholdRegionById, edges);
        addJumpEdges(geometry, footholdRegionById, edges);
        addDropEdges(geometry, footholdRegionById, edges);
        addClimbEdges(geometry, footholdRegionById, ladderRegionByIndex, edges);
        addPortalEdges(geometry, regions, footholdRegionById, edges);
        return new AgentNavigationGraph(geometry.mapId(), List.copyOf(regions), List.copyOf(edges));
    }

    private void addWalkEdges(AgentMapGeometry geometry, Map<Integer, Integer> footholdRegionById,
                              List<AgentNavigationGraph.Edge> edges) {
        for (AgentMapGeometry.Foothold from : geometry.footholds()) {
            Integer fromRegion = footholdRegionById.get(from.id());
            if (fromRegion == null) {
                continue;
            }
            for (AgentMapGeometry.Foothold to : geometry.footholds()) {
                Integer toRegion = footholdRegionById.get(to.id());
                if (toRegion == null || from.id() == to.id()) {
                    continue;
                }
                boolean linked = from.nextId() == to.id() || from.prevId() == to.id()
                        || Math.abs(from.maxX() - to.minX()) <= WALK_STITCH_PIXELS
                        || Math.abs(to.maxX() - from.minX()) <= WALK_STITCH_PIXELS;
                if (!linked) {
                    continue;
                }
                Point start = walkExitPoint(from, to);
                Point end = walkEntryPoint(from, to);
                edges.add(new AgentNavigationGraph.Edge(fromRegion, toRegion, AgentNavigationGraph.EdgeType.WALK,
                        start, end, Math.max(1, Math.abs(end.x - start.x)), "walk-linked-footholds", null, null));
            }
        }
    }

    private void addJumpEdges(AgentMapGeometry geometry, Map<Integer, Integer> footholdRegionById,
                              List<AgentNavigationGraph.Edge> edges) {
        for (AgentMapGeometry.Foothold from : geometry.footholds()) {
            Integer fromRegion = footholdRegionById.get(from.id());
            if (fromRegion == null) {
                continue;
            }
            for (AgentMapGeometry.Foothold to : geometry.footholds()) {
                Integer toRegion = footholdRegionById.get(to.id());
                if (toRegion == null || from.id() == to.id() || walkLinked(from, to)) {
                    continue;
                }
                Optional<JumpCandidate> candidate = jumpCandidate(from, to);
                if (candidate.isEmpty()) {
                    continue;
                }
                JumpCandidate jump = candidate.get();
                int cost = Math.max(1, Math.abs(jump.end().x - jump.start().x))
                        + Math.max(1, Math.abs(jump.end().y - jump.start().y)) * 2;
                edges.add(new AgentNavigationGraph.Edge(fromRegion, toRegion, AgentNavigationGraph.EdgeType.JUMP,
                        jump.start(), jump.end(), cost, "jump-between-nearby-footholds", null, null));
            }
        }
    }

    private void addDropEdges(AgentMapGeometry geometry, Map<Integer, Integer> footholdRegionById,
                              List<AgentNavigationGraph.Edge> edges) {
        for (AgentMapGeometry.Foothold from : geometry.footholds()) {
            Integer fromRegion = footholdRegionById.get(from.id());
            if (fromRegion == null || from.forbidFallDown()) {
                continue;
            }
            for (int x : List.of(from.minX() + 2, from.maxX() - 2, (from.minX() + from.maxX()) / 2)) {
                int fromY = from.yAt(x);
                Optional<AgentMapGeometry.Ground> ground = geometry.footholds().stream()
                        .filter(target -> target.id() != from.id())
                        .filter(target -> target.walkable() && target.spans(x))
                        .map(target -> new AgentMapGeometry.Ground(target, target.yAt(x)))
                        .filter(target -> target.y() > fromY && target.y() - fromY <= DROP_SCAN_PIXELS)
                        .min(Comparator.comparingInt(target -> target.y() - fromY));
                if (ground.isEmpty()) {
                    continue;
                }
                Integer toRegion = footholdRegionById.get(ground.get().foothold().id());
                if (toRegion != null) {
                    edges.add(new AgentNavigationGraph.Edge(fromRegion, toRegion, AgentNavigationGraph.EdgeType.DROP,
                            new Point(x, fromY), new Point(x, ground.get().y()), ground.get().y() - fromY,
                            "drop-to-lower-foothold", null, null));
                }
            }
        }
    }

    private void addClimbEdges(AgentMapGeometry geometry, Map<Integer, Integer> footholdRegionById,
                               Map<Integer, Integer> ladderRegionByIndex, List<AgentNavigationGraph.Edge> edges) {
        for (AgentMapGeometry.LadderRope ladderRope : geometry.ladderRopes()) {
            Integer ladderRegion = ladderRegionByIndex.get(ladderRope.index());
            if (ladderRegion == null) {
                continue;
            }
            for (AgentMapGeometry.Foothold foothold : geometry.footholds()) {
                Integer footholdRegion = footholdRegionById.get(foothold.id());
                if (footholdRegion == null || !foothold.spans(ladderRope.x())) {
                    continue;
                }
                int footholdY = foothold.yAt(ladderRope.x());
                boolean attachable = Math.abs(footholdY - ladderRope.topY()) <= CLIMB_ATTACH_PIXELS
                        || Math.abs(footholdY - ladderRope.bottomY()) <= CLIMB_ATTACH_PIXELS
                        || ladderRope.containsY(footholdY);
                if (!attachable) {
                    continue;
                }
                Point groundPoint = new Point(ladderRope.x(), footholdY);
                Point ropePoint = new Point(ladderRope.x(), clamp(footholdY, ladderRope.topY(), ladderRope.bottomY()));
                edges.add(new AgentNavigationGraph.Edge(footholdRegion, ladderRegion, AgentNavigationGraph.EdgeType.CLIMB,
                        groundPoint, ropePoint, 24, "attach-ladder-rope", null, null));
                edges.add(new AgentNavigationGraph.Edge(ladderRegion, footholdRegion, AgentNavigationGraph.EdgeType.CLIMB,
                        ropePoint, groundPoint, 24, "exit-ladder-rope", null, null));
            }
        }
    }

    private void addPortalEdges(AgentMapGeometry geometry, List<AgentNavigationGraph.Region> regions,
                                Map<Integer, Integer> footholdRegionById, List<AgentNavigationGraph.Edge> edges) {
        List<AgentNavigationGraph.Region> portalRegions = regions.stream()
                .filter(region -> region.type() == AgentNavigationGraph.RegionType.PORTAL)
                .toList();
        for (AgentNavigationGraph.Region portalRegion : portalRegions) {
            AgentMapGeometry.Portal portal = geometry.portals().stream()
                    .filter(candidate -> candidate.index() == portalRegion.sourceId())
                    .findFirst()
                    .orElse(null);
            if (portal == null) {
                continue;
            }
            Optional<AgentMapGeometry.Ground> ground = geometry.groundAt(portal.x(), portal.y())
                    .or(() -> geometry.groundBelow(portal.x(), portal.y() - PORTAL_ATTACH_PIXELS));
            if (ground.isEmpty()) {
                continue;
            }
            Integer footholdRegion = footholdRegionById.get(ground.get().foothold().id());
            if (footholdRegion == null) {
                continue;
            }
            Point point = new Point(portal.x(), ground.get().y());
            edges.add(new AgentNavigationGraph.Edge(footholdRegion, portalRegion.id(), AgentNavigationGraph.EdgeType.PORTAL,
                    point, new Point(portal.x(), portal.y()), 12, portal.name(), portal.targetMapId(),
                    portal.targetPortalName()));
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean walkLinked(AgentMapGeometry.Foothold from, AgentMapGeometry.Foothold to) {
        return from.nextId() == to.id() || from.prevId() == to.id()
                || Math.abs(from.maxX() - to.minX()) <= WALK_STITCH_PIXELS
                || Math.abs(to.maxX() - from.minX()) <= WALK_STITCH_PIXELS;
    }

    private static Point walkExitPoint(AgentMapGeometry.Foothold from, AgentMapGeometry.Foothold to) {
        if (from.maxX() <= to.minX()) {
            return new Point(from.maxX(), from.yAt(from.maxX()));
        }
        if (to.maxX() <= from.minX()) {
            return new Point(from.minX(), from.yAt(from.minX()));
        }
        int overlapX = clamp((Math.max(from.minX(), to.minX()) + Math.min(from.maxX(), to.maxX())) / 2,
                from.minX(), from.maxX());
        return new Point(overlapX, from.yAt(overlapX));
    }

    private static Point walkEntryPoint(AgentMapGeometry.Foothold from, AgentMapGeometry.Foothold to) {
        if (from.maxX() <= to.minX()) {
            return new Point(to.minX(), to.yAt(to.minX()));
        }
        if (to.maxX() <= from.minX()) {
            return new Point(to.maxX(), to.yAt(to.maxX()));
        }
        int overlapX = clamp((Math.max(from.minX(), to.minX()) + Math.min(from.maxX(), to.maxX())) / 2,
                to.minX(), to.maxX());
        return new Point(overlapX, to.yAt(overlapX));
    }

    private static Optional<JumpCandidate> jumpCandidate(AgentMapGeometry.Foothold from, AgentMapGeometry.Foothold to) {
        Point start;
        Point end;
        if (from.maxX() < to.minX()) {
            start = new Point(from.maxX() - 2, from.yAt(from.maxX() - 2));
            end = new Point(to.minX() + 2, to.yAt(to.minX() + 2));
        } else if (to.maxX() < from.minX()) {
            start = new Point(from.minX() + 2, from.yAt(from.minX() + 2));
            end = new Point(to.maxX() - 2, to.yAt(to.maxX() - 2));
        } else {
            int x = clamp((Math.max(from.minX(), to.minX()) + Math.min(from.maxX(), to.maxX())) / 2,
                    from.minX(), from.maxX());
            start = new Point(x, from.yAt(x));
            end = new Point(clamp(x, to.minX(), to.maxX()), to.yAt(clamp(x, to.minX(), to.maxX())));
        }

        int horizontal = Math.abs(end.x - start.x);
        int vertical = end.y - start.y;
        if (horizontal > JUMP_HORIZONTAL_PIXELS) {
            return Optional.empty();
        }
        if (vertical < -JUMP_UP_PIXELS || vertical > JUMP_DOWN_PIXELS) {
            return Optional.empty();
        }
        if (horizontal < WALK_STITCH_PIXELS && Math.abs(vertical) < WALK_STITCH_PIXELS) {
            return Optional.empty();
        }
        return Optional.of(new JumpCandidate(start, end));
    }

    private record JumpCandidate(Point start, Point end) {
    }
}
