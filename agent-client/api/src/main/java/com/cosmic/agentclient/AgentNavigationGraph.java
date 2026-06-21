package com.cosmic.agentclient;

import java.awt.Point;
import java.util.List;
import java.util.Optional;

record AgentNavigationGraph(int mapId, List<Region> regions, List<Edge> edges) {
    Optional<Region> regionById(int id) {
        return regions.stream().filter(region -> region.id() == id).findFirst();
    }

    List<Edge> outgoingEdges(int regionId) {
        return edges.stream().filter(edge -> edge.fromRegionId() == regionId).toList();
    }

    enum RegionType {
        FOOTHOLD,
        LADDER_ROPE,
        PORTAL
    }

    enum EdgeType {
        WALK,
        JUMP,
        DROP,
        CLIMB,
        PORTAL
    }

    record Region(int id, RegionType type, Integer sourceId, Point anchor, int minX, int maxX, int minY, int maxY) {
    }

    record Edge(int fromRegionId, int toRegionId, EdgeType type, Point start, Point end, int cost,
                String label, Integer targetMapId, String targetPortalName) {
    }
}
