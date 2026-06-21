package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

final class AgentCombatPacketFactory {
    private static final int CLOSE_RANGE_ATTACK_RECV_OPCODE = 0x2C;

    private AgentCombatPacketFactory() {
    }

    static byte[] basicCloseRangeAttackBody(int monsterObjectId, int x, int y, int stance, int direction,
                                            int display, int speed, int damage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        int displayAction = display <= 0 ? 16 : display;
        int attackSpeed = speed <= 0 ? 5 : speed;
        writeShort(out, CLOSE_RANGE_ATTACK_RECV_OPCODE);

        // Matches the fields read by AbstractDealDamageHandler.parseDamage for close-range attacks.
        out.write(0);
        out.write(0x11); // one monster, one damage line
        writeInt(out, 0); // basic attack, no skill id
        writeBytes(out, 8);
        out.write(clampByte(displayAction));
        out.write(clampByte(direction));
        out.write(clampByte(stance));
        out.write(0);
        out.write(clampByte(attackSpeed));
        writeInt(out, 0);

        writeInt(out, monsterObjectId);
        writeInt(out, 0);
        writeShort(out, x);
        writeShort(out, y);
        writeShort(out, x);
        writeShort(out, y);
        writeShort(out, 120);
        writeInt(out, Math.max(1, damage));
        writeInt(out, 0);
        return out.toByteArray();
    }

    static Map<String, Object> describeBasicCloseRangeAttack(int monsterObjectId, int x, int y, int stance,
                                                             int direction, int damage) {
        byte[] body = basicCloseRangeAttackBody(monsterObjectId, x, y, stance, direction, 16, 5, damage);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", CLOSE_RANGE_ATTACK_RECV_OPCODE);
        detail.put("opcodeName", "CLOSE_RANGE_ATTACK");
        detail.put("attackType", "BASIC_CLOSE_RANGE");
        detail.put("monsterObjectId", monsterObjectId);
        detail.put("x", x);
        detail.put("y", y);
        detail.put("stance", stance);
        detail.put("direction", direction);
        detail.put("damage", Math.max(1, damage));
        detail.put("bodyLength", body.length);
        detail.put("guard", "AGENT_CLIENT_SEND_COMBAT_PACKETS");
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
