package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPhysicsSystemTest {
    private final AgentPhysicsSystem physics = new AgentPhysicsSystem();

    @Test
    void snapsCharacterToNearestGround() {
        AgentMapGeometry geometry = flatMap();

        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 50, 304, 0, 1);

        assertThat(state.mode()).isEqualTo(AgentPhysicsSystem.MotionMode.GROUNDED);
        assertThat(state.y()).isEqualTo(300);
        assertThat(state.footholdId()).isEqualTo(1);
    }

    @Test
    void groundedWalkFollowsFootholdSlope() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 100, 250, 0, 0, false)
        ), List.of(), List.of());
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 20, 290, 0, 1);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.walk(1));

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.GROUNDED);
        assertThat(step.current().x()).isGreaterThan(state.x());
        assertThat(step.current().y()).isEqualTo(geometry.groundAt(step.current().x(), step.current().y()).orElseThrow().y());
    }

    @Test
    void walkingPastFootholdBecomesAirborneAndThenLandsBelow() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 100, 20, 100, 0, 0, false),
                new AgentMapGeometry.Foothold(2, -100, 200, 200, 200, 0, 0, false)
        ), List.of(), List.of());
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 18, 100, 1, 1);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.walk(1));
        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.AIRBORNE);

        AgentPhysicsSystem.MotionState falling = step.current();
        for (int i = 0; i < 20 && falling.mode() == AgentPhysicsSystem.MotionMode.AIRBORNE; i++) {
            falling = physics.tick(geometry, falling, AgentPhysicsSystem.MovementIntent.idle()).current();
        }

        assertThat(falling.mode()).isEqualTo(AgentPhysicsSystem.MotionMode.GROUNDED);
        assertThat(falling.y()).isEqualTo(200);
        assertThat(falling.footholdId()).isEqualTo(2);
    }

    @Test
    void groundedWalkDoesNotSnapDownToLowerPlatforms() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 100, 20, 100, 0, 0, false),
                new AgentMapGeometry.Foothold(2, 21, 150, 200, 150, 0, 0, false)
        ), List.of(), List.of());
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 18, 100, 1, 1);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.walk(1));

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.AIRBORNE);
        assertThat(step.current().y()).isEqualTo(100);
        assertThat(step.event()).isEqualTo("WALK_OFF_EDGE");
    }

    @Test
    void uncontrolledFallOutsideFootholdSpanRecoversToNearestSafeGround() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 100, 20, 100, 0, 0, false)
        ), List.of(), List.of());
        AgentPhysicsSystem.MotionState falling = new AgentPhysicsSystem.MotionState(
                AgentPhysicsSystem.MotionMode.AIRBORNE, 50, 850, 125, 670, 1, 1, 6, null);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, falling, AgentPhysicsSystem.MovementIntent.idle());

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.GROUNDED);
        assertThat(step.current().x()).isEqualTo(20);
        assertThat(step.current().y()).isEqualTo(100);
        assertThat(step.current().footholdId()).isEqualTo(1);
    }

    @Test
    void midAirFallOutsideFootholdSpanRecoversBeforeDriftingTooFar() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 100, 20, 100, 0, 0, false)
        ), List.of(), List.of());
        AgentPhysicsSystem.MotionState falling = new AgentPhysicsSystem.MotionState(
                AgentPhysicsSystem.MotionMode.AIRBORNE, 50, 350, 125, 670, 1, 1, 6, null);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, falling, AgentPhysicsSystem.MovementIntent.idle());

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.GROUNDED);
        assertThat(step.current().x()).isEqualTo(20);
        assertThat(step.current().y()).isEqualTo(100);
        assertThat(step.event()).isEqualTo("AIR_FALL_RECOVERED");
    }

    @Test
    void groundedWalkAllowsSmallLegalStepChanges() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 100, 20, 100, 0, 0, false),
                new AgentMapGeometry.Foothold(2, 21, 108, 200, 108, 0, 0, false)
        ), List.of(), List.of());
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 18, 100, 1, 1);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.walk(1));

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.GROUNDED);
        assertThat(step.current().y()).isEqualTo(108);
        assertThat(step.current().footholdId()).isEqualTo(2);
    }

    @Test
    void jumpStartsAirborneAndKeepsLastGroundFootholdUntilLanding() {
        AgentMapGeometry geometry = flatMap();
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 50, 300, 1, 1);

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.jump(1));

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.AIRBORNE);
        assertThat(step.current().velocityY()).isLessThan(0);
        assertThat(step.current().footholdId()).isEqualTo(1);
        assertThat(step.current().stance()).isEqualTo(6);
    }

    @Test
    void climbIntentAttachesToRopeAndMovesVertically() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 200, 300, 0, 0, false)
        ), List.of(
                new AgentMapGeometry.LadderRope(7, false, false, 50, 100, 300, 0, "test")
        ), List.of());
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 50, 300, 1, 1);

        AgentPhysicsSystem.MotionState attached = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.climb(-1)).current();
        AgentPhysicsSystem.MotionState climbed = physics.tick(geometry, attached, AgentPhysicsSystem.MovementIntent.climb(-1)).current();

        assertThat(attached.mode()).isEqualTo(AgentPhysicsSystem.MotionMode.CLIMBING);
        assertThat(attached.ladderRopeIndex()).isEqualTo(7);
        assertThat(climbed.y()).isLessThan(attached.y());
        assertThat(climbed.stance()).isEqualTo(16);
    }

    @Test
    void climbJumpDetachesFromRopeIntoAirbornePhysics() {
        AgentMapGeometry geometry = new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 200, 300, 0, 0, false)
        ), List.of(
                new AgentMapGeometry.LadderRope(7, false, false, 50, 100, 300, 0, "test")
        ), List.of());
        AgentPhysicsSystem.MotionState state = physics.snapToGround(geometry, 50, 300, 1, 1);
        AgentPhysicsSystem.MotionState attached = physics.tick(geometry, state, AgentPhysicsSystem.MovementIntent.climb(-1)).current();

        AgentPhysicsSystem.PhysicsStep step = physics.tick(geometry, attached, AgentPhysicsSystem.MovementIntent.jump(1));

        assertThat(step.current().mode()).isEqualTo(AgentPhysicsSystem.MotionMode.AIRBORNE);
        assertThat(step.current().ladderRopeIndex()).isNull();
        assertThat(step.event()).isEqualTo("CLIMB_JUMP");
    }

    private static AgentMapGeometry flatMap() {
        return new AgentMapGeometry(1, List.of(
                new AgentMapGeometry.Foothold(1, 0, 300, 200, 300, 0, 0, false)
        ), List.of(), List.of());
    }
}
