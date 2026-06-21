package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Packet bodies for small, non-login v83 client actions used by the headless agent runtime.
 * Combat packets are intentionally excluded until attack-body calibration is complete.
 */
final class AgentActionPacketFactory {
    private static final int GENERAL_CHAT_RECV_OPCODE = 0x31;
    private static final int FACE_EXPRESSION_RECV_OPCODE = 0x33;
    private static final int USE_ITEM_RECV_OPCODE = 0x48;
    private static final int USE_CHAIR_RECV_OPCODE = 0x2B;
    private static final int ITEM_PICKUP_RECV_OPCODE = 0xCA;

    private AgentActionPacketFactory() {
    }

    static byte[] itemPickupBody(int objectId, int x, int y) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(17);
        writeShort(out, ITEM_PICKUP_RECV_OPCODE);
        writeInt(out, (int) (System.currentTimeMillis() & 0x7FFFFFFF));
        out.write(0);
        writeShort(out, x);
        writeShort(out, y);
        writeInt(out, objectId);
        return out.toByteArray();
    }

    static byte[] generalChatBody(String text, int showType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + (text == null ? 0 : text.length()));
        writeShort(out, GENERAL_CHAT_RECV_OPCODE);
        writeMapleString(out, text == null ? "" : text);
        out.write(clampByte(showType));
        return out.toByteArray();
    }

    static byte[] faceExpressionBody(int emote) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(6);
        writeShort(out, FACE_EXPRESSION_RECV_OPCODE);
        writeInt(out, emote);
        return out.toByteArray();
    }

    static byte[] useItemBody(int slot, int itemId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        writeShort(out, USE_ITEM_RECV_OPCODE);
        writeInt(out, (int) (System.currentTimeMillis() & 0x7FFFFFFF));
        writeShort(out, slot);
        writeInt(out, itemId);
        return out.toByteArray();
    }

    static byte[] useChairBody(int itemId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(6);
        writeShort(out, USE_CHAIR_RECV_OPCODE);
        writeInt(out, itemId);
        return out.toByteArray();
    }

    static Map<String, Object> describeItemPickup(int objectId, int x, int y) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", ITEM_PICKUP_RECV_OPCODE);
        detail.put("opcodeName", "ITEM_PICKUP");
        detail.put("objectId", objectId);
        detail.put("x", x);
        detail.put("y", y);
        detail.put("bodyLength", itemPickupBody(objectId, x, y).length);
        detail.put("shape", "pickup packet: int timestamp, byte 0, pos, int drop object id");
        return detail;
    }

    static Map<String, Object> describeGeneralChat(String text, int showType) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", GENERAL_CHAT_RECV_OPCODE);
        detail.put("opcodeName", "GENERAL_CHAT");
        detail.put("text", text);
        detail.put("showType", clampByte(showType));
        detail.put("bodyLength", generalChatBody(text, showType).length);
        return detail;
    }

    static Map<String, Object> describeFaceExpression(int emote) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", FACE_EXPRESSION_RECV_OPCODE);
        detail.put("opcodeName", "FACE_EXPRESSION");
        detail.put("emote", emote);
        detail.put("bodyLength", faceExpressionBody(emote).length);
        return detail;
    }

    static Map<String, Object> describeUseItem(int slot, int itemId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", USE_ITEM_RECV_OPCODE);
        detail.put("opcodeName", "USE_ITEM");
        detail.put("slot", slot);
        detail.put("itemId", itemId);
        detail.put("bodyLength", useItemBody(slot, itemId).length);
        return detail;
    }

    static Map<String, Object> describeUseChair(int itemId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", USE_CHAIR_RECV_OPCODE);
        detail.put("opcodeName", "USE_CHAIR");
        detail.put("itemId", itemId);
        detail.put("bodyLength", useChairBody(itemId).length);
        return detail;
    }

    private static void writeMapleString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        writeShort(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
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
