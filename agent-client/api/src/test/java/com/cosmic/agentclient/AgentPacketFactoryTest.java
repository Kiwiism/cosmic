package com.cosmic.agentclient;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPacketFactoryTest {
    @Test
    void movementPacketUsesMovePlayerOpcodeAndExpectedBodyShape() {
        byte[] body = AgentMovementPacketFactory.absoluteMoveBody(100, 200, 7, 4, 160);

        assertThat(unsignedShort(body, 0)).isEqualTo(0x29);
        assertThat(body).hasSize(26);
        assertThat(body[11]).isEqualTo((byte) 1);
        assertThat(body[12]).isEqualTo((byte) 0);
        assertThat(unsignedShort(body, 13)).isEqualTo(100);
        assertThat(unsignedShort(body, 15)).isEqualTo(200);
        assertThat(unsignedShort(body, 21)).isEqualTo(7);
        assertThat(body[23]).isEqualTo((byte) 4);
        assertThat(unsignedShort(body, 24)).isEqualTo(160);
    }

    @Test
    void richerMovementPacketsUseMultipleFragmentsForClientLikeMotion() {
        byte[] walk = AgentMovementPacketFactory.walkMoveBody(100, 200, 128, 200, 7, 4, 160);
        byte[] jump = AgentMovementPacketFactory.jumpMoveBody(100, 200, 128, 160, 7, 4, 180);
        byte[] climb = AgentMovementPacketFactory.climbMoveBody(100, 200, 100, 160, 7, 6, 180);
        byte[] drop = AgentMovementPacketFactory.dropMoveBody(100, 160, 120, 220, 7, 7, 180);

        assertThat(unsignedShort(walk, 0)).isEqualTo(0x29);
        assertThat(walk[11]).isEqualTo((byte) 2);
        assertThat(walk[12]).isEqualTo((byte) 0);
        assertThat(walk[26]).isEqualTo((byte) 1);
        assertThat(unsignedShort(jump, 0)).isEqualTo(0x29);
        assertThat(jump[11]).isEqualTo((byte) 3);
        assertThat(jump[40]).isEqualTo((byte) 0);
        assertThat(unsignedShort(jump, 41)).isEqualTo(128);
        assertThat(unsignedShort(jump, 43)).isEqualTo(160);
        assertThat(unsignedShort(climb, 0)).isEqualTo(0x29);
        assertThat(climb[11]).isEqualTo((byte) 2);
        assertThat(unsignedShort(drop, 0)).isEqualTo(0x29);
        assertThat(drop[11]).isEqualTo((byte) 2);
        assertThat(drop[26]).isEqualTo((byte) 15);
    }

    @Test
    void physicsMovementPacketsUseOneTimedAbsoluteFragmentWithVelocity() {
        byte[] body = AgentMovementPacketFactory.physicsMoveBody(106, 200, 125.0, 0.0, 7, 1, 50);

        assertThat(unsignedShort(body, 0)).isEqualTo(0x29);
        assertThat(body).hasSize(26);
        assertThat(body[11]).isEqualTo((byte) 1);
        assertThat(body[12]).isEqualTo((byte) 0);
        assertThat(unsignedShort(body, 13)).isEqualTo(106);
        assertThat(unsignedShort(body, 15)).isEqualTo(200);
        assertThat(unsignedShort(body, 17)).isEqualTo(125);
        assertThat(unsignedShort(body, 19)).isEqualTo(0);
        assertThat(unsignedShort(body, 21)).isEqualTo(7);
        assertThat(body[23]).isEqualTo((byte) 1);
        assertThat(unsignedShort(body, 24)).isEqualTo(50);
    }

    @Test
    void runtimeUsesPhysicsMovementPacketShapeForContinuousTicks() {
        AgentMapGeometryRepository geometryRepository = new AgentMapGeometryRepository(null) {
            @Override
            AgentMapGeometry load(int mapId) {
                return new AgentMapGeometry(mapId, java.util.List.of(
                        new AgentMapGeometry.Foothold(7, 0, 200, 300, 200, 0, 0, false)
                ), java.util.List.of(), java.util.List.of());
            }
        };
        AgentMovementRuntimeSystem runtime = new AgentMovementRuntimeSystem(geometryRepository, new AgentPhysicsSystem());

        AgentMovementRuntimeSystem.RuntimeStep step = runtime.tick(new AgentMovementRuntimeSystem.RuntimeRequest(
                1,
                1,
                10000,
                100,
                200,
                7,
                1,
                java.util.Optional.empty(),
                AgentPhysicsSystem.MovementIntent.walk(1)
        ));

        assertThat(step.body()[11]).isEqualTo((byte) 2);
        assertThat(step.detail()).extractingByKey("packet").asString().contains("WALK");
    }

    @Test
    void socialAndInventoryPacketsKeepStableOpcodes() {
        assertThat(unsignedShort(AgentActionPacketFactory.generalChatBody("hi", 0), 0)).isEqualTo(0x31);
        assertThat(unsignedShort(AgentActionPacketFactory.faceExpressionBody(5), 0)).isEqualTo(0x33);
        assertThat(unsignedShort(AgentActionPacketFactory.useItemBody(1, 2000000), 0)).isEqualTo(0x48);
        assertThat(unsignedShort(AgentActionPacketFactory.useChairBody(3010000), 0)).isEqualTo(0x2B);
        assertThat(unsignedShort(AgentActionPacketFactory.itemPickupBody(123, 10, 20), 0)).isEqualTo(0xCA);
    }

    @Test
    void npcAndPortalPacketsKeepStableOpcodes() {
        assertThat(unsignedShort(AgentNpcPacketFactory.openNpcBody(77), 0)).isEqualTo(0x3A);
        assertThat(unsignedShort(AgentNpcPacketFactory.continueBody(0, 1, 2), 0)).isEqualTo(0x3C);
        assertThat(unsignedShort(AgentPortalPacketFactory.normalPortalBody("sp"), 0)).isEqualTo(0x26);
        assertThat(unsignedShort(AgentPortalPacketFactory.specialPortalBody("market00"), 0)).isEqualTo(0x64);
    }

    @Test
    void basicCloseRangeAttackPacketKeepsGuardedOpcodeAndTargetShape() {
        byte[] body = AgentCombatPacketFactory.basicCloseRangeAttackBody(1234, 50, 60, 4, 1, 0, 5, 12);

        assertThat(unsignedShort(body, 0)).isEqualTo(0x2C);
        assertThat(body).hasSize(51);
        assertThat(body[3]).isEqualTo((byte) 0x11);
        assertThat(body[16]).isEqualTo((byte) 16);
        assertThat(unsignedInt(body, 25)).isEqualTo(1234);
        assertThat(unsignedShort(body, 33)).isEqualTo(50);
        assertThat(unsignedShort(body, 35)).isEqualTo(60);
        assertThat(unsignedShort(body, 41)).isEqualTo(120);
        assertThat(unsignedInt(body, 43)).isEqualTo(12);
        assertThat(unsignedInt(body, 47)).isZero();
    }

    @Test
    void mobMovementPacketKeepsMoveLifeShapeForControlledMonsters() {
        byte[] body = AgentMobMovementPacketFactory.moveLifeBody(7001, 12, 100, 200, 112, 200, 7, 2, 250, -1);

        assertThat(unsignedShort(body, 0)).isEqualTo(0xBC);
        assertThat(unsignedInt(body, 2)).isEqualTo(7001);
        assertThat(unsignedShort(body, 6)).isEqualTo(12);
        assertThat(body[31]).isEqualTo((byte) 1);
        assertThat(body[32]).isEqualTo((byte) 0);
        assertThat(unsignedShort(body, 33)).isEqualTo(112);
        assertThat(unsignedShort(body, 35)).isEqualTo(200);
        assertThat(unsignedShort(body, 41)).isEqualTo(7);
        assertThat(body[43]).isEqualTo((byte) 2);
        assertThat(unsignedShort(body, 44)).isEqualTo(250);
    }

    private static int unsignedShort(byte[] body, int offset) {
        return (body[offset] & 0xFF) | ((body[offset + 1] & 0xFF) << 8);
    }

    private static long unsignedInt(byte[] body, int offset) {
        return (long) (body[offset] & 0xFF)
                | ((long) (body[offset + 1] & 0xFF) << 8)
                | ((long) (body[offset + 2] & 0xFF) << 16)
                | ((long) (body[offset + 3] & 0xFF) << 24);
    }
}
