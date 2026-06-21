package com.cosmic.agentclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentDecisionSystem {
    private final JdbcTemplate cmsJdbc;
    private final ObjectMapper mapper;
    private final AgentTaskLifecycleSystem taskLifecycleSystem;

    public AgentDecisionSystem(JdbcTemplate cmsJdbc, ObjectMapper mapper,
                               AgentTaskLifecycleSystem taskLifecycleSystem) {
        this.cmsJdbc = cmsJdbc;
        this.mapper = mapper;
        this.taskLifecycleSystem = taskLifecycleSystem;
    }

    public Map<String, Object> decide(int profileId, int currentMapId, Map<String, Object> channelSnapshot) {
        Map<String, Object> perception = objectMap(channelSnapshot.get("perception"));
        Map<String, Object> observed = objectMap(channelSnapshot.get("observed"));
        Optional<Map<String, Object>> activeGoal = activeGoal(profileId);
        List<Map<String, Object>> cards = loadoutCards(profileId);

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("system", "AgentDecisionSystem");
        decision.put("profileId", profileId);
        decision.put("mapId", currentMapId);
        decision.put("perceptionCounts", perceptionCounts(perception));

        if (activeGoal.isPresent()) {
            Map<String, Object> goal = activeGoal.get();
            decision.put("source", "goal");
            decision.put("goal", publicGoal(goal));
            applyGoalDecision(decision, goal, currentMapId, perception, observed);
            return decision;
        }

        List<Map<String, Object>> hobbies = cards.stream()
                .filter(card -> "HOBBY".equals(card.get("cardType")))
                .filter(card -> String.valueOf(card.get("slotKey")).startsWith("hobby_"))
                .toList();
        List<Map<String, Object>> personalities = cards.stream()
                .filter(card -> "PERSONALITY".equals(card.get("cardType")))
                .filter(card -> String.valueOf(card.get("slotKey")).startsWith("personality_"))
                .toList();

        Optional<Map<String, Object>> task = scheduledTask(profileId)
                .or(() -> firstCard(cards, "TASK", "active_task"))
                .or(() -> queuedTask(profileId))
                .or(() -> defaultTask(profileId, hobbies));
        if (task.isPresent()) {
            Map<String, Object> taskCard = task.get();
            Map<String, Object> lifecycle = taskLifecycleSystem.activateIfNeeded(profileId, taskCard);
            decision.put("source", taskCard.getOrDefault("source", "task"));
            decision.put("card", publicCard(taskCard));
            decision.put("taskLifecycle", lifecycle);
            decision.put("hobbies", hobbies.stream().map(this::publicCard).toList());
            decision.put("personalities", personalities.stream().map(this::publicCard).toList());
            Object queueId = taskCard.get("queueId");
            if (queueId != null) {
                decision.put("queueId", queueId);
            }
            Optional<AgentTaskLifecycleSystem.TaskCompletion> completion =
                    taskLifecycleSystem.evaluateCompletion(taskCard, currentMapId, observed);
            if (completion.isPresent()) {
                taskLifecycleSystem.completeTask(profileId, taskCard, completion.get());
                decision.put("intent", "WAIT");
                decision.put("reason", "Task completed: " + completion.get().reason());
                decision.put("completion", Map.of(
                        "reason", completion.get().reason(),
                        "progress", completion.get().progress(),
                        "reusableLoop", completion.get().reusableLoop()
                ));
                decision.put("next", "Next decision tick will select the next queued/default task.");
                return decision;
            }
            applyTaskDecision(decision, taskCard, hobbies, personalities, currentMapId, perception, observed);
            return decision;
        }

        if (!hobbies.isEmpty()) {
            Map<String, Object> hobby = hobbies.getFirst();
            decision.put("source", "hobby_fallback");
            decision.put("hobby", publicCard(hobby));
            decision.put("personalities", personalities.stream().map(this::publicCard).toList());
            applyHobbyFallbackDecision(decision, hobby, currentMapId, perception, observed);
            return decision;
        }

        decision.put("source", "idle");
        decision.put("intent", "IDLE");
        decision.put("reason", "No active goal, task, default task, or hobby card is equipped.");
        decision.put("next", "Equip a task card, queue a task, or add hobby cards for free-time selection.");
        return decision;
    }

    private void applyGoalDecision(Map<String, Object> decision, Map<String, Object> goal, int currentMapId,
                                   Map<String, Object> perception, Map<String, Object> observed) {
        String intent = String.valueOf(goal.getOrDefault("intent", "IDLE")).toUpperCase();
        Map<String, Object> config = objectMap(goal.get("payload"));
        Object targetRef = goal.get("target_ref");
        if (targetRef != null && !String.valueOf(targetRef).isBlank()) {
            config.putIfAbsent("targetRef", targetRef);
            config.putIfAbsent("argument", targetRef);
            if ("SAY".equals(intent)) {
                config.putIfAbsent("text", targetRef);
            } else if ("USE_ITEM".equals(intent) || "USE_CHAIR".equals(intent) || "CHAIR".equals(intent)) {
                config.putIfAbsent("itemId", targetRef);
            } else if ("EQUIP".equals(intent)) {
                config.putIfAbsent("itemId", targetRef);
            }
        }
        Integer targetMap = integer(goal.get("target_map")).orElse(null);
        if (targetMap != null && targetMap != currentMapId) {
            moveToMap(decision, targetMap, "Active goal target map differs from the current map.");
            return;
        }
        applyIntentDecision(decision, intent, currentMapId, perception, observed, config);
    }

    private void applyTaskDecision(Map<String, Object> decision, Map<String, Object> taskCard,
                                   List<Map<String, Object>> hobbies, List<Map<String, Object>> personalities,
                                   int currentMapId, Map<String, Object> perception, Map<String, Object> observed) {
        Map<String, Object> taskConfig = objectMap(taskCard.get("config"));
        Integer targetMap = integer(taskConfig.get("targetMapId"))
                .or(() -> integer(taskConfig.get("endpointMapId")))
                .orElse(null);
        if (targetMap != null && targetMap != currentMapId) {
            moveToMap(decision, targetMap, "Task card target map differs from the current map.");
            return;
        }
        Map<String, Object> merged = new LinkedHashMap<>(taskConfig);
        merged.put("hobbyBias", aggregateHobbyWeights(hobbies));
        merged.put("personalityTone", personalities.stream()
                .map(card -> objectMap(card.get("config")).get("tone"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .toList());
        String intent = String.valueOf(taskConfig.getOrDefault("intent", inferIntentFromHobbies(hobbies))).toUpperCase();
        applyIntentDecision(decision, intent, currentMapId, perception, observed, merged);
    }

    private void applyHobbyFallbackDecision(Map<String, Object> decision, Map<String, Object> hobby, int currentMapId,
                                            Map<String, Object> perception, Map<String, Object> observed) {
        Map<String, Object> config = objectMap(hobby.get("config"));
        String intent = String.valueOf(config.getOrDefault("fallbackIntent", "WAIT")).toUpperCase();
        applyIntentDecision(decision, intent, currentMapId, perception, observed, config);
    }

    private void applyIntentDecision(Map<String, Object> decision, String intent, int currentMapId,
                                     Map<String, Object> perception, Map<String, Object> observed,
                                     Map<String, Object> config) {
        switch (intent) {
            case "LOOT" -> chooseVisible(decision, "LOOT", perception, observed, "drops", config,
                    "Visible drops are available for the active task or hobby.");
            case "GRIND", "ATTACK" -> {
                Optional<Map<String, Object>> target = nearestVisible(perception, observed, "monsters");
                if (target.isPresent()) {
                    target(decision, "ATTACK", "monster", target.get(), config,
                            "Visible monster selected by combat task or hobby.");
                } else if (truthy(config.get("lootNearby")) && !visible(perception, "drops").isEmpty()) {
                    chooseVisible(decision, "LOOT", perception, observed, "drops", config,
                            "No visible monster; nearby drop selected because lootNearby is enabled.");
                } else {
                    roam(decision, currentMapId, "No visible monster is currently known.");
                }
            }
            case "NPC", "QUEST_ROUTE" -> {
                Optional<Map<String, Object>> npc = "QUEST_ROUTE".equals(intent)
                        ? questRouteNpc(perception, observed, config, currentMapId)
                        : nearestVisible(perception, observed, "npcs");
                if (npc.isPresent()) {
                    target(decision, "NPC", "npc", npc.get(), config,
                            "Visible NPC selected by quest/NPC task.");
                } else if (truthy(config.get("lootQuestItems")) && !visible(perception, "drops").isEmpty()) {
                    chooseVisible(decision, "LOOT", perception, observed, "drops", config,
                            "No visible NPC; quest-item loot preference selected a visible drop.");
                } else if ("QUEST_ROUTE".equals(intent) && !visible(perception, "monsters").isEmpty()) {
                    Optional<Map<String, Object>> target = nearestVisible(perception, observed, "monsters");
                    target.ifPresentOrElse(
                            row -> target(decision, "ATTACK", "monster", row, config,
                                    "No valid quest NPC is visible; Maple Island route selected nearby training monster work."),
                            () -> roam(decision, currentMapId, "No valid quest NPC or monster is currently known.")
                    );
                } else {
                    roam(decision, currentMapId, "No visible NPC is currently known.");
                }
            }
            case "FOLLOW_CHARACTER" -> chooseVisible(decision, "FOLLOW_CHARACTER", perception, observed, "players", config,
                    "Visible player selected by companion task or hobby.");
            case "ROAM" -> roam(decision, currentMapId, "Roam task or hobby is active.");
            case "WAIT" -> {
                decision.put("intent", "WAIT");
                decision.put("reason", socialReason(perception, config));
                decision.put("target", Map.of("type", "map", "mapId", currentMapId));
            }
            case "SAY" -> {
                decision.put("intent", "SAY");
                decision.put("target", Map.of(
                        "type", "chat",
                        "text", String.valueOf(config.getOrDefault("text", config.getOrDefault("argument", "")))
                ));
                decision.put("reason", "Chat intent selected by goal or card configuration.");
            }
            case "USE_ITEM", "USE_CHAIR", "CHAIR", "EQUIP" -> {
                decision.put("intent", intent);
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("type", intent.toLowerCase());
                target.put("itemId", config.getOrDefault("itemId", config.getOrDefault("argument", "")));
                target.put("slot", config.getOrDefault("slot", config.getOrDefault("inventorySlot", "")));
                decision.put("target", target);
                decision.put("reason", "Inventory intent selected by goal or card configuration.");
            }
            default -> {
                decision.put("intent", intent.isBlank() ? "IDLE" : intent);
                decision.put("reason", "Intent is known to the card system but has no concrete virtual-client executor yet.");
                decision.put("next", "Add an executor for this intent before it can send client-like packets.");
            }
        }
    }

    private void chooseVisible(Map<String, Object> decision, String intent, Map<String, Object> perception,
                               Map<String, Object> observed, String key, Map<String, Object> config, String reason) {
        Optional<Map<String, Object>> visible = nearestVisible(perception, observed, key);
        if (visible.isPresent()) {
            target(decision, intent, singular(key), visible.get(), config, reason);
            return;
        }
        decision.put("intent", "WAIT");
        decision.put("reason", "No visible " + key + " are currently known.");
        decision.put("next", "Wait for channel perception or roam once movement policy allows it.");
    }

    private void target(Map<String, Object> decision, String intent, String type, Map<String, Object> row, String reason) {
        target(decision, intent, type, row, Map.of(), reason);
    }

    private void target(Map<String, Object> decision, String intent, String type, Map<String, Object> row,
                        Map<String, Object> config, String reason) {
        Map<String, Object> target = new LinkedHashMap<>(row);
        target.put("type", type);
        for (String key : List.of(
                "messageType", "npcMessageType", "action", "npcAction", "selection", "npcSelection",
                "text", "npcText", "autoContinue", "npcAutoContinue", "interactionRange",
                "attackRangeX", "attackRangeY", "combatStyle", "strategy", "testDamage", "damage",
                "itemId", "slot", "inventorySlot")) {
            if (config.containsKey(key)) {
                target.put(key, config.get(key));
            }
        }
        decision.put("intent", intent);
        decision.put("target", target);
        decision.put("reason", reason);
    }

    private void moveToMap(Map<String, Object> decision, int targetMapId, String reason) {
        decision.put("intent", "MOVE_TO_MAP");
        decision.put("target", Map.of("type", "map", "mapId", targetMapId));
        decision.put("reason", reason);
    }

    private void roam(Map<String, Object> decision, int currentMapId, String reason) {
        decision.put("intent", "ROAM");
        decision.put("target", Map.of("type", "map", "mapId", currentMapId));
        decision.put("reason", reason);
    }

    private String socialReason(Map<String, Object> perception, Map<String, Object> config) {
        int chatCount = numeric(perception.get("chatCount"), 0);
        if (truthy(config.get("socialDrift")) && chatCount > 0) {
            return "Waiting in social mode; recent visible chat can be used by the chat system.";
        }
        if (truthy(config.get("preferChair"))) {
            return "Waiting with chair preference enabled.";
        }
        return "Wait task or hobby is active.";
    }

    private Optional<Map<String, Object>> questRouteNpc(Map<String, Object> perception, Map<String, Object> observed,
                                                        Map<String, Object> config, int currentMapId) {
        String instructionSet = String.valueOf(config.getOrDefault("instructionSet", ""));
        List<Map<String, Object>> npcs = visible(perception, "npcs");
        if (npcs.isEmpty()) {
            return Optional.empty();
        }
        if (!"mapleisland.full_questline.v1".equals(instructionSet)) {
            return nearestVisible(perception, observed, "npcs");
        }

        List<Integer> preferred = switch (currentMapId) {
            case 10000 -> List.of(2101);
            case 0, 1, 2, 3 -> List.of(2100, 2101);
            case 20000 -> List.of(2003);
            case 60000 -> List.of(22000);
            default -> List.of();
        };
        for (Integer npcId : preferred) {
            Optional<Map<String, Object>> preferredNpc = nearestVisible(
                    filteredPerception(perception, "npcs", npcs.stream()
                            .filter(row -> numeric(row.get("npcId"), 0) == npcId)
                            .toList()),
                    observed,
                    "npcs");
            if (preferredNpc.isPresent()) {
                return preferredNpc;
            }
        }

        return nearestVisible(
                filteredPerception(perception, "npcs", npcs.stream()
                        .filter(row -> isKnownMapleIslandScriptNpc(currentMapId, numeric(row.get("npcId"), 0)))
                        .toList()),
                observed,
                "npcs");
    }

    private boolean isKnownMapleIslandScriptNpc(int mapId, int npcId) {
        if (npcId == 2100 || npcId == 2101 || npcId == 2003) {
            return true;
        }
        if (npcId == 22000) {
            return mapId == 60000;
        }
        if (npcId == 2007) {
            return false;
        }
        return false;
    }

    private Map<String, Object> filteredPerception(Map<String, Object> perception, String key,
                                                   List<Map<String, Object>> rows) {
        Map<String, Object> filtered = new LinkedHashMap<>(perception);
        filtered.put(key, rows);
        filtered.put(singular(key) + "Count", rows.size());
        return filtered;
    }

    private Optional<Map<String, Object>> activeGoal(int profileId) {
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT id, goal_type, status, priority, target_map, target_ref, parameters_json
                FROM agent_goals
                WHERE agent_profile_id=?
                  AND status IN ('ACTIVE', 'RUNNING', 'PENDING')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'RUNNING' THEN 1 WHEN 'PENDING' THEN 2 ELSE 3 END,
                         priority DESC, id
                LIMIT 1
                """, profileId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private List<Map<String, Object>> loadoutCards(int profileId) {
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT l.slot_key, l.priority slot_priority, l.override_behavior, c.id card_id, c.card_key,
                       c.card_type, c.name, c.description, c.priority card_priority, c.config_json
                FROM agent_card_loadouts l
                JOIN agent_cards c ON c.id = l.card_id
                WHERE l.agent_profile_id=?
                  AND l.enabled=1
                  AND c.enabled=1
                ORDER BY l.priority DESC, l.id
                """, profileId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("slotKey", row.get("slot_key"));
            card.put("slotPriority", row.get("slot_priority"));
            card.put("overrideBehavior", row.get("override_behavior"));
            card.put("cardId", row.get("card_id"));
            card.put("cardKey", row.get("card_key"));
            card.put("cardType", row.get("card_type"));
            card.put("name", row.get("name"));
            card.put("description", row.get("description"));
            card.put("cardPriority", row.get("card_priority"));
            card.put("config", parseJson(row.get("config_json")));
            result.add(card);
        }
        return result;
    }

    private Optional<Map<String, Object>> scheduledTask(int profileId) {
        String today = dayCode(LocalDate.now().getDayOfWeek());
        LocalTime now = LocalTime.now();
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT s.id schedule_id, s.priority slot_priority, s.schedule_name, s.parameter_json,
                       s.queue_rule, c.id card_id, c.card_key, c.card_type, c.name, c.description,
                       c.priority card_priority, c.config_json
                FROM agent_task_schedules s
                JOIN agent_cards c ON c.id = s.card_id
                WHERE s.agent_profile_id=?
                  AND s.enabled=1
                  AND c.enabled=1
                  AND c.card_type='TASK'
                  AND (s.days_of_week='ALL' OR s.days_of_week LIKE ?)
                  AND (s.start_time IS NULL OR s.start_time <= ?)
                  AND (s.end_time IS NULL OR s.end_time >= ?)
                ORDER BY s.priority DESC, s.id
                LIMIT 1
                """, profileId, "%" + today + "%", now.toString(), now.toString());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> card = cardFromRow(rows.getFirst(), "scheduled_task");
        card.put("scheduleId", rows.getFirst().get("schedule_id"));
        card.put("scheduleName", rows.getFirst().get("schedule_name"));
        card.put("queueRule", rows.getFirst().get("queue_rule"));
        mergeParameters(card, rows.getFirst().get("parameter_json"));
        String queueRule = String.valueOf(card.getOrDefault("queueRule", "BACK")).toUpperCase();
        if (!"REPLACE_ACTIVE".equals(queueRule)) {
            ensureScheduledTaskQueued(profileId, card, queueRule);
            return Optional.empty();
        }
        return Optional.of(card);
    }

    private void ensureScheduledTaskQueued(int profileId, Map<String, Object> card, String queueRule) {
        int cardId = numeric(card.get("cardId"), 0);
        int scheduleId = numeric(card.get("scheduleId"), 0);
        if (cardId <= 0 || scheduleId <= 0) {
            return;
        }
        Integer existing = cmsJdbc.query("""
                SELECT id
                FROM agent_task_queue
                WHERE agent_profile_id=?
                  AND card_id=?
                  AND status IN ('ACTIVE', 'PENDING')
                  AND notes LIKE ?
                ORDER BY id DESC
                LIMIT 1
                """, rs -> rs.next() ? rs.getInt("id") : null, profileId, cardId, "%schedule:" + scheduleId + "%");
        if (existing != null) {
            return;
        }
        int order = scheduledQueueOrder(profileId, queueRule);
        Map<String, Object> parameters = objectMap(card.get("parameters"));
        String parameterJson = parameters.isEmpty() ? null : writeJson(parameters);
        cmsJdbc.update("""
                INSERT INTO agent_task_queue(agent_profile_id, card_id, queue_order, status, run_mode,
                                             repeat_policy, parameter_json, notes)
                VALUES (?, ?, ?, 'PENDING', 'REUSABLE', 'LOOP', ?, ?)
                """, profileId, cardId, order, parameterJson,
                "Scheduled from schedule:" + scheduleId + " using " + queueRule + " queue rule");
    }

    private int scheduledQueueOrder(int profileId, String queueRule) {
        Integer boundary = cmsJdbc.query("""
                SELECT COALESCE(%s(queue_order), 0) AS queue_order
                FROM agent_task_queue
                WHERE agent_profile_id=?
                  AND status IN ('ACTIVE', 'PENDING')
                """.formatted("FRONT".equals(queueRule) ? "MIN" : "MAX"),
                rs -> rs.next() ? rs.getInt("queue_order") : 0,
                profileId);
        int value = boundary == null ? 0 : boundary;
        return "FRONT".equals(queueRule) ? value - 10 : value + 10;
    }

    private Optional<Map<String, Object>> queuedTask(int profileId) {
        taskLifecycleSystem.completeExpiredQueueTasks(profileId);
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT q.id queue_id, q.queue_order slot_priority, q.parameter_json, q.status,
                       q.run_mode, q.repeat_policy, q.starts_at, q.expires_at, q.completed_at, q.locked_reason,
                       c.id card_id, c.card_key, c.card_type, c.name, c.description,
                       c.priority card_priority, c.config_json
                FROM agent_task_queue q
                JOIN agent_cards c ON c.id = q.card_id
                WHERE q.agent_profile_id=?
                  AND q.status IN ('ACTIVE', 'PENDING')
                  AND c.enabled=1
                  AND c.card_type='TASK'
                  AND (q.starts_at IS NULL OR q.starts_at <= CURRENT_TIMESTAMP)
                  AND (q.expires_at IS NULL OR q.expires_at >= CURRENT_TIMESTAMP)
                ORDER BY CASE q.status WHEN 'ACTIVE' THEN 0 ELSE 1 END, q.queue_order, q.id
                LIMIT 1
                """, profileId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> card = cardFromRow(rows.getFirst(), "queued_task");
        card.put("queueId", rows.getFirst().get("queue_id"));
        card.put("queueStatus", rows.getFirst().get("status"));
        card.put("runMode", rows.getFirst().get("run_mode"));
        card.put("repeatPolicy", rows.getFirst().get("repeat_policy"));
        card.put("startsAt", rows.getFirst().get("starts_at"));
        card.put("expiresAt", rows.getFirst().get("expires_at"));
        card.put("completedAt", rows.getFirst().get("completed_at"));
        card.put("lockedReason", rows.getFirst().get("locked_reason"));
        mergeParameters(card, rows.getFirst().get("parameter_json"));
        return Optional.of(card);
    }

    private Optional<Map<String, Object>> defaultTask(int profileId, List<Map<String, Object>> hobbies) {
        List<Map<String, Object>> rows = cmsJdbc.queryForList("""
                SELECT d.id default_id, d.priority slot_priority, d.selection_rule, d.parameter_json,
                       d.run_mode, d.repeat_policy,
                       c.id card_id, c.card_key, c.card_type, c.name, c.description,
                       c.priority card_priority, c.config_json
                FROM agent_task_defaults d
                JOIN agent_cards c ON c.id = d.card_id
                WHERE d.agent_profile_id=?
                  AND d.enabled=1
                  AND c.enabled=1
                  AND c.card_type='TASK'
                """, profileId);
        return rows.stream()
                .map(row -> {
                    Map<String, Object> card = cardFromRow(row, "default_task");
                    card.put("defaultId", row.get("default_id"));
                    card.put("selectionRule", row.get("selection_rule"));
                    card.put("runMode", row.get("run_mode"));
                    card.put("repeatPolicy", row.get("repeat_policy"));
                    mergeParameters(card, row.get("parameter_json"));
                    card.put("hobbyScore", hobbyScore(card, hobbies));
                    return card;
                })
                .max(Comparator.comparingDouble((Map<String, Object> card) -> numericDouble(card.get("hobbyScore"), 0.0))
                        .thenComparingInt(card -> numeric(card.get("slotPriority"), 0))
                        .thenComparingInt(card -> numeric(card.get("cardPriority"), 0)));
    }

    private Map<String, Object> cardFromRow(Map<String, Object> row, String source) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("source", source);
        card.put("slotKey", source);
        card.put("slotPriority", row.get("slot_priority"));
        card.put("cardId", row.get("card_id"));
        card.put("cardKey", row.get("card_key"));
        card.put("cardType", row.get("card_type"));
        card.put("name", row.get("name"));
        card.put("description", row.get("description"));
        card.put("cardPriority", row.get("card_priority"));
        card.put("config", parseJson(row.get("config_json")));
        return card;
    }

    private Optional<Map<String, Object>> firstCard(List<Map<String, Object>> cards, String cardType, String... slotKeys) {
        List<String> slots = List.of(slotKeys);
        return cards.stream()
                .filter(card -> cardType.equals(card.get("cardType")))
                .filter(card -> slots.contains(String.valueOf(card.get("slotKey"))))
                .findFirst();
    }

    @SuppressWarnings("unchecked")
    private void mergeParameters(Map<String, Object> card, Object parameterJson) {
        Map<String, Object> parameters = parseJson(parameterJson);
        if (parameters.isEmpty()) {
            return;
        }
        Map<String, Object> config = new LinkedHashMap<>(objectMap(card.get("config")));
        config.putAll(parameters);
        card.put("config", config);
        card.put("parameters", parameters);
    }

    private double hobbyScore(Map<String, Object> taskCard, List<Map<String, Object>> hobbies) {
        Map<String, Object> taskWeights = objectMap(objectMap(taskCard.get("config")).get("categoryWeights"));
        if (taskWeights.isEmpty() || hobbies.isEmpty()) {
            return numeric(taskCard.get("slotPriority"), 0);
        }
        double score = 0.0;
        for (Map<String, Object> hobby : hobbies) {
            Map<String, Object> preferenceWeights = objectMap(objectMap(hobby.get("config")).get("preferenceWeights"));
            for (Map.Entry<String, Object> entry : taskWeights.entrySet()) {
                score += numericDouble(entry.getValue(), 0.0)
                        * numericDouble(preferenceWeights.get(entry.getKey()), 0.0);
            }
        }
        return score * 1000.0 + numeric(taskCard.get("slotPriority"), 0);
    }

    private Map<String, Double> aggregateHobbyWeights(List<Map<String, Object>> hobbies) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map<String, Object> hobby : hobbies) {
            Map<String, Object> preferenceWeights = objectMap(objectMap(hobby.get("config")).get("preferenceWeights"));
            for (Map.Entry<String, Object> entry : preferenceWeights.entrySet()) {
                weights.merge(entry.getKey(), numericDouble(entry.getValue(), 0.0), Double::sum);
            }
        }
        return weights;
    }

    private String inferIntentFromHobbies(List<Map<String, Object>> hobbies) {
        for (Map<String, Object> hobby : hobbies) {
            Object intent = objectMap(hobby.get("config")).get("fallbackIntent");
            if (intent != null && !String.valueOf(intent).isBlank()) {
                return String.valueOf(intent);
            }
        }
        return "WAIT";
    }

    private String dayCode(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "MON";
            case TUESDAY -> "TUE";
            case WEDNESDAY -> "WED";
            case THURSDAY -> "THU";
            case FRIDAY -> "FRI";
            case SATURDAY -> "SAT";
            case SUNDAY -> "SUN";
        };
    }

    private Optional<Map<String, Object>> nearestVisible(Map<String, Object> perception, Map<String, Object> observed,
                                                        String key) {
        List<Map<String, Object>> rows = visible(perception, key);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Optional<Integer> x = integer(observed.get("x"));
        Optional<Integer> y = integer(observed.get("y"));
        if (x.isEmpty() || y.isEmpty()) {
            return Optional.of(rows.getFirst());
        }
        return rows.stream()
                .min(Comparator.comparingInt(row -> distanceSquared(x.get(), y.get(),
                        numeric(row.get("x"), x.get()), numeric(row.get("y"), y.get()))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> visible(Map<String, Object> perception, String key) {
        Object value = perception.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private Map<String, Object> publicGoal(Map<String, Object> goal) {
        Map<String, Object> result = new LinkedHashMap<>(goal);
        result.put("payload", parseJson(goal.get("parameters_json")));
        result.remove("parameters_json");
        return result;
    }

    private Map<String, Object> publicCard(Map<String, Object> card) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("slotKey", card.get("slotKey"));
        result.put("cardId", card.get("cardId"));
        result.put("cardKey", card.get("cardKey"));
        result.put("cardType", card.get("cardType"));
        result.put("name", card.get("name"));
        result.put("config", card.get("config"));
        result.put("runMode", card.get("runMode"));
        result.put("repeatPolicy", card.get("repeatPolicy"));
        result.put("queueStatus", card.get("queueStatus"));
        return result;
    }

    private Map<String, Object> perceptionCounts(Map<String, Object> perception) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("npcs", numeric(perception.get("npcCount"), 0));
        counts.put("monsters", numeric(perception.get("monsterCount"), 0));
        counts.put("drops", numeric(perception.get("dropCount"), 0));
        counts.put("players", numeric(perception.get("playerCount"), 0));
        counts.put("chat", numeric(perception.get("chatCount"), 0));
        return counts;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        try {
            return mapper.readValue(String.valueOf(value), Map.class);
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Optional<Integer> integer(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.intValue());
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Optional.of(Integer.parseInt(String.valueOf(value)));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private int numeric(Object value, int fallback) {
        return integer(value).orElse(fallback);
    }

    private double numericDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private int distanceSquared(int x1, int y1, int x2, int y2) {
        int dx = x1 - x2;
        int dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    private boolean truthy(Object value) {
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

    private String singular(String key) {
        return key.endsWith("s") ? key.substring(0, key.length() - 1) : key;
    }
}
