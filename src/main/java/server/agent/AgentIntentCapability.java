package server.agent;

public enum AgentIntentCapability {
    SELF("intent.self.enabled", true),
    CHAT("intent.chat.enabled", false),
    NAVIGATION("intent.navigation.enabled", false),
    COMBAT("intent.combat.enabled", false),
    LOOT("intent.loot.enabled", false),
    NPC("intent.npc.enabled", false),
    SHOP("intent.shop.enabled", false),
    TRADE("intent.trade.enabled", false),
    PARTY("intent.party.enabled", false),
    INVENTORY("intent.inventory.enabled", false),
    SKILL("intent.skill.enabled", false),
    SCRIPT("intent.script.enabled", false);

    private final String policyKey;
    private final boolean defaultEnabled;

    AgentIntentCapability(String policyKey, boolean defaultEnabled) {
        this.policyKey = policyKey;
        this.defaultEnabled = defaultEnabled;
    }

    public String policyKey() {
        return policyKey;
    }

    public boolean defaultEnabled() {
        return defaultEnabled;
    }

    public static AgentIntentCapability fromIntent(AgentIntentType type) {
        return switch (type) {
            case IDLE, WAIT -> SELF;
            case SAY -> CHAT;
            case ROAM, MOVE, MOVE_TO_MAP, FOLLOW_CHARACTER, USE_PORTAL -> NAVIGATION;
            case ATTACK, GRIND -> COMBAT;
            case LOOT -> LOOT;
            case NPC -> NPC;
            case SHOP -> SHOP;
            case TRADE -> TRADE;
            case PARTY -> PARTY;
            case USE_ITEM, EQUIP -> INVENTORY;
            case SKILL -> SKILL;
            case UNKNOWN -> SCRIPT;
        };
    }
}
