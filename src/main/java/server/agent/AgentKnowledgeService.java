package server.agent;

import client.Character;
import client.Character.SkillEntry;
import client.Skill;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;

import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class AgentKnowledgeService {
    private static final int SKILL_LIMIT = 24;
    private static final int ITEM_SAMPLE_LIMIT = 8;

    public AgentKnowledgeSnapshot snapshot(Character character) {
        return new AgentKnowledgeSnapshot(
                character.getLevel(),
                character.getJob().getId(),
                character.getHp(),
                character.getCurrentMaxHp(),
                character.getMp(),
                character.getCurrentMaxMp(),
                character.getMeso(),
                skills(character),
                inventories(character)
        );
    }

    private List<AgentKnowledgeSnapshot.SkillSummary> skills(Character character) {
        return character.getSkills().entrySet().stream()
                .filter(entry -> entry.getValue().skillevel > 0)
                .sorted(Comparator.comparingInt((Map.Entry<Skill, SkillEntry> entry) -> entry.getValue().skillevel).reversed()
                        .thenComparingInt(entry -> entry.getKey().getId()))
                .limit(SKILL_LIMIT)
                .map(entry -> new AgentKnowledgeSnapshot.SkillSummary(
                        entry.getKey().getId(),
                        entry.getValue().skillevel,
                        entry.getValue().masterlevel,
                        entry.getValue().expiration
                ))
                .toList();
    }

    private List<AgentKnowledgeSnapshot.InventorySummary> inventories(Character character) {
        return List.of(
                inventory(character.getInventory(InventoryType.EQUIPPED)),
                inventory(character.getInventory(InventoryType.EQUIP)),
                inventory(character.getInventory(InventoryType.USE)),
                inventory(character.getInventory(InventoryType.SETUP)),
                inventory(character.getInventory(InventoryType.ETC)),
                inventory(character.getInventory(InventoryType.CASH))
        );
    }

    private AgentKnowledgeSnapshot.InventorySummary inventory(Inventory inventory) {
        Collection<Item> items = inventory.list();
        int totalQuantity = items.stream().mapToInt(Item::getQuantity).sum();
        List<AgentKnowledgeSnapshot.ItemStackSummary> sampleItems = items.stream()
                .sorted(Comparator.comparingInt(Item::getPosition))
                .limit(ITEM_SAMPLE_LIMIT)
                .map(item -> new AgentKnowledgeSnapshot.ItemStackSummary(
                        item.getItemId(),
                        item.getQuantity(),
                        item.getPosition()
                ))
                .toList();
        return new AgentKnowledgeSnapshot.InventorySummary(
                inventory.getType().name(),
                inventory.getSlotLimit(),
                items.size(),
                totalQuantity,
                sampleItems
        );
    }
}
