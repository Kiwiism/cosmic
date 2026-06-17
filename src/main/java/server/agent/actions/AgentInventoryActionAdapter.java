package server.agent.actions;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;
import server.agent.AgentIntentCapability;
import server.agent.AgentIntentType;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

public final class AgentInventoryActionAdapter implements AgentActionAdapter {
    @Override
    public AgentIntentCapability capability() {
        return AgentIntentCapability.INVENTORY;
    }

    @Override
    public AgentActionResult execute(AgentActionContext context) {
        AgentIntentType type = context.intent().type();
        if (type != AgentIntentType.USE_ITEM && type != AgentIntentType.EQUIP) {
            return AgentActionResult.blockedByRuntime(capability(), type + " reached the inventory adapter unexpectedly");
        }

        Character character = context.managed().character();
        if (character == null) {
            return AgentActionResult.blockedByRuntime(capability(), "Cannot inspect inventory without an attached character");
        }

        InventoryType inventoryType = type == AgentIntentType.EQUIP ? InventoryType.EQUIP : InventoryType.USE;
        Inventory inventory = character.getInventory(inventoryType);
        if (inventory == null) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No " + inventoryType + " inventory is available",
                    inventoryDetailsJson(context, inventoryType, null, noItemState(type), "Inventory container is unavailable")
            );
        }

        Optional<Item> candidate = selectItem(inventory, context.intent().argument(), type);
        if (candidate.isEmpty()) {
            return AgentActionResult.blockedByRuntime(
                    capability(),
                    "No matching " + inventoryType + " item is available for " + type,
                    inventoryDetailsJson(context, inventoryType, null, noItemState(type), "No matching item found")
            );
        }

        Item item = candidate.get();
        String state = type == AgentIntentType.EQUIP ? "EQUIP_READY" : "ITEM_READY";
        String itemName = itemName(item.getItemId());
        return AgentActionResult.ok(
                capability(),
                type + " readiness found " + (itemName == null ? item.getItemId() : itemName)
                        + " in " + inventoryType + " slot " + item.getPosition(),
                false,
                inventoryDetailsJson(context, inventoryType, item, state, "Readiness only; item use/equip is intentionally not executed yet")
        );
    }

    private Optional<Item> selectItem(Inventory inventory, String argument, AgentIntentType type) {
        String target = argument == null ? "" : argument.trim();
        return inventory.list().stream()
                .filter(item -> item != null)
                .filter(item -> itemMatches(item, target, type))
                .min(Comparator.comparingInt(Item::getPosition));
    }

    private boolean itemMatches(Item item, String target, AgentIntentType type) {
        if (target == null || target.isBlank()) {
            return true;
        }
        if (String.valueOf(item.getItemId()).equals(target)) {
            return true;
        }

        String itemName = itemName(item.getItemId());
        if (itemName == null || itemName.isBlank()) {
            return false;
        }

        String lowerTarget = target.toLowerCase(Locale.ROOT);
        String lowerName = itemName.toLowerCase(Locale.ROOT);
        if (type == AgentIntentType.USE_ITEM && matchesPotionAlias(lowerName, lowerTarget)) {
            return true;
        }
        return lowerName.contains(lowerTarget);
    }

    private boolean matchesPotionAlias(String lowerName, String lowerTarget) {
        if ("potion".equals(lowerTarget)) {
            return lowerName.contains("potion") || lowerName.contains("elixir");
        }
        if ("hp".equals(lowerTarget) || "health".equals(lowerTarget)) {
            return lowerName.contains("hp")
                    || lowerName.contains("red potion")
                    || lowerName.contains("orange potion")
                    || lowerName.contains("white potion")
                    || lowerName.contains("elixir");
        }
        if ("mp".equals(lowerTarget) || "mana".equals(lowerTarget)) {
            return lowerName.contains("mp")
                    || lowerName.contains("blue potion")
                    || lowerName.contains("mana")
                    || lowerName.contains("elixir");
        }
        return false;
    }

    private String noItemState(AgentIntentType type) {
        return type == AgentIntentType.EQUIP ? "NO_EQUIP" : "NO_ITEM";
    }

    private String inventoryDetailsJson(
            AgentActionContext context,
            InventoryType inventoryType,
            Item item,
            String state,
            String note
    ) {
        return "{"
                + "\"inventoryState\":\"" + escapeJson(state) + "\","
                + "\"intent\":\"" + context.intent().type().name() + "\","
                + "\"argument\":\"" + escapeJson(context.intent().argument()) + "\","
                + "\"world\":" + world(context) + ","
                + "\"channel\":" + channel(context) + ","
                + "\"mapId\":" + mapId(context) + ","
                + "\"inventoryType\":\"" + inventoryType.name() + "\","
                + "\"matchedItem\":" + itemJson(item) + ","
                + "\"mutationEnabled\":false,"
                + "\"note\":\"" + escapeJson(note) + "\""
                + "}";
    }

    private int world(AgentActionContext context) {
        return context.perception() == null ? context.managed().client().getWorld() : context.perception().world();
    }

    private int channel(AgentActionContext context) {
        return context.perception() == null ? context.managed().client().getChannel() : context.perception().channel();
    }

    private int mapId(AgentActionContext context) {
        if (context.perception() != null) {
            return context.perception().mapId();
        }
        Character character = context.managed().character();
        return character == null ? -1 : character.getMapId();
    }

    private String itemJson(Item item) {
        if (item == null) {
            return "null";
        }
        return "{"
                + "\"itemId\":" + item.getItemId() + ","
                + "\"name\":\"" + escapeJson(itemName(item.getItemId())) + "\","
                + "\"position\":" + item.getPosition() + ","
                + "\"quantity\":" + item.getQuantity() + ","
                + "\"inventoryType\":\"" + item.getInventoryType().name() + "\","
                + "\"owner\":\"" + escapeJson(item.getOwner()) + "\","
                + "\"flag\":" + item.getFlag() + ","
                + "\"expiration\":" + item.getExpiration()
                + "}";
    }

    private String itemName(int itemId) {
        return ItemInformationProvider.getInstance().getName(itemId);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
