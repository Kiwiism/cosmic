package com.cosmic.agentclient;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class AgentPortalPacketFactory {
    private static final int CHANGE_MAP_RECV_OPCODE = 0x26;
    private static final int CHANGE_MAP_SPECIAL_RECV_OPCODE = 0x64;

    private AgentPortalPacketFactory() {
    }

    static byte[] normalPortalBody(String portalName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + portalName.length());
        writeShort(out, CHANGE_MAP_RECV_OPCODE);

        out.write(0); // regular portal change, not death warp
        writeInt(out, -1); // client uses -1 when entering the current map's named portal
        writeMapleString(out, portalName);
        out.write(0); // unknown client byte consumed by ChangeMapHandler
        out.write(0); // wheel-of-destiny flag
        out.write(0); // GM chase flag
        return out.toByteArray();
    }

    static byte[] specialPortalBody(String portalName) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + portalName.length());
        writeShort(out, CHANGE_MAP_SPECIAL_RECV_OPCODE);

        out.write(0); // client action marker consumed by ChangeMapSpecialHandler
        writeMapleString(out, portalName);
        writeShort(out, 0); // client footer consumed by ChangeMapSpecialHandler
        return out.toByteArray();
    }

    static Map<String, Object> describeNormalPortal(String portalName) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", CHANGE_MAP_RECV_OPCODE);
        detail.put("opcodeName", "CHANGE_MAP");
        detail.put("portalName", portalName);
        detail.put("bodyLength", normalPortalBody(portalName).length);
        detail.put("shape", "regular portal packet: byte 0, target map -1, portal name, footer flags");
        return detail;
    }

    static Map<String, Object> describeSpecialPortal(String portalName) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("opcode", CHANGE_MAP_SPECIAL_RECV_OPCODE);
        detail.put("opcodeName", "CHANGE_MAP_SPECIAL");
        detail.put("portalName", portalName);
        detail.put("bodyLength", specialPortalBody(portalName).length);
        detail.put("shape", "special portal packet: byte 0, portal name, short 0");
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
}
