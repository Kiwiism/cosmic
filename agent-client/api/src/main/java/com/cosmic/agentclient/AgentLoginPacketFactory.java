package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds v83 client packet bodies for the external headless-client runtime.
 * Encryption and socket lifecycle are intentionally handled by the virtual client transport layer.
 */
final class AgentLoginPacketFactory {
    private static final int LOGIN_PASSWORD_OPCODE = 0x01;
    private static final int CHARLIST_REQUEST_OPCODE = 0x05;
    private static final int SERVERLIST_REQUEST_OPCODE = 0x0B;
    private static final int CHAR_SELECT_OPCODE = 0x13;
    private static final int PLAYER_LOGGEDIN_OPCODE = 0x14;
    private static final int PONG_OPCODE = 0x18;
    private static final byte[] DEFAULT_HWID_NIBBLES = new byte[]{0x00, 0x00, 0x00, 0x00};
    private static final String DEFAULT_MACS = "000000000000";
    private static final String DEFAULT_HOST_STRING = DEFAULT_MACS + "_00000000";

    private AgentLoginPacketFactory() {
    }

    static byte[] loginPasswordBody(String accountName, String password) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, LOGIN_PASSWORD_OPCODE);
        writeString(out, accountName);
        writeString(out, password);
        out.writeBytes(new byte[6]);
        out.writeBytes(DEFAULT_HWID_NIBBLES);
        return out.toByteArray();
    }

    static Map<String, Object> describeLoginPassword(String accountName, String password) {
        byte[] packet = loginPasswordBody(accountName, password);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("opcode", LOGIN_PASSWORD_OPCODE);
        details.put("opcodeName", "LOGIN_PASSWORD");
        details.put("accountName", accountName);
        details.put("bodyLength", packet.length);
        details.put("passwordPresent", password != null && !password.isBlank());
        details.put("hwidNibblesLength", DEFAULT_HWID_NIBBLES.length);
        details.put("next", "encrypt body with v83 Maple AES/OFB and send on login socket");
        return details;
    }

    static byte[] serverListRequestBody() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, SERVERLIST_REQUEST_OPCODE);
        return out.toByteArray();
    }

    static byte[] charListRequestBody(int world, int channel) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, CHARLIST_REQUEST_OPCODE);
        out.write(0);
        out.write(world & 0xFF);
        out.write(Math.max(0, channel - 1) & 0xFF);
        return out.toByteArray();
    }

    static byte[] charSelectBody(int characterId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, CHAR_SELECT_OPCODE);
        writeInt(out, characterId);
        writeString(out, DEFAULT_MACS);
        writeString(out, DEFAULT_HOST_STRING);
        return out.toByteArray();
    }

    static byte[] playerLoggedInBody(int characterId) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, PLAYER_LOGGEDIN_OPCODE);
        writeInt(out, characterId);
        return out.toByteArray();
    }

    static byte[] pongBody() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeShort(out, PONG_OPCODE);
        return out.toByteArray();
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

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.US_ASCII);
        writeShort(out, bytes.length);
        out.writeBytes(bytes);
    }
}
