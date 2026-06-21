package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class AgentMobMovementPacketFactory {
    private static final int MOVE_LIFE_RECV_OPCODE = 0xBC;
    private static final int COMMAND_ABSOLUTE = 0;

    private AgentMobMovementPacketFactory() {
    }

    static byte[] moveLifeBody(int objectId, int moveId, int fromX, int fromY, int toX, int toY, int foothold,
                               int stance, int durationMillis, int rawActivity) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeShort(out, MOVE_LIFE_RECV_OPCODE);
        writeInt(out, objectId);
        writeShort(out, moveId);
        out.write(0); // pNibbles
        out.write(rawActivity < 0 ? 0xFF : clampByte(rawActivity));
        out.write(0); // skill id
        out.write(0); // skill level
        writeShort(out, 0); // pOption
        writeBytes(out, 8);
        out.write(0);
        writeInt(out, 0);
        writeShort(out, fromX);
        writeShort(out, fromY);

        out.write(1); // one movement fragment
        out.write(COMMAND_ABSOLUTE);
        writeShort(out, toX);
        writeShort(out, toY);
        writeShort(out, toX - fromX);
        writeShort(out, 0);
        writeShort(out, foothold);
        out.write(clampByte(stance));
        writeShort(out, durationMillis);
        return out.toByteArray();
    }

    static Map<String, Object> describeMoveLife(int objectId, int moveId, int fromX, int fromY, int toX, int toY,
                                                int foothold, int stance, int durationMillis) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", MOVE_LIFE_RECV_OPCODE);
        detail.put("opcodeName", "MOVE_LIFE");
        detail.put("objectId", objectId);
        detail.put("moveId", moveId);
        detail.put("fromX", fromX);
        detail.put("fromY", fromY);
        detail.put("x", toX);
        detail.put("y", toY);
        detail.put("foothold", foothold);
        detail.put("stance", stance);
        detail.put("durationMillis", durationMillis);
        detail.put("bodyLength", moveLifeBody(objectId, moveId, fromX, fromY, toX, toY, foothold, stance,
                durationMillis, -1).length);
        detail.put("guard", "AGENT_CLIENT_SEND_MOB_MOVEMENT_PACKETS");
        return detail;
    }

    private static void writeBytes(ByteArrayOutputStream out, int count) {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        int clamped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        out.write(clamped & 0xFF);
        out.write((clamped >>> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static int clampByte(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 255);
    }
}
