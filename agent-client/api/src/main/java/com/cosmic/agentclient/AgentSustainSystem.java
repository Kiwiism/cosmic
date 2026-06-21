package com.cosmic.agentclient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class AgentSustainSystem {
    SustainPlan plan(SustainContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "sustain-plan");
        detail.put("enabled", context.enabled());
        detail.put("observedHp", context.observedHp().orElse(null));
        detail.put("hpThreshold", context.hpThreshold());
        if (!context.enabled()) {
            return SustainPlan.none(detail, "Sustain is disabled.");
        }
        if (context.observedHp().isEmpty()) {
            return SustainPlan.none(detail, "No observed HP yet.");
        }
        if (context.observedHp().get() > context.hpThreshold()) {
            return SustainPlan.none(detail, "Observed HP is above the sustain threshold.");
        }
        if (context.lastInventoryAtMillis() > 0 && context.nowMillis() - context.lastInventoryAtMillis() < 1_000) {
            detail.put("throttled", true);
            detail.put("lastSentAt", context.lastInventoryAtMillis());
            return SustainPlan.none(detail, "Inventory packet cooldown is active.");
        }
        if (context.hpItemId() <= 0 || context.hpItemSlot() <= 0) {
            detail.put("virtualDebtAllowed", context.virtualDebtAllowed());
            String reason = context.virtualDebtAllowed()
                    ? "Virtual sustain debt is allowed, but no server-side debt bridge is installed; a normal client still needs an item slot."
                    : "HP is low but no hpItemId/hpItemSlot is configured.";
            return SustainPlan.blocked(detail, reason);
        }
        detail.put("itemId", context.hpItemId());
        detail.put("slot", context.hpItemSlot());
        return new SustainPlan(SustainAction.USE_HP_ITEM, true, "", context.hpItemId(), context.hpItemSlot(), detail);
    }

    enum SustainAction {
        NONE,
        USE_HP_ITEM
    }

    record SustainContext(
            boolean enabled,
            Optional<Integer> observedHp,
            int hpThreshold,
            int hpItemId,
            int hpItemSlot,
            boolean virtualDebtAllowed,
            long lastInventoryAtMillis,
            long nowMillis
    ) {
    }

    record SustainPlan(
            SustainAction action,
            boolean executable,
            String blockedReason,
            int itemId,
            int slot,
            Map<String, Object> detail
    ) {
        static SustainPlan none(Map<String, Object> detail, String reason) {
            detail.put("reason", reason);
            return new SustainPlan(SustainAction.NONE, false, reason, 0, 0, detail);
        }

        static SustainPlan blocked(Map<String, Object> detail, String reason) {
            detail.put("blocked", reason);
            return new SustainPlan(SustainAction.NONE, false, reason, 0, 0, detail);
        }
    }
}
