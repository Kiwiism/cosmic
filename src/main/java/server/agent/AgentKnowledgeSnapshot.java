package server.agent;

import java.util.List;

public record AgentKnowledgeSnapshot(
        int level,
        int jobId,
        int hp,
        int maxHp,
        int mp,
        int maxMp,
        int meso,
        List<SkillSummary> skills,
        List<InventorySummary> inventories
) {
    public AgentKnowledgeSnapshot {
        skills = List.copyOf(skills == null ? List.of() : skills);
        inventories = List.copyOf(inventories == null ? List.of() : inventories);
    }

    public record SkillSummary(
            int skillId,
            int level,
            int masterLevel,
            long expiration
    ) {
    }

    public record InventorySummary(
            String type,
            int slotLimit,
            int usedSlots,
            int totalQuantity,
            List<ItemStackSummary> sampleItems
    ) {
        public InventorySummary {
            sampleItems = List.copyOf(sampleItems == null ? List.of() : sampleItems);
        }
    }

    public record ItemStackSummary(
            int itemId,
            int quantity,
            short position
    ) {
    }
}
