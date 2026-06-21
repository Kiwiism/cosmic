package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNavigationGraphBuilderTest {
    private final AgentNavigationGraphBuilder builder = new AgentNavigationGraphBuilder();

    @Test
    void buildsWalkDropClimbAndPortalEdgesFromPureGeometry() {
        AgentMapGeometry geometry = new AgentMapGeometry(10000, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 100, 300, 0, 2, false),
                new AgentMapGeometry.Foothold(2, 104, 300, 200, 300, 1, 0, false),
                new AgentMapGeometry.Foothold(3, 0, 420, 220, 420, 0, 0, false)
        ), List.of(
                new AgentMapGeometry.LadderRope(9, true, false, 50, 180, 300, 0, "test")
        ), List.of(
                new AgentMapGeometry.Portal(0, "east00", 2, 20000, "west00", 190, 300, "test")
        ));

        AgentNavigationGraph graph = builder.build(geometry);

        assertThat(graph.regions()).anyMatch(region -> region.type() == AgentNavigationGraph.RegionType.FOOTHOLD);
        assertThat(graph.regions()).anyMatch(region -> region.type() == AgentNavigationGraph.RegionType.LADDER_ROPE);
        assertThat(graph.regions()).anyMatch(region -> region.type() == AgentNavigationGraph.RegionType.PORTAL);
        assertThat(graph.edges()).anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.WALK);
        assertThat(graph.edges()).anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.DROP);
        assertThat(graph.edges()).anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.CLIMB);
        assertThat(graph.edges()).anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.PORTAL
                && edge.targetMapId() == 20000);
    }

    @Test
    void buildsConservativeJumpEdgesOnlyForReachableNearbyFootholds() {
        AgentMapGeometry geometry = new AgentMapGeometry(10000, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 100, 300, 0, 0, false),
                new AgentMapGeometry.Foothold(2, 150, 250, 240, 250, 0, 0, false),
                new AgentMapGeometry.Foothold(3, 400, 100, 500, 100, 0, 0, false)
        ), List.of(), List.of());

        AgentNavigationGraph graph = builder.build(geometry);

        assertThat(graph.edges())
                .anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.JUMP
                        && edge.start().x <= 100
                        && edge.end().x >= 150);
        assertThat(graph.edges())
                .noneMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.JUMP
                        && edge.end().x >= 400);
    }

    @Test
    void walkEdgesUseTheActualAdjacentSideForBothDirections() {
        AgentMapGeometry geometry = new AgentMapGeometry(10000, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 100, 300, 0, 2, false),
                new AgentMapGeometry.Foothold(2, 104, 300, 200, 300, 1, 0, false)
        ), List.of(), List.of());

        AgentNavigationGraph graph = builder.build(geometry);

        assertThat(graph.edges())
                .anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.WALK
                        && edge.start().x == 100
                        && edge.end().x == 104);
        assertThat(graph.edges())
                .anyMatch(edge -> edge.type() == AgentNavigationGraph.EdgeType.WALK
                        && edge.start().x == 104
                        && edge.end().x == 100);
    }
}
