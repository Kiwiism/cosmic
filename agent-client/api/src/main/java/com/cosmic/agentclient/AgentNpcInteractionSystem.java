package com.cosmic.agentclient;

import java.util.LinkedHashMap;
import java.util.Map;

final class AgentNpcInteractionSystem {
    private static final int DEFAULT_INTERACTION_RANGE = 180;
    private static final long OPEN_COOLDOWN_MILLIS = 1_000;
    private static final long CONTINUE_COOLDOWN_MILLIS = 650;

    NpcPlan plan(NpcContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("kind", "npc-interaction-plan");
        detail.put("target", context.target());
        int objectId = numeric(context.target().get("objectId"), 0);
        Integer npcId = context.target().get("npcId") instanceof Number number ? number.intValue() : null;
        detail.put("objectId", objectId);
        if (npcId != null) {
            detail.put("npcId", npcId);
        }
        if (objectId <= 0) {
            return NpcPlan.blocked(NpcAction.NONE, detail, "Decision target has no visible NPC object id.");
        }
        if (context.observedX() == null || context.observedY() == null) {
            return NpcPlan.blocked(NpcAction.NONE, detail, "No observed character x/y yet, so NPC range cannot be verified.");
        }

        Integer targetX = integer(context.target().get("x"));
        Integer targetY = integer(context.target().get("y"));
        if (targetX != null && targetY != null) {
            int distanceSquared = distanceSquared(context.observedX(), context.observedY(), targetX, targetY);
            int range = numeric(context.target().get("interactionRange"), DEFAULT_INTERACTION_RANGE);
            detail.put("targetX", targetX);
            detail.put("targetY", targetY);
            detail.put("distanceSquared", distanceSquared);
            detail.put("interactionRange", range);
            if (distanceSquared > range * range) {
                return NpcPlan.blocked(NpcAction.NONE, detail, "NPC target is not close enough; navigation/local approach should run first.");
            }
        }

        long now = context.nowMillis();
        if (context.activeConversation()) {
            if (!truthy(context.target().getOrDefault("autoContinue", context.target().getOrDefault("npcAutoContinue", true)))) {
                return NpcPlan.blocked(NpcAction.NONE, detail, "NPC conversation is active but autoContinue is disabled by the task/card.");
            }
            if (context.lastNpcAtMillis() > 0 && now - context.lastNpcAtMillis() < CONTINUE_COOLDOWN_MILLIS) {
                detail.put("lastSentAt", context.lastNpcAtMillis());
                return NpcPlan.throttled(NpcAction.CONTINUE, detail);
            }
            int messageType = numeric(context.target().get("messageType"),
                    numeric(context.target().get("npcMessageType"), context.lastMessageType()));
            int action = numeric(context.target().get("action"), numeric(context.target().get("npcAction"), 1));
            int selection = numeric(context.target().get("selection"), numeric(context.target().get("npcSelection"), 0));
            String text = String.valueOf(context.target().getOrDefault("text", context.target().getOrDefault("npcText", ""))).strip();
            detail.put("messageType", messageType);
            detail.put("action", action);
            detail.put("selection", selection);
            if (!text.isBlank()) {
                detail.put("textLength", text.length());
                return new NpcPlan(NpcAction.TEXT, true, false, "", objectId, npcId, messageType, action, selection, text, detail);
            }
            return new NpcPlan(NpcAction.CONTINUE, true, false, "", objectId, npcId, messageType, action, selection, "", detail);
        }

        if (context.lastNpcAtMillis() > 0 && now - context.lastNpcAtMillis() < OPEN_COOLDOWN_MILLIS) {
            detail.put("lastSentAt", context.lastNpcAtMillis());
            return NpcPlan.throttled(NpcAction.OPEN, detail);
        }
        return new NpcPlan(NpcAction.OPEN, true, false, "", objectId, npcId, 0, 1, 0, "", detail);
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

    private static int distanceSquared(int x1, int y1, int x2, int y2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        return switch (String.valueOf(value).trim().toLowerCase()) {
            case "1", "true", "yes", "y", "enabled", "on" -> true;
            default -> false;
        };
    }

    enum NpcAction {
        NONE,
        OPEN,
        CONTINUE,
        TEXT
    }

    record NpcContext(
            Map<String, Object> target,
            Integer observedX,
            Integer observedY,
            boolean activeConversation,
            int lastMessageType,
            long lastNpcAtMillis,
            long nowMillis
    ) {
    }

    record NpcPlan(
            NpcAction action,
            boolean executable,
            boolean throttled,
            String blockedReason,
            int objectId,
            Integer npcId,
            int messageType,
            int continueAction,
            int selection,
            String text,
            Map<String, Object> detail
    ) {
        static NpcPlan blocked(NpcAction action, Map<String, Object> detail, String reason) {
            detail.put("blocked", reason);
            return new NpcPlan(action, false, false, reason, 0, null, 0, 1, 0, "", detail);
        }

        static NpcPlan throttled(NpcAction action, Map<String, Object> detail) {
            detail.put("throttled", true);
            return new NpcPlan(action, false, true, "", 0, null, 0, 1, 0, "", detail);
        }
    }
}
