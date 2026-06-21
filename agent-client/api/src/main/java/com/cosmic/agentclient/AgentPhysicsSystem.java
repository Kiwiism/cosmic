package com.cosmic.agentclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class AgentPhysicsSystem {
    static final int TICK_MILLIS = 50;
    private static final double TICK_SECONDS = TICK_MILLIS / 1000.0;
    private static final int WALK_SPEED_PIXELS_PER_SECOND = 125;
    private static final double GRAVITY_PIXELS_PER_SECOND_SQUARED = 2000.0;
    private static final double JUMP_SPEED_PIXELS_PER_SECOND = 555.0;
    private static final double DOWN_JUMP_SPEED_PIXELS_PER_SECOND = 196.0;
    private static final double MAX_FALL_PIXELS_PER_SECOND = 670.0;
    private static final int CLIMB_SPEED_PIXELS_PER_SECOND = 100;
    private static final int MAX_GROUNDED_STEP_PIXELS = 12;
    private static final int MAX_UNCONTROLLED_FALL_PIXELS = 600;
    private static final int MID_AIR_SAFE_RECOVERY_PIXELS = 240;
    private static final int PROBE_STEP_PIXELS = Math.max(1, (int) Math.round(WALK_SPEED_PIXELS_PER_SECOND * TICK_SECONDS));
    private static final int PROBE_RANGE_PIXELS = 48;
    private static final int STANCE_STAND_RIGHT = 4;
    private static final int STANCE_STAND_LEFT = 5;
    private static final int STANCE_WALK_RIGHT = 2;
    private static final int STANCE_WALK_LEFT = 3;
    private static final int STANCE_JUMP_RIGHT = 6;
    private static final int STANCE_JUMP_LEFT = 7;
    private static final int STANCE_PRONE_RIGHT = 10;
    private static final int STANCE_PRONE_LEFT = 11;
    private static final int STANCE_CLIMB = 16;

    PhysicsStep tick(AgentMapGeometry geometry, MotionState state, MovementIntent intent) {
        MotionState normalized = snapInitialGround(geometry, state);
        PhysicsStep step = switch (normalized.mode()) {
            case CLIMBING -> tickClimbing(geometry, normalized, intent);
            case AIRBORNE -> tickAirborne(geometry, normalized, intent);
            case GROUNDED -> tickGrounded(geometry, normalized, intent);
        };
        return step;
    }

    MotionState snapToGround(AgentMapGeometry geometry, int x, int y, int footholdId, int direction) {
        Optional<AgentMapGeometry.Ground> ground = geometry.groundAt(x, y).or(() -> geometry.groundBelow(x, y));
        if (ground.isEmpty()) {
            return new MotionState(MotionMode.AIRBORNE, x, y, 0, 0, Math.max(1, direction), footholdId,
                    jumpStance(direction), null);
        }
        AgentMapGeometry.Ground resolved = ground.get();
        return new MotionState(MotionMode.GROUNDED, x, resolved.y(), 0, 0, Math.max(1, direction),
                resolved.foothold().id(), standStance(direction), null);
    }

    MovementStep nextBoundedProbe(int currentX, int currentY, int originX, int foothold, int direction) {
        int nextDirection = direction == 0 ? 1 : direction;
        int nextX = currentX + (nextDirection * PROBE_STEP_PIXELS);
        if (nextX > originX + PROBE_RANGE_PIXELS) {
            nextDirection = -1;
            nextX = currentX - PROBE_STEP_PIXELS;
        } else if (nextX < originX - PROBE_RANGE_PIXELS) {
            nextDirection = 1;
            nextX = currentX + PROBE_STEP_PIXELS;
        }
        int stance = walkStance(nextDirection);
        return new MovementStep(nextX, currentY, foothold, stance, TICK_MILLIS, nextDirection);
    }

    private MotionState snapInitialGround(AgentMapGeometry geometry, MotionState state) {
        if (state.mode() == MotionMode.AIRBORNE) {
            Optional<AgentMapGeometry.Ground> nearest = geometry.nearestGroundAtX(state.x(), state.y());
            if (nearest.isPresent()) {
                AgentMapGeometry.Ground ground = nearest.get();
                boolean closeToGround = Math.abs(ground.y() - state.y()) <= 4;
                boolean fellFarBelowGround = state.y() - ground.y() > MAX_UNCONTROLLED_FALL_PIXELS;
                if (closeToGround || fellFarBelowGround) {
                    return new MotionState(MotionMode.GROUNDED, state.x(), ground.y(), 0, 0,
                            state.direction(), ground.foothold().id(), standStance(state.direction()), null);
                }
            }
            Optional<AgentMapGeometry.Ground> safe = geometry.nearestSafeGround(state.x(), state.y());
            if (safe.isPresent() && state.y() - safe.get().y() > MAX_UNCONTROLLED_FALL_PIXELS) {
                AgentMapGeometry.Ground ground = safe.get();
                int safeX = Math.max(ground.foothold().minX(), Math.min(ground.foothold().maxX(), state.x()));
                return new MotionState(MotionMode.GROUNDED, safeX, ground.foothold().yAt(safeX), 0, 0,
                        state.direction(), ground.foothold().id(), standStance(state.direction()), null);
            }
            return state;
        }
        if (state.mode() != MotionMode.GROUNDED) {
            return state;
        }
        return geometry.groundAt(state.x(), state.y())
                .map(ground -> state.withPosition(state.x(), ground.y()).withFoothold(ground.foothold().id()))
                .orElseGet(() -> state.withMode(MotionMode.AIRBORNE).withStance(jumpStance(state.direction())));
    }

    private PhysicsStep tickGrounded(AgentMapGeometry geometry, MotionState state, MovementIntent intent) {
        int direction = intent.horizontalDirection() == 0 && (intent.jump() || intent.drop() || intent.climb())
                ? state.direction()
                : intent.horizontalDirection();
        if (intent.climb()) {
            Optional<AgentMapGeometry.LadderRope> ladderRope = geometry.grabbableLadderRope(state.x(), state.y());
            if (ladderRope.isPresent()) {
                AgentMapGeometry.LadderRope rope = ladderRope.get();
                MotionState next = new MotionState(MotionMode.CLIMBING, rope.x(), state.y(), 0, 0, direction,
                        state.footholdId(), climbStance(), rope.index());
                return new PhysicsStep(state, next, "CLIMB_ATTACH", TICK_MILLIS);
            }
        }
        if (intent.jump()) {
            MotionState next = new MotionState(MotionMode.AIRBORNE, state.x(), state.y(),
                    direction * WALK_SPEED_PIXELS_PER_SECOND, -JUMP_SPEED_PIXELS_PER_SECOND, direction,
                    state.footholdId(), jumpStance(direction), null);
            return new PhysicsStep(state, next, "JUMP_START", TICK_MILLIS);
        }
        if (intent.drop()) {
            MotionState next = new MotionState(MotionMode.AIRBORNE, state.x(), state.y() + 2,
                    direction * (WALK_SPEED_PIXELS_PER_SECOND / 2.0), DOWN_JUMP_SPEED_PIXELS_PER_SECOND, direction,
                    state.footholdId(), proneStance(direction), null);
            return new PhysicsStep(state, next, "DROP_START", TICK_MILLIS);
        }
        if (direction == 0) {
            MotionState next = state.withVelocity(0, 0).withStance(standStance(state.direction()));
            return new PhysicsStep(state, next, "GROUND_IDLE", TICK_MILLIS);
        }

        int nextX = state.x() + (direction * PROBE_STEP_PIXELS);
        Optional<AgentMapGeometry.Ground> nextGround = geometry.groundAt(nextX, state.y())
                .filter(ground -> Math.abs(ground.y() - state.y()) <= MAX_GROUNDED_STEP_PIXELS);
        if (nextGround.isEmpty()) {
            MotionState next = new MotionState(MotionMode.AIRBORNE, nextX, state.y(),
                    direction * WALK_SPEED_PIXELS_PER_SECOND, 0, direction, state.footholdId(), jumpStance(direction), null);
            return new PhysicsStep(state, next, "WALK_OFF_EDGE", TICK_MILLIS);
        }
        AgentMapGeometry.Ground ground = nextGround.get();
        MotionState next = new MotionState(MotionMode.GROUNDED, nextX, ground.y(),
                direction * WALK_SPEED_PIXELS_PER_SECOND, 0, direction, ground.foothold().id(),
                walkStance(direction), null);
        return new PhysicsStep(state, next, "GROUND_WALK", TICK_MILLIS);
    }

    private PhysicsStep tickAirborne(AgentMapGeometry geometry, MotionState state, MovementIntent intent) {
        int direction = intent.directionOr(state.direction());
        double vx = direction == 0 ? state.velocityX() : direction * WALK_SPEED_PIXELS_PER_SECOND;
        double vy = Math.min(MAX_FALL_PIXELS_PER_SECOND, state.velocityY() + (GRAVITY_PIXELS_PER_SECOND_SQUARED * TICK_SECONDS));
        int nextX = state.x() + (int) Math.round(vx * TICK_SECONDS);
        int nextY = state.y() + (int) Math.round(vy * TICK_SECONDS);
        if (intent.climb()) {
            Optional<AgentMapGeometry.LadderRope> ladderRope = geometry.grabbableLadderRope(nextX, nextY);
            if (ladderRope.isPresent()) {
                AgentMapGeometry.LadderRope rope = ladderRope.get();
                MotionState next = new MotionState(MotionMode.CLIMBING, rope.x(), nextY, 0, 0, direction,
                        state.footholdId(), climbStance(), rope.index());
                return new PhysicsStep(state, next, "AIR_ROPE_GRAB", TICK_MILLIS);
            }
        }
        if (vy >= 0) {
            Optional<AgentMapGeometry.Ground> landing = geometry.landingBetween(state.x(), state.y(), nextX, nextY);
            if (landing.isPresent()) {
                AgentMapGeometry.Ground ground = landing.get();
                MotionState next = new MotionState(MotionMode.GROUNDED, nextX, ground.y(), 0, 0, direction,
                        ground.foothold().id(), standStance(direction), null);
                return new PhysicsStep(state, next, "AIR_LAND", TICK_MILLIS);
            }
            Optional<AgentMapGeometry.Ground> safe = geometry.nearestSafeGround(nextX, nextY);
            if (safe.isPresent() && nextY - safe.get().y() > MID_AIR_SAFE_RECOVERY_PIXELS) {
                AgentMapGeometry.Ground ground = safe.get();
                int safeX = clamp(nextX, ground.foothold().minX(), ground.foothold().maxX());
                MotionState next = new MotionState(MotionMode.GROUNDED, safeX, ground.foothold().yAt(safeX), 0, 0,
                        direction, ground.foothold().id(), standStance(direction), null);
                return new PhysicsStep(state, next, "AIR_FALL_RECOVERED", TICK_MILLIS);
            }
        }
        MotionState next = new MotionState(MotionMode.AIRBORNE, nextX, nextY, vx, vy, direction,
                state.footholdId(), jumpStance(direction), null);
        return new PhysicsStep(state, next, "AIRBORNE", TICK_MILLIS);
    }

    private PhysicsStep tickClimbing(AgentMapGeometry geometry, MotionState state, MovementIntent intent) {
        Optional<AgentMapGeometry.LadderRope> current = geometry.ladderRopes().stream()
                .filter(ladderRope -> state.ladderRopeIndex() != null && ladderRope.index() == state.ladderRopeIndex())
                .findFirst();
        if (current.isEmpty()) {
            MotionState next = state.withMode(MotionMode.AIRBORNE).withStance(jumpStance(state.direction()));
            return new PhysicsStep(state, next, "CLIMB_LOST_ROPE", TICK_MILLIS);
        }
        AgentMapGeometry.LadderRope rope = current.get();
        int vertical = intent.verticalDirection();
        if (vertical == 0) {
            if (intent.jump() || intent.drop()) {
                MotionState next = new MotionState(MotionMode.AIRBORNE, state.x(), state.y(),
                        state.direction() * WALK_SPEED_PIXELS_PER_SECOND, intent.drop()
                        ? DOWN_JUMP_SPEED_PIXELS_PER_SECOND : -JUMP_SPEED_PIXELS_PER_SECOND,
                        state.direction(), state.footholdId(), intent.drop() ? proneStance(state.direction()) : jumpStance(state.direction()), null);
                return new PhysicsStep(state, next, intent.drop() ? "CLIMB_DROP" : "CLIMB_JUMP", TICK_MILLIS);
            }
            MotionState next = state.withVelocity(0, 0).withStance(climbStance());
            return new PhysicsStep(state, next, "CLIMB_HOLD", TICK_MILLIS);
        }
        int climbStep = Math.max(1, (int) Math.round(CLIMB_SPEED_PIXELS_PER_SECOND * TICK_SECONDS));
        int nextY = state.y() + (vertical * climbStep);
        if (nextY <= rope.topY() || nextY >= rope.bottomY()) {
            int exitY = vertical < 0 ? rope.topY() : rope.bottomY();
            Optional<AgentMapGeometry.Ground> ground = geometry.groundBelow(rope.x(), exitY - 12);
            if (ground.isPresent()) {
                AgentMapGeometry.Ground resolved = ground.get();
                MotionState next = new MotionState(MotionMode.GROUNDED, rope.x(), resolved.y(), 0, 0, state.direction(),
                        resolved.foothold().id(), standStance(state.direction()), null);
                return new PhysicsStep(state, next, "CLIMB_EXIT_GROUND", TICK_MILLIS);
            }
            nextY = Math.max(rope.topY(), Math.min(rope.bottomY(), nextY));
        }
        MotionState next = new MotionState(MotionMode.CLIMBING, rope.x(), nextY, 0,
                vertical * CLIMB_SPEED_PIXELS_PER_SECOND, state.direction(), state.footholdId(), climbStance(),
                rope.index());
        return new PhysicsStep(state, next, vertical < 0 ? "CLIMB_UP" : "CLIMB_DOWN", TICK_MILLIS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int standStance(int direction) {
        return direction < 0 ? STANCE_STAND_LEFT : STANCE_STAND_RIGHT;
    }

    private static int walkStance(int direction) {
        return direction < 0 ? STANCE_WALK_LEFT : STANCE_WALK_RIGHT;
    }

    private static int jumpStance(int direction) {
        return direction < 0 ? STANCE_JUMP_LEFT : STANCE_JUMP_RIGHT;
    }

    private static int proneStance(int direction) {
        return direction < 0 ? STANCE_PRONE_LEFT : STANCE_PRONE_RIGHT;
    }

    private static int climbStance() {
        return STANCE_CLIMB;
    }

    record MovementStep(int x, int y, int foothold, int stance, int durationMillis, int nextDirection) {
        Map<String, Object> describe() {
            Map<String, Object> result = new LinkedHashMap<>(
                    AgentMovementPacketFactory.describeAbsoluteMove(x, y, foothold, stance, durationMillis));
            result.put("physicsMode", "BOUNDED_SPAWN_PROBE");
            result.put("nextDirection", nextDirection);
            return result;
        }
    }

    enum MotionMode {
        GROUNDED,
        AIRBORNE,
        CLIMBING
    }

    record MovementIntent(int horizontalDirection, int verticalDirection, boolean jump, boolean drop, boolean climb) {
        static MovementIntent idle() {
            return new MovementIntent(0, 0, false, false, false);
        }

        static MovementIntent walk(int direction) {
            return new MovementIntent(Integer.compare(direction, 0), 0, false, false, false);
        }

        static MovementIntent jump(int direction) {
            return new MovementIntent(Integer.compare(direction, 0), 0, true, false, false);
        }

        static MovementIntent drop(int direction) {
            return new MovementIntent(Integer.compare(direction, 0), 1, false, true, false);
        }

        static MovementIntent climb(int verticalDirection) {
            return new MovementIntent(0, Integer.compare(verticalDirection, 0), false, false, true);
        }

        int directionOr(int fallback) {
            return horizontalDirection == 0 ? fallback : horizontalDirection;
        }
    }

    record MotionState(MotionMode mode, int x, int y, double velocityX, double velocityY, int direction,
                       int footholdId, int stance, Integer ladderRopeIndex) {
        MotionState withMode(MotionMode mode) {
            return new MotionState(mode, x, y, velocityX, velocityY, direction, footholdId, stance, ladderRopeIndex);
        }

        MotionState withPosition(int x, int y) {
            return new MotionState(mode, x, y, velocityX, velocityY, direction, footholdId, stance, ladderRopeIndex);
        }

        MotionState withVelocity(double velocityX, double velocityY) {
            return new MotionState(mode, x, y, velocityX, velocityY, direction, footholdId, stance, ladderRopeIndex);
        }

        MotionState withFoothold(int footholdId) {
            return new MotionState(mode, x, y, velocityX, velocityY, direction, footholdId, stance, ladderRopeIndex);
        }

        MotionState withStance(int stance) {
            return new MotionState(mode, x, y, velocityX, velocityY, direction, footholdId, stance, ladderRopeIndex);
        }
    }

    record PhysicsStep(MotionState previous, MotionState current, String event, int durationMillis) {
        MovementStep toMovementStep() {
            return new MovementStep(current.x(), current.y(), current.footholdId(), current.stance(), durationMillis,
                    current.direction());
        }

        Map<String, Object> describe() {
            Map<String, Object> result = toMovementStep().describe();
            result.put("physicsMode", current.mode().name());
            result.put("physicsEvent", event);
            result.put("velocityX", current.velocityX());
            result.put("velocityY", current.velocityY());
            result.put("ladderRopeIndex", current.ladderRopeIndex());
            return result;
        }
    }
}
