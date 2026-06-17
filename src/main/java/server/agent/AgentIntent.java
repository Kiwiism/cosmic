package server.agent;

public record AgentIntent(
        AgentIntentType type,
        String argument,
        long durationMillis
) {
    public static AgentIntent idle(long durationMillis) {
        return new AgentIntent(AgentIntentType.IDLE, null, durationMillis);
    }

    public static AgentIntent waitFor(long durationMillis) {
        return new AgentIntent(AgentIntentType.WAIT, null, durationMillis);
    }

    public static AgentIntent say(String message) {
        return new AgentIntent(AgentIntentType.SAY, message, 0);
    }

    public static AgentIntent roam(String hint) {
        return new AgentIntent(AgentIntentType.ROAM, blankToNull(hint), 0);
    }

    public static AgentIntent move(String destination) {
        return new AgentIntent(AgentIntentType.MOVE, destination, 0);
    }

    public static AgentIntent moveToMap(String mapId) {
        return new AgentIntent(AgentIntentType.MOVE_TO_MAP, mapId, 0);
    }

    public static AgentIntent followCharacter(String characterIdOrName) {
        return new AgentIntent(AgentIntentType.FOLLOW_CHARACTER, blankToNull(characterIdOrName), 0);
    }

    public static AgentIntent usePortal(String portalName) {
        return new AgentIntent(AgentIntentType.USE_PORTAL, blankToNull(portalName), 0);
    }

    public static AgentIntent attack(String target) {
        return new AgentIntent(AgentIntentType.ATTACK, blankToNull(target), 0);
    }

    public static AgentIntent grind(String target) {
        return new AgentIntent(AgentIntentType.GRIND, blankToNull(target), 0);
    }

    public static AgentIntent loot(String target) {
        return new AgentIntent(AgentIntentType.LOOT, blankToNull(target), 0);
    }

    public static AgentIntent npc(String npcIdOrName) {
        return new AgentIntent(AgentIntentType.NPC, npcIdOrName, 0);
    }

    public static AgentIntent shop(String shopIdOrName) {
        return new AgentIntent(AgentIntentType.SHOP, shopIdOrName, 0);
    }

    public static AgentIntent trade(String characterName) {
        return new AgentIntent(AgentIntentType.TRADE, characterName, 0);
    }

    public static AgentIntent party(String command) {
        return new AgentIntent(AgentIntentType.PARTY, command, 0);
    }

    public static AgentIntent useItem(String itemIdOrName) {
        return new AgentIntent(AgentIntentType.USE_ITEM, itemIdOrName, 0);
    }

    public static AgentIntent equip(String itemIdOrName) {
        return new AgentIntent(AgentIntentType.EQUIP, itemIdOrName, 0);
    }

    public static AgentIntent skill(String skillIdOrAlias) {
        return new AgentIntent(AgentIntentType.SKILL, blankToNull(skillIdOrAlias), 0);
    }

    public static AgentIntent unknown(String line) {
        return new AgentIntent(AgentIntentType.UNKNOWN, line, 0);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
