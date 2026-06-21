package com.cosmic.agentclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class AgentChannelPacketObserver {
    private static final int SET_FIELD = 0x7D;
    private static final int SPAWN_PLAYER = 0xA0;
    private static final int REMOVE_PLAYER_FROM_MAP = 0xA1;
    private static final int CHATTEXT = 0xA2;
    private static final int MOVE_PLAYER = 0xB9;
    private static final int SPAWN_MONSTER = 0xEC;
    private static final int KILL_MONSTER = 0xED;
    private static final int SPAWN_MONSTER_CONTROL = 0xEE;
    private static final int MOVE_MONSTER = 0xEF;
    private static final int SPAWN_NPC = 0x101;
    private static final int REMOVE_NPC = 0x102;
    private static final int SPAWN_NPC_REQUEST_CONTROLLER = 0x103;
    private static final int DROP_ITEM_FROM_MAPOBJECT = 0x10C;
    private static final int REMOVE_ITEM_FROM_MAP = 0x10D;
    private static final int NPC_TALK = 0x130;
    private static final int PING = 0x11;

    private AgentChannelPacketObserver() {
    }

    static Optional<Map<String, Object>> observe(byte[] packet, int characterId) {
        if (packet.length < 2) {
            return Optional.empty();
        }
        int opcode = unsignedShortLE(packet, 0);
        return switch (opcode) {
            case SET_FIELD -> observeSetField(packet);
            case SPAWN_PLAYER -> observeSpawnPlayer(packet, characterId);
            case REMOVE_PLAYER_FROM_MAP -> observeRemovePlayer(packet);
            case CHATTEXT -> observeChatText(packet, characterId);
            case MOVE_PLAYER -> observeMovePlayer(packet, characterId);
            case SPAWN_MONSTER -> observeMonsterSpawn(packet, false);
            case SPAWN_MONSTER_CONTROL -> observeMonsterControl(packet);
            case KILL_MONSTER -> observeKillMonster(packet);
            case MOVE_MONSTER -> observeMoveMonster(packet);
            case SPAWN_NPC -> observeNpcSpawn(packet, false);
            case REMOVE_NPC -> observeRemoveNpc(packet);
            case SPAWN_NPC_REQUEST_CONTROLLER -> observeNpcController(packet);
            case DROP_ITEM_FROM_MAPOBJECT -> observeDrop(packet);
            case REMOVE_ITEM_FROM_MAP -> observeRemoveDrop(packet);
            case NPC_TALK -> observeNpcTalk(packet);
            case PING -> Optional.of(event("PING"));
            default -> Optional.empty();
        };
    }

    private static Optional<Map<String, Object>> observeSetField(byte[] packet) {
        if (packet.length < 11) {
            return Optional.empty();
        }

        int channelZeroBased = readIntLE(packet, 2);
        int mode = unsignedByte(packet, 6);
        Map<String, Object> event = event("SET_FIELD");
        event.put("channel", channelZeroBased + 1);
        event.put("mode", mode);

        // PacketCreator#getWarpToMap writes: int channel, int 0, byte 0, int map, byte spawn.
        // PacketCreator#getCharInfo writes a different mode marker; it includes the full character blob.
        if (packet.length >= 19 && mode == 0 && readIntLE(packet, 6) == 0) {
            event.put("kind", "WARP_TO_MAP");
            event.put("mapId", readIntLE(packet, 11));
            event.put("spawnPoint", unsignedByte(packet, 15));
            event.put("hp", unsignedShortLE(packet, 16));
            event.put("hasExplicitPosition", unsignedByte(packet, 18) != 0);
            if (packet.length >= 27 && Boolean.TRUE.equals(event.get("hasExplicitPosition"))) {
                event.put("x", readIntLE(packet, 19));
                event.put("y", readIntLE(packet, 23));
            }
        } else {
            event.put("kind", "CHARACTER_FIELD");
            event.put("detail", "Full character field packet observed; map position is parsed from warp packets or runtime state.");
        }
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeSpawnPlayer(byte[] packet, int characterId) {
        Cursor cursor = new Cursor(packet, 2);
        if (!cursor.has(6)) {
            return Optional.empty();
        }
        int spawnedCharacterId = cursor.intLE();
        int level = cursor.ubyte();
        String name = cursor.string();
        Map<String, Object> event = event("PLAYER_VISIBLE");
        event.put("characterId", spawnedCharacterId);
        event.put("self", spawnedCharacterId == characterId);
        event.put("level", level);
        event.put("name", name);
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeRemovePlayer(byte[] packet) {
        if (packet.length < 6) {
            return Optional.empty();
        }
        Map<String, Object> event = event("PLAYER_REMOVED");
        event.put("characterId", readIntLE(packet, 2));
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeChatText(byte[] packet, int characterId) {
        Cursor cursor = new Cursor(packet, 2);
        if (!cursor.has(6)) {
            return Optional.empty();
        }
        int speakerCharacterId = cursor.intLE();
        boolean gm = cursor.bool();
        String text = cursor.string();
        Map<String, Object> event = event("MAP_CHAT");
        event.put("characterId", speakerCharacterId);
        event.put("self", speakerCharacterId == characterId);
        event.put("gm", gm);
        event.put("text", text);
        if (cursor.has(1)) {
            event.put("showType", cursor.ubyte());
        }
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeMovePlayer(byte[] packet, int characterId) {
        if (packet.length < 11) {
            return Optional.empty();
        }
        int movedCharacterId = readIntLE(packet, 2);
        Map<String, Object> event = event("MOVE_PLAYER");
        event.put("characterId", movedCharacterId);
        event.put("self", movedCharacterId == characterId);
        Map<String, Object> movement = parseFirstMovementFragment(packet, 10);
        if (!movement.isEmpty()) {
            event.put("movement", movement);
        }
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeMonsterSpawn(byte[] packet, boolean hasControllerByte) {
        Cursor cursor = new Cursor(packet, 2);
        Map<String, Object> event = event("MONSTER_VISIBLE");
        if (hasControllerByte) {
            if (!cursor.has(1)) {
                return Optional.empty();
            }
            event.put("controllerMode", cursor.ubyte());
        }
        if (!cursor.has(27)) {
            return Optional.empty();
        }
        event.put("objectId", cursor.intLE());
        event.put("controllerState", cursor.ubyte());
        event.put("mobId", cursor.intLE());
        if (hasControllerByte) {
            skipTemporary(cursor);
        } else {
            cursor.skip(16);
        }
        if (!cursor.has(9)) {
            return Optional.of(event);
        }
        event.put("x", cursor.shortLE());
        event.put("y", cursor.shortLE());
        event.put("stance", cursor.ubyte());
        event.put("originFoothold", cursor.shortLE());
        event.put("foothold", cursor.shortLE());
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeMonsterControl(byte[] packet) {
        if (packet.length >= 7 && unsignedByte(packet, 2) == 0) {
            Map<String, Object> event = event("MONSTER_REMOVED");
            event.put("objectId", readIntLE(packet, 3));
            event.put("controlOnly", true);
            return Optional.of(event);
        }
        return observeMonsterSpawn(packet, true);
    }

    private static Optional<Map<String, Object>> observeKillMonster(byte[] packet) {
        if (packet.length < 7) {
            return Optional.empty();
        }
        Map<String, Object> event = event("MONSTER_REMOVED");
        event.put("objectId", readIntLE(packet, 2));
        event.put("animation", unsignedByte(packet, 6));
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeMoveMonster(byte[] packet) {
        if (packet.length < 19) {
            return Optional.empty();
        }
        Map<String, Object> event = event("MONSTER_MOVED");
        event.put("objectId", readIntLE(packet, 2));
        event.put("x", readShortLE(packet, 12));
        event.put("y", readShortLE(packet, 14));
        Map<String, Object> movement = parseFirstMovementFragment(packet, 16);
        if (!movement.isEmpty()) {
            event.put("movement", movement);
            if (movement.get("x") instanceof Number x && movement.get("y") instanceof Number y) {
                event.put("x", x.intValue());
                event.put("y", y.intValue());
            }
        }
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeNpcSpawn(byte[] packet, boolean hasControllerByte) {
        Cursor cursor = new Cursor(packet, 2);
        Map<String, Object> event = event("NPC_VISIBLE");
        if (hasControllerByte) {
            if (!cursor.has(1)) {
                return Optional.empty();
            }
            int mode = cursor.ubyte();
            event.put("controllerMode", mode);
            if (mode == 0 && cursor.has(4)) {
                event.put("event", "NPC_REMOVED");
                event.put("objectId", cursor.intLE());
                return Optional.of(event);
            }
        }
        if (!cursor.has(19)) {
            return Optional.empty();
        }
        event.put("objectId", cursor.intLE());
        event.put("npcId", cursor.intLE());
        event.put("x", cursor.shortLE());
        event.put("y", cursor.shortLE());
        event.put("facingLeft", cursor.bool());
        event.put("foothold", cursor.shortLE());
        event.put("rx0", cursor.shortLE());
        event.put("rx1", cursor.shortLE());
        if (cursor.has(1)) {
            event.put("minimap", cursor.bool());
        }
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeNpcController(byte[] packet) {
        return observeNpcSpawn(packet, true);
    }

    private static Optional<Map<String, Object>> observeRemoveNpc(byte[] packet) {
        if (packet.length < 6) {
            return Optional.empty();
        }
        Map<String, Object> event = event("NPC_REMOVED");
        event.put("objectId", readIntLE(packet, 2));
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeNpcTalk(byte[] packet) {
        Map<String, Object> event = event("NPC_TALK");
        event.put("rawLength", packet.length);
        if (packet.length >= 3) {
            event.put("speakerType", unsignedByte(packet, 2));
        }
        if (packet.length >= 7) {
            event.put("npcId", readIntLE(packet, 3));
        }
        if (packet.length >= 8) {
            event.put("messageType", unsignedByte(packet, 7));
        }
        if (packet.length >= 9) {
            event.put("speaker", unsignedByte(packet, 8));
        }
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeDrop(byte[] packet) {
        Cursor cursor = new Cursor(packet, 2);
        if (!cursor.has(22)) {
            return Optional.empty();
        }
        Map<String, Object> event = event("DROP_VISIBLE");
        event.put("mode", cursor.ubyte());
        event.put("objectId", cursor.intLE());
        event.put("meso", cursor.bool());
        event.put("itemId", cursor.intLE());
        event.put("ownerId", cursor.intLE());
        event.put("dropType", cursor.ubyte());
        event.put("x", cursor.shortLE());
        event.put("y", cursor.shortLE());
        event.put("dropperObjectId", cursor.intLE());
        return Optional.of(event);
    }

    private static Optional<Map<String, Object>> observeRemoveDrop(byte[] packet) {
        if (packet.length < 7) {
            return Optional.empty();
        }
        Map<String, Object> event = event("DROP_REMOVED");
        event.put("animation", unsignedByte(packet, 2));
        event.put("objectId", readIntLE(packet, 3));
        if (packet.length >= 11) {
            event.put("characterId", readIntLE(packet, 7));
        }
        return Optional.of(event);
    }

    private static Map<String, Object> parseFirstMovementFragment(byte[] packet, int offset) {
        Map<String, Object> movement = new LinkedHashMap<>();
        if (packet.length <= offset + 1) {
            return movement;
        }
        int fragmentCount = unsignedByte(packet, offset);
        int command = unsignedByte(packet, offset + 1);
        movement.put("fragmentCount", fragmentCount);
        movement.put("firstCommand", command);
        if (fragmentCount > 0 && command == 0 && packet.length >= offset + 16) {
            movement.put("x", readShortLE(packet, offset + 2));
            movement.put("y", readShortLE(packet, offset + 4));
            movement.put("xVelocity", readShortLE(packet, offset + 6));
            movement.put("yVelocity", readShortLE(packet, offset + 8));
            movement.put("foothold", readShortLE(packet, offset + 10));
            movement.put("stance", unsignedByte(packet, offset + 12));
            movement.put("durationMillis", unsignedShortLE(packet, offset + 13));
        }
        return movement;
    }

    private static Map<String, Object> event(String event) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("event", event);
        return result;
    }

    private static void skipTemporary(Cursor cursor) {
        // Monster temporary status encoding is variable. For perception we only need
        // stable object identity, so stop parsing if the temporary block is not small.
        if (cursor.has(18)) {
            cursor.skip(18);
        }
    }

    private static int readIntLE(byte[] bytes, int offset) {
        return unsignedByte(bytes, offset)
                | (unsignedByte(bytes, offset + 1) << 8)
                | (unsignedByte(bytes, offset + 2) << 16)
                | (unsignedByte(bytes, offset + 3) << 24);
    }

    private static int unsignedShortLE(byte[] bytes, int offset) {
        return unsignedByte(bytes, offset) | (unsignedByte(bytes, offset + 1) << 8);
    }

    private static int readShortLE(byte[] bytes, int offset) {
        int value = unsignedShortLE(bytes, offset);
        return value > Short.MAX_VALUE ? value - 65_536 : value;
    }

    private static int unsignedByte(byte[] bytes, int offset) {
        return bytes[offset] & 0xFF;
    }

    private static final class Cursor {
        private final byte[] bytes;
        private int offset;

        private Cursor(byte[] bytes, int offset) {
            this.bytes = bytes;
            this.offset = offset;
        }

        private boolean has(int length) {
            return offset >= 0 && length >= 0 && offset + length <= bytes.length;
        }

        private void skip(int length) {
            offset = Math.min(bytes.length, offset + Math.max(0, length));
        }

        private int ubyte() {
            int value = unsignedByte(bytes, offset);
            offset += 1;
            return value;
        }

        private boolean bool() {
            return ubyte() != 0;
        }

        private int shortLE() {
            int value = readShortLE(bytes, offset);
            offset += 2;
            return value;
        }

        private int intLE() {
            int value = readIntLE(bytes, offset);
            offset += 4;
            return value;
        }

        private String string() {
            if (!has(2)) {
                return "";
            }
            int length = unsignedShortLE(bytes, offset);
            offset += 2;
            if (!has(length)) {
                offset = bytes.length;
                return "";
            }
            String value = new String(bytes, offset, length, java.nio.charset.StandardCharsets.UTF_8);
            offset += length;
            return value;
        }
    }
}
