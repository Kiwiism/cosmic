package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds v83 NPC interaction packet bodies for the external headless-client runtime.
 * These packets mirror what the client sends; the virtual-client transport layer owns encryption.
 */
final class AgentNpcPacketFactory {
    private static final int NPC_TALK_RECV_OPCODE = 0x3A;
    private static final int NPC_TALK_MORE_RECV_OPCODE = 0x3C;

    private AgentNpcPacketFactory() {
    }

    static byte[] openNpcBody(int objectId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(6);
        writeShort(out, NPC_TALK_RECV_OPCODE);
        writeInt(out, objectId);
        return out.toByteArray();
    }

    static byte[] continueBody(int lastMessageType, int action, int selection) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeShort(out, NPC_TALK_MORE_RECV_OPCODE);
        out.write(clampByte(lastMessageType));
        out.write(clampByte(action));
        writeInt(out, selection);
        return out.toByteArray();
    }

    static byte[] textInputBody(int lastMessageType, int action, String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + (text == null ? 0 : text.length()));
        writeShort(out, NPC_TALK_MORE_RECV_OPCODE);
        out.write(clampByte(lastMessageType));
        out.write(clampByte(action));
        writeMapleString(out, text == null ? "" : text);
        return out.toByteArray();
    }

    static Map<String, Object> describeOpenNpc(int objectId, Integer npcId) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", NPC_TALK_RECV_OPCODE);
        detail.put("opcodeName", "NPC_TALK");
        detail.put("objectId", objectId);
        if (npcId != null) {
            detail.put("npcId", npcId);
        }
        detail.put("bodyLength", openNpcBody(objectId).length);
        detail.put("shape", "open NPC packet: int visible map object id");
        return detail;
    }

    static Map<String, Object> describeContinue(int lastMessageType, int action, int selection) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", NPC_TALK_MORE_RECV_OPCODE);
        detail.put("opcodeName", "NPC_TALK_MORE");
        detail.put("lastMessageType", clampByte(lastMessageType));
        detail.put("action", clampByte(action));
        detail.put("selection", selection);
        detail.put("bodyLength", continueBody(lastMessageType, action, selection).length);
        detail.put("shape", "continue NPC packet: byte lastMsg, byte action, int selection");
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
