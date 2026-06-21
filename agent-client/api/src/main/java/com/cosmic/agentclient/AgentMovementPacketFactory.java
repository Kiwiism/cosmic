package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class AgentMovementPacketFactory {
    private static final int MOVE_PLAYER_RECV_OPCODE = 0x29;
    private static final int COMMAND_ABSOLUTE = 0;
    private static final int COMMAND_RELATIVE_WALK = 1;
    private static final int COMMAND_JUMP_DOWN = 15;

    private AgentMovementPacketFactory() {
    }

    static byte[] absoluteMoveBody(int x, int y, int foothold, int stance, int durationMillis) {
        ByteArrayOutputStream out = movementPrelude(1);
        writeAbsoluteFragment(out, x, y, 0, 0, foothold, stance, durationMillis);
        return out.toByteArray();
    }

    static byte[] physicsMoveBody(int x, int y, double velocityX, double velocityY, int foothold, int stance,
                                  int durationMillis) {
        ByteArrayOutputStream out = movementPrelude(1);
        writeAbsoluteFragment(out, x, y, velocityComponent(velocityX), velocityComponent(velocityY), foothold, stance,
                durationMillis);
        return out.toByteArray();
    }

    static byte[] walkMoveBody(int fromX, int fromY, int toX, int toY, int foothold, int stance, int durationMillis) {
        ByteArrayOutputStream out = movementPrelude(2);
        writeAbsoluteFragment(out, fromX, fromY, 0, 0, foothold, stance, Math.max(20, durationMillis / 3));
        writeRelativeFragment(out, toX - fromX, toY - fromY, stance, durationMillis);
        return out.toByteArray();
    }

    static byte[] jumpMoveBody(int fromX, int fromY, int toX, int toY, int foothold, int stance, int durationMillis) {
        ByteArrayOutputStream out = movementPrelude(3);
        int firstDuration = Math.max(40, durationMillis / 3);
        int secondDuration = Math.max(40, durationMillis / 3);
        int landingDuration = Math.max(40, durationMillis - firstDuration - secondDuration);
        int peakX = fromX + ((toX - fromX) / 2);
        int peakY = Math.min(fromY, toY) - 34;
        writeAbsoluteFragment(out, fromX, fromY, 0, -260, foothold, stance, firstDuration);
        writeAbsoluteFragment(out, peakX, peakY, toX - fromX, -180, foothold, stance, secondDuration);
        writeAbsoluteFragment(out, toX, toY, 0, 0, foothold, stance, landingDuration);
        return out.toByteArray();
    }

    static byte[] climbMoveBody(int fromX, int fromY, int toX, int toY, int foothold, int stance, int durationMillis) {
        ByteArrayOutputStream out = movementPrelude(2);
        writeAbsoluteFragment(out, fromX, fromY, 0, 0, foothold, stance, Math.max(20, durationMillis / 3));
        writeRelativeFragment(out, toX - fromX, toY - fromY, stance, durationMillis);
        return out.toByteArray();
    }

    static byte[] dropMoveBody(int fromX, int fromY, int toX, int toY, int foothold, int stance, int durationMillis) {
        ByteArrayOutputStream out = movementPrelude(2);
        writeAbsoluteFragment(out, fromX, fromY, 0, 120, foothold, stance, Math.max(20, durationMillis / 3));
        out.write(COMMAND_JUMP_DOWN);
        writeShort(out, toX);
        writeShort(out, toY);
        writeShort(out, 0);
        writeShort(out, 180);
        writeShort(out, foothold);
        writeShort(out, foothold);
        out.write(clampByte(stance));
        writeShort(out, durationMillis);
        return out.toByteArray();
    }

    static byte[] navigationMoveBody(String mode, int fromX, int fromY, int toX, int toY, int foothold,
                                     int stance, int durationMillis) {
        String normalizedMode = mode == null ? "" : mode.toUpperCase();
        if (normalizedMode.contains("CLIMB")) {
            return climbMoveBody(fromX, fromY, toX, toY, foothold, stance, durationMillis);
        }
        if (normalizedMode.contains("JUMP")) {
            return jumpMoveBody(fromX, fromY, toX, toY, foothold, stance, durationMillis);
        }
        if (normalizedMode.contains("DROP")) {
            return dropMoveBody(fromX, fromY, toX, toY, foothold, stance, durationMillis);
        }
        if (Math.abs(toX - fromX) > 3 || Math.abs(toY - fromY) > 3) {
            return walkMoveBody(fromX, fromY, toX, toY, foothold, stance, durationMillis);
        }
        return absoluteMoveBody(toX, toY, foothold, stance, durationMillis);
    }

    static Map<String, Object> describeNavigationMove(String mode, int fromX, int fromY, int toX, int toY,
                                                      int foothold, int stance, int durationMillis) {
        Map<String, Object> detail = describeAbsoluteMove(toX, toY, foothold, stance, durationMillis);
        detail.put("fragmentType", movementKind(mode, fromX, fromY, toX, toY));
        detail.put("fromX", clampShort(fromX));
        detail.put("fromY", clampShort(fromY));
        detail.put("mode", mode);
        detail.put("deltaX", clampShort(toX - fromX));
        detail.put("deltaY", clampShort(toY - fromY));
        detail.put("bodyLength", navigationMoveBody(mode, fromX, fromY, toX, toY, foothold, stance, durationMillis).length);
        return detail;
    }

    static Map<String, Object> describePhysicsMove(String mode, int fromX, int fromY, int toX, int toY,
                                                   double velocityX, double velocityY, int foothold, int stance,
                                                   int durationMillis) {
        Map<String, Object> detail = describeAbsoluteMove(toX, toY, foothold, stance, durationMillis);
        detail.put("fragmentType", "PHYSICS_ABSOLUTE");
        detail.put("fromX", clampShort(fromX));
        detail.put("fromY", clampShort(fromY));
        detail.put("mode", mode);
        detail.put("deltaX", clampShort(toX - fromX));
        detail.put("deltaY", clampShort(toY - fromY));
        detail.put("velocityX", velocityComponent(velocityX));
        detail.put("velocityY", velocityComponent(velocityY));
        detail.put("bodyLength", physicsMoveBody(toX, toY, velocityX, velocityY, foothold, stance, durationMillis).length);
        return detail;
    }

    private static ByteArrayOutputStream movementPrelude(int fragmentCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(48);
        writeShort(out, MOVE_PLAYER_RECV_OPCODE);

        // MovePlayerHandler skips this client prelude before reading movement fragments.
        for (int i = 0; i < 9; i++) {
            out.write(0);
        }
        out.write(clampByte(fragmentCount));
        return out;
    }

    private static void writeAbsoluteFragment(ByteArrayOutputStream out, int x, int y, int xVelocity, int yVelocity,
                                              int foothold, int stance, int durationMillis) {
        out.write(COMMAND_ABSOLUTE);
        writeShort(out, x);
        writeShort(out, y);
        writeShort(out, xVelocity);
        writeShort(out, yVelocity);
        writeShort(out, foothold);
        out.write(clampByte(stance));
        writeShort(out, durationMillis);
    }

    private static void writeRelativeFragment(ByteArrayOutputStream out, int deltaX, int deltaY, int stance,
                                              int durationMillis) {
        out.write(COMMAND_RELATIVE_WALK);
        writeShort(out, deltaX);
        writeShort(out, deltaY);
        out.write(clampByte(stance));
        writeShort(out, durationMillis);
    }

    static Map<String, Object> describeAbsoluteMove(int x, int y, int foothold, int stance, int durationMillis) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", MOVE_PLAYER_RECV_OPCODE);
        detail.put("opcodeName", "MOVE_PLAYER");
        detail.put("fragmentType", "ABSOLUTE");
        detail.put("x", clampShort(x));
        detail.put("y", clampShort(y));
        detail.put("foothold", clampShort(foothold));
        detail.put("stance", clampByte(stance));
        detail.put("durationMillis", clampShort(durationMillis));
        detail.put("bodyLength", absoluteMoveBody(x, y, foothold, stance, durationMillis).length);
        return detail;
    }

    private static String movementKind(String mode, int fromX, int fromY, int toX, int toY) {
        String normalizedMode = mode == null ? "" : mode.toUpperCase();
        if (normalizedMode.contains("CLIMB")) {
            return "CLIMB";
        }
        if (normalizedMode.contains("JUMP")) {
            return "JUMP";
        }
        if (normalizedMode.contains("DROP")) {
            return "DROP";
        }
        if (Math.abs(toX - fromX) > 3 || Math.abs(toY - fromY) > 3) {
            return "WALK";
        }
        return "ABSOLUTE";
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        int clamped = clampShort(value);
        out.write(clamped & 0xFF);
        out.write((clamped >>> 8) & 0xFF);
    }

    private static int clampShort(int value) {
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return Math.min(value, Short.MAX_VALUE);
    }

    private static int clampByte(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }

    private static int velocityComponent(double value) {
        return clampShort((int) Math.round(value));
    }
}
