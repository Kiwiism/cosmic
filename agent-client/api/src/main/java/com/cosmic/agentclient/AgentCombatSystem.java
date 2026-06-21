package com.cosmic.agentclient;

import java.util.LinkedHashMap;
import java.util.Map;

final class AgentCombatSystem {
    private static final int DEFAULT_HORIZONTAL_RANGE = 95;
    private static final int DEFAULT_VERTICAL_RANGE = 65;
    private static final int KITE_TOO_CLOSE_RANGE = 35;
    private static final long BASIC_ATTACK_COOLDOWN_MILLIS = 650;

    CombatPlan plan(CombatContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "combat-plan");
        detail.put("target", context.target());
        int objectId = numeric(context.target().get("objectId"), numeric(context.target().get("oid"), 0));
        detail.put("objectId", objectId);
        if (objectId <= 0) {
            return CombatPlan.blocked(CombatAction.NONE, detail, "No visible monster object id is available for a basic attack packet.");
        }
        if (context.observedX() == null || context.observedY() == null) {
            return CombatPlan.blocked(CombatAction.NONE, detail, "No observed character x/y yet, so combat range cannot be verified.");
        }
        int targetX = numeric(context.target().get("x"), context.observedX());
        int targetY = numeric(context.target().get("y"), context.observedY());
        int dx = targetX - context.observedX();
        int dy = targetY - context.observedY();
        int horizontalRange = numeric(context.target().get("attackRangeX"), DEFAULT_HORIZONTAL_RANGE);
        int verticalRange = numeric(context.target().get("attackRangeY"), DEFAULT_VERTICAL_RANGE);
        String style = String.valueOf(context.target().getOrDefault("combatStyle", context.target().getOrDefault("strategy", "BASIC"))).toUpperCase();
        detail.put("targetX", targetX);
        detail.put("targetY", targetY);
        detail.put("dx", dx);
        detail.put("dy", dy);
        detail.put("style", style);
        detail.put("attackRangeX", horizontalRange);
        detail.put("attackRangeY", verticalRange);

        if (Math.abs(dy) > verticalRange) {
            return CombatPlan.blocked(CombatAction.APPROACH, detail, "Monster is on a different platform/height; navigation must approach first.");
        }
        if (Math.abs(dx) > horizontalRange) {
            return CombatPlan.blocked(CombatAction.APPROACH, detail, "Monster is outside basic attack range; local approach should run first.");
        }
        if (("KITE".equals(style) || "SAFE".equals(style)) && Math.abs(dx) < KITE_TOO_CLOSE_RANGE) {
            int retreatDirection = dx <= 0 ? 1 : 0;
            detail.put("retreatDirection", retreatDirection);
            return new CombatPlan(CombatAction.RETREAT, false, false, "Target is too close for kite style; retreat movement should run first.",
                    objectId, targetX, targetY, context.observedX(), context.observedY(), context.observedStance(), retreatDirection, 0, detail);
        }
        if (context.lastCombatAtMillis() > 0 && context.nowMillis() - context.lastCombatAtMillis() < BASIC_ATTACK_COOLDOWN_MILLIS) {
            detail.put("lastCombatAt", context.lastCombatAtMillis());
            return CombatPlan.throttled(detail);
        }
        int direction = targetX < context.observedX() ? 0 : 1;
        int damage = numeric(context.target().get("testDamage"), numeric(context.target().get("damage"), 1));
        return new CombatPlan(CombatAction.ATTACK, true, false, "", objectId, targetX, targetY,
                context.observedX(), context.observedY(), context.observedStance(), direction, damage, detail);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static int numeric(Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    enum CombatAction {
        NONE,
        APPROACH,
        RETREAT,
        ATTACK
    }

    record CombatContext(
            Map<String, Object> target,
            Integer observedX,
            Integer observedY,
            int observedStance,
            long lastCombatAtMillis,
            long nowMillis
    ) {
    }

    record CombatPlan(
            CombatAction action,
            boolean executable,
            boolean throttled,
            String blockedReason,
            int objectId,
            int targetX,
            int targetY,
            int attackX,
            int attackY,
            int stance,
            int direction,
            int damage,
            Map<String, Object> detail
    ) {
        static CombatPlan blocked(CombatAction action, Map<String, Object> detail, String reason) {
            detail.put("blocked", reason);
            return new CombatPlan(action, false, false, reason, 0, 0, 0, 0, 0, 0, 1, 0, detail);
        }

        static CombatPlan throttled(Map<String, Object> detail) {
            detail.put("throttled", true);
            return new CombatPlan(CombatAction.ATTACK, false, true, "", 0, 0, 0, 0, 0, 0, 1, 0, detail);
        }
    }
}
