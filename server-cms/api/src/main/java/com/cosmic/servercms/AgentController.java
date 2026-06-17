package com.cosmic.servercms;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final JdbcTemplate cmsJdbc;
    private final JdbcTemplate gameJdbc;
    private final ObjectMapper mapper;
    private final BridgeClient bridge;
    private static final List<AgentCapabilityPolicy> CAPABILITY_POLICIES = List.of(
            new AgentCapabilityPolicy("intent.self.enabled", "Self timing", "Allows no-op IDLE and WAIT dry-run intents.", true),
            new AgentCapabilityPolicy("intent.chat.enabled", "Chat", "Allows future SAY/chat intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.navigation.enabled", "Navigation", "Allows future ROAM, MOVE, MAP and PORTAL intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.combat.enabled", "Combat", "Allows future ATTACK and GRIND intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.loot.enabled", "Loot", "Allows future LOOT intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.npc.enabled", "NPC interaction", "Allows future NPC/TALK intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.shop.enabled", "Shop interaction", "Allows future SHOP/MERCHANT intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.trade.enabled", "Trade", "Allows future TRADE intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.party.enabled", "Party", "Allows future PARTY intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.inventory.enabled", "Inventory", "Allows future USEITEM and EQUIP intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.script.enabled", "Script fallback", "Allows unknown script intents to pass the policy gate. Keep disabled unless debugging parser behavior.", false)
    );

    public AgentController(JdbcTemplate cmsJdbc, @Qualifier("gameJdbc") JdbcTemplate gameJdbc, ObjectMapper mapper,
                           BridgeClient bridge) {
        this.cmsJdbc = cmsJdbc;
        this.gameJdbc = gameJdbc;
        this.mapper = mapper;
        this.bridge = bridge;
    }

    @GetMapping
    List<Map<String, Object>> agents(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT p.*, c.name character_name, c.level, c.job, c.world, c.map, a.name account_name,
                       s.state latest_state, s.current_task latest_task, s.last_tick_at
                FROM agent_profiles p
                JOIN characters c ON c.id = p.character_id
                JOIN accounts a ON a.id = c.accountid
                LEFT JOIN agent_runtime_sessions s ON s.id = (
                    SELECT s2.id FROM agent_runtime_sessions s2
                    WHERE s2.agent_profile_id = p.id
                    ORDER BY s2.id DESC LIMIT 1
                )
                WHERE c.name LIKE ? OR a.name LIKE ? OR p.display_name LIKE ? OR CAST(p.character_id AS CHAR) LIKE ?
                ORDER BY p.enabled DESC, p.id DESC
                LIMIT 200
                """, like(q), like(q), like(q), like(q));
    }

    @GetMapping("/{id}")
    Map<String, Object> agent(@PathVariable int id) {
        return oneGame("""
                SELECT p.*, c.name character_name, c.level, c.job, c.world, c.map, c.spawnpoint,
                       a.id account_id, a.name account_name, a.loggedin
                FROM agent_profiles p
                JOIN characters c ON c.id = p.character_id
                JOIN accounts a ON a.id = c.accountid
                WHERE p.id = ?
                """, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(@Valid @RequestBody CreateAgent body, Principal principal) {
        Map<String, Object> character = oneGame("""
                SELECT c.id, c.name character_name, c.accountid, a.name account_name
                FROM characters c JOIN accounts a ON a.id = c.accountid
                WHERE c.id = ?
                """, body.characterId());
        if (!gameJdbc.queryForList("SELECT id FROM agent_profiles WHERE character_id=?", body.characterId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This character already has an agent profile");
        }

        gameJdbc.update("""
                INSERT INTO agent_profiles(character_id, ownership_type, owner_account_id, owner_character_id,
                                           enabled, display_name, default_mode, behavior_profile,
                                           personality_profile, script_name, llm_enabled)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                body.characterId(),
                valueOr(body.ownershipType(), "SERVER"),
                body.ownerAccountId(),
                body.ownerCharacterId(),
                Boolean.TRUE.equals(body.enabled()),
                valueOr(body.displayName(), String.valueOf(character.get("character_name"))),
                valueOr(body.defaultMode(), "IDLE"),
                valueOr(body.behaviorProfile(), "default"),
                valueOr(body.personalityProfile(), "default"),
                body.scriptName(),
                Boolean.TRUE.equals(body.llmEnabled()));

        Map<String, Object> created = oneGame("""
                SELECT p.*, c.name character_name, c.level, c.job, c.world, c.map, a.name account_name
                FROM agent_profiles p
                JOIN characters c ON c.id = p.character_id
                JOIN accounts a ON a.id = c.accountid
                WHERE p.character_id=?
                """, body.characterId());
        audit(principal, "AGENT_PROFILE_CREATE", "agent:" + created.get("id"), null, created, "Created through Server CMS");
        return created;
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable int id, @Valid @RequestBody UpdateAgent body, Principal principal) {
        Map<String, Object> before = agent(id);
        gameJdbc.update("""
                UPDATE agent_profiles
                SET enabled=?, display_name=?, default_mode=?, behavior_profile=?,
                    personality_profile=?, script_name=?, llm_enabled=?
                WHERE id=?
                """,
                body.enabled(),
                valueOr(body.displayName(), String.valueOf(before.get("character_name"))),
                valueOr(body.defaultMode(), "IDLE"),
                valueOr(body.behaviorProfile(), "default"),
                valueOr(body.personalityProfile(), "default"),
                body.scriptName(),
                Boolean.TRUE.equals(body.llmEnabled()),
                id);
        Map<String, Object> after = agent(id);
        audit(principal, "AGENT_PROFILE_UPDATE", "agent:" + id, before, after, valueOr(body.reason(), "Updated through Server CMS"));
        return after;
    }

    @GetMapping("/characters")
    List<Map<String, Object>> characters(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT c.id, c.name character_name, c.level, c.job, c.world, c.map,
                       a.id account_id, a.name account_name,
                       p.id agent_profile_id
                FROM characters c
                JOIN accounts a ON a.id = c.accountid
                LEFT JOIN agent_profiles p ON p.character_id = c.id
                WHERE c.name LIKE ? OR a.name LIKE ? OR CAST(c.id AS CHAR) LIKE ?
                ORDER BY p.id IS NOT NULL, c.world, c.level DESC, c.name
                LIMIT 50
                """, like(q), like(q), like(q));
    }

    @GetMapping("/{id}/spawn-plan")
    Map<String, Object> spawnPlan(@PathVariable int id) {
        Map<String, Object> row = agent(id);
        boolean enabled = Boolean.TRUE.equals(row.get("enabled"));
        boolean accountOnline = Number.class.cast(row.get("loggedin")).intValue() != 0;
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("ready", enabled && !accountOnline);
        plan.put("world", row.get("world"));
        plan.put("channel", 1);
        plan.put("mapId", row.get("map"));
        plan.put("spawnPoint", row.get("spawnpoint"));
        plan.put("message", !enabled ? "Agent profile is disabled" : accountOnline ? "Account is currently logged in" : "Ready for future dormant control shell");
        return plan;
    }

    @GetMapping("/{id}/sessions")
    List<Map<String, Object>> sessions(@PathVariable int id) {
        return gameJdbc.queryForList("""
                SELECT * FROM agent_runtime_sessions
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 30
                """, id);
    }

    @GetMapping("/{id}/logs")
    List<Map<String, Object>> logs(@PathVariable int id) {
        return gameJdbc.queryForList("""
                SELECT * FROM agent_action_logs
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 100
                """, id);
    }

    @GetMapping("/{id}/memory")
    List<Map<String, Object>> memory(@PathVariable int id) {
        agent(id);
        return gameJdbc.queryForList("""
                SELECT *
                FROM agent_memory_events
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 50
                """, id);
    }

    @GetMapping("/{id}/goals")
    List<Map<String, Object>> goals(@PathVariable int id) {
        agent(id);
        return gameJdbc.queryForList("""
                SELECT *
                FROM agent_goals
                WHERE agent_profile_id=?
                ORDER BY FIELD(status, 'ACTIVE', 'RUNNING', 'PENDING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'),
                         priority DESC, id ASC
                LIMIT 100
                """, id);
    }

    @PostMapping("/{id}/goals")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createGoal(@PathVariable int id, @Valid @RequestBody CreateGoal body, Principal principal) {
        agent(id);
        gameJdbc.update("""
                INSERT INTO agent_goals(agent_profile_id, goal_type, priority, status, target_world, target_channel,
                                        target_map, target_ref, parameters_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                valueOr(body.goalType(), "IDLE"),
                body.priority() == null ? 0 : body.priority(),
                valueOr(body.status(), "PENDING"),
                body.targetWorld(),
                body.targetChannel(),
                body.targetMap(),
                body.targetRef(),
                body.parametersJson());
        Map<String, Object> created = oneGame("""
                SELECT *
                FROM agent_goals
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 1
                """, id);
        audit(principal, "AGENT_GOAL_CREATE", "agent:" + id + ":goal:" + created.get("id"), null, created,
                "Created agent goal through Server CMS");
        return created;
    }

    @PutMapping("/{id}/goals/{goalId}/status")
    Map<String, Object> updateGoalStatus(@PathVariable int id, @PathVariable long goalId,
                                         @Valid @RequestBody UpdateGoalStatus body, Principal principal) {
        agent(id);
        String status = valueOr(body.status(), "").trim().toUpperCase();
        if (!List.of("PENDING", "ACTIVE", "RUNNING", "PAUSED", "COMPLETED", "FAILED", "CANCELLED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported goal status");
        }

        Map<String, Object> before = oneGame("""
                SELECT *
                FROM agent_goals
                WHERE id=? AND agent_profile_id=?
                """, goalId, id);
        gameJdbc.update("""
                UPDATE agent_goals
                SET status=?,
                    completed_at = CASE WHEN ? IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN CURRENT_TIMESTAMP ELSE completed_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id=? AND agent_profile_id=?
                """, status, status, goalId, id);
        Map<String, Object> after = oneGame("""
                SELECT *
                FROM agent_goals
                WHERE id=? AND agent_profile_id=?
                """, goalId, id);
        audit(principal, "AGENT_GOAL_STATUS", "agent:" + id + ":goal:" + goalId, before, after,
                valueOr(body.reason(), "Updated agent goal status through Server CMS"));
        return after;
    }

    @GetMapping("/{id}/policies")
    List<Map<String, Object>> policies(@PathVariable int id) {
        agent(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCapabilityPolicy capability : CAPABILITY_POLICIES) {
            String globalValue = policyValue(0, capability.key());
            String agentValue = policyValue(id, capability.key());
            boolean effective = parseBoolean(agentValue == null ? globalValue : agentValue, capability.defaultEnabled());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", capability.key());
            row.put("label", capability.label());
            row.put("description", capability.description());
            row.put("defaultEnabled", capability.defaultEnabled());
            row.put("globalValue", globalValue);
            row.put("agentValue", agentValue);
            row.put("effective", effective);
            row.put("overridden", agentValue != null);
            rows.add(row);
        }
        return rows;
    }

    @PutMapping("/{id}/policies/{key}")
    Map<String, Object> updatePolicy(@PathVariable int id, @PathVariable String key,
                                     @Valid @RequestBody UpdatePolicy body, Principal principal) {
        agent(id);
        AgentCapabilityPolicy capability = CAPABILITY_POLICIES.stream()
                .filter(policy -> policy.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent policy"));

        String previousAgentValue = policyValue(id, key);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("agentValue", previousAgentValue);
        before.put("effective", parseBoolean(previousAgentValue, capability.defaultEnabled()));
        gameJdbc.update("""
                INSERT INTO agent_policies(agent_profile_id, scope, policy_key, policy_value)
                VALUES (?, 'AGENT', ?, ?)
                ON DUPLICATE KEY UPDATE policy_value=VALUES(policy_value)
                """, id, key, Boolean.toString(Boolean.TRUE.equals(body.enabled())));
        Map<String, Object> after = policies(id).stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_POLICY_SET", "agent:" + id + ":" + key, before, after,
                valueOr(body.reason(), "Updated agent policy through Server CMS"));
        return after;
    }

    @DeleteMapping("/{id}/policies/{key}")
    Map<String, Object> resetPolicy(@PathVariable int id, @PathVariable String key,
                                    @RequestParam(defaultValue = "Reset agent policy through Server CMS") String reason,
                                    Principal principal) {
        agent(id);
        if (CAPABILITY_POLICIES.stream().noneMatch(policy -> policy.key().equals(key))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent policy");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("agentValue", policyValue(id, key));
        gameJdbc.update("DELETE FROM agent_policies WHERE agent_profile_id=? AND policy_key=?", id, key);
        Map<String, Object> after = policies(id).stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_POLICY_RESET", "agent:" + id + ":" + key, before, after, reason);
        return after;
    }

    @PostMapping("/{id}/runtime/{action}")
    Map<String, Object> runtimeAction(@PathVariable int id, @PathVariable String action, Principal principal) {
        if (!List.of("prepare", "enter", "tick", "release").contains(action)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent runtime action");
        }

        Map<String, Object> before = agent(id);
        Map<String, Object> result = bridge.agentAction(id, action);
        audit(principal, "AGENT_RUNTIME_" + action.toUpperCase(), "agent:" + id, before, result,
                "Manual agent runtime action through Server CMS");
        return result;
    }

    private Map<String, Object> oneGame(String sql, Object... args) {
        List<Map<String, Object>> rows = gameJdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent record not found");
        }
        return rows.getFirst();
    }

    private String like(String value) {
        return "%" + value + "%";
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String policyValue(int agentProfileId, String key) {
        List<String> rows = gameJdbc.queryForList("""
                SELECT policy_value FROM agent_policies
                WHERE agent_profile_id=? AND policy_key=?
                ORDER BY id DESC LIMIT 1
                """, String.class, agentProfileId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "y", "enabled", "on" -> true;
            default -> false;
        };
    }

    private void audit(Principal principal, String action, String key, Object before, Object after, String reason) {
        try {
            Long actor = cmsJdbc.queryForObject("SELECT id FROM server_cms_users WHERE username=?", Long.class, principal.getName());
            cmsJdbc.update("""
                    INSERT INTO server_cms_audit(actor_user_id,action,entity_key,before_json,after_json,reason,outcome)
                    VALUES (?,?,?,?,?,?,'SAVED')
                    """, actor, action, key, before == null ? null : mapper.writeValueAsString(before),
                    after == null ? null : mapper.writeValueAsString(after), reason);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to write audit record", exception);
        }
    }

    record CreateAgent(@NotNull Integer characterId, String ownershipType, Integer ownerAccountId,
                       Integer ownerCharacterId, Boolean enabled, String displayName, String defaultMode,
                       String behaviorProfile, String personalityProfile, String scriptName, Boolean llmEnabled) {}

    record UpdateAgent(Boolean enabled, String displayName, String defaultMode, String behaviorProfile,
                       String personalityProfile, String scriptName, Boolean llmEnabled, String reason) {}

    record UpdatePolicy(Boolean enabled, String reason) {}

    record AgentCapabilityPolicy(String key, String label, String description, boolean defaultEnabled) {}

    record CreateGoal(
            String goalType,
            Integer priority,
            String status,
            Integer targetWorld,
            Integer targetChannel,
            Integer targetMap,
            String targetRef,
            String parametersJson
    ) {}

    record UpdateGoalStatus(String status, String reason) {}
}
