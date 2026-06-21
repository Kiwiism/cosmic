package com.cosmic.agentclient;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
class AgentMovementRuntimeSystem {
    private final AgentMapGeometryRepository geometryRepository;
    private final AgentPhysicsSystem physicsSystem;

    AgentMovementRuntimeSystem(AgentMapGeometryRepository geometryRepository, AgentPhysicsSystem physicsSystem) {
        this.geometryRepository = geometryRepository;
        this.physicsSystem = physicsSystem;
    }

    RuntimeStep tick(RuntimeRequest request) {
        AgentMapGeometry geometry = geometryRepository.load(request.mapId());
        AgentPhysicsSystem.MotionState start = request.motionState()
                .orElseGet(() -> physicsSystem.snapToGround(
                        geometry,
                        request.x(),
                        request.y(),
                        request.foothold(),
                        request.direction() == 0 ? 1 : request.direction()
                ));
        AgentPhysicsSystem.PhysicsStep physicsStep = physicsSystem.tick(geometry, start, request.intent());
        AgentPhysicsSystem.MotionState current = physicsStep.current();
        byte[] body = AgentMovementPacketFactory.navigationMoveBody(
                physicsStep.event(),
                physicsStep.previous().x(),
                physicsStep.previous().y(),
                current.x(),
                current.y(),
                current.footholdId(),
                current.stance(),
                physicsStep.durationMillis()
        );
        Map<String, Object> packet = AgentMovementPacketFactory.describeNavigationMove(
                physicsStep.event(),
                physicsStep.previous().x(),
                physicsStep.previous().y(),
                current.x(),
                current.y(),
                current.footholdId(),
                current.stance(),
                physicsStep.durationMillis()
        );
        Map<String, Object> detail = new LinkedHashMap<>(physicsStep.describe());
        detail.put("mapId", geometry.mapId());
        detail.put("footholds", geometry.footholds().size());
        detail.put("ladderRopes", geometry.ladderRopes().size());
        detail.put("packet", packet);
        return new RuntimeStep(physicsStep, body, detail);
    }

    AgentPhysicsSystem.MovementIntent manualIntent(String key, int fallbackDirection) {
        return switch (key == null ? "" : key.toUpperCase()) {
            case "LEFT" -> AgentPhysicsSystem.MovementIntent.walk(-1);
            case "RIGHT" -> AgentPhysicsSystem.MovementIntent.walk(1);
            case "UP" -> AgentPhysicsSystem.MovementIntent.climb(-1);
            case "DOWN" -> AgentPhysicsSystem.MovementIntent.climb(1);
            case "JUMP" -> AgentPhysicsSystem.MovementIntent.jump(fallbackDirection == 0 ? 1 : fallbackDirection);
            case "DROP" -> AgentPhysicsSystem.MovementIntent.drop(fallbackDirection);
            default -> AgentPhysicsSystem.MovementIntent.idle();
        };
    }

    record RuntimeRequest(int profileId, int characterId, int mapId, int x, int y, int foothold, int direction,
                          Optional<AgentPhysicsSystem.MotionState> motionState,
                          AgentPhysicsSystem.MovementIntent intent) {
    }

    record RuntimeStep(AgentPhysicsSystem.PhysicsStep physicsStep, byte[] body, Map<String, Object> detail) {
    }
}
