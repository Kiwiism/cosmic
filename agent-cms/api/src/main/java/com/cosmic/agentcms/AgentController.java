package com.cosmic.agentcms;

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
            new AgentCapabilityPolicy("intent.self.enabled", "Self timing", "Allows no-op IDLE and WAIT runtime intents.", true),
            new AgentCapabilityPolicy("intent.chat.enabled", "Chat", "Allows SAY intents to broadcast normal map chat.", false),
            new AgentCapabilityPolicy("intent.navigation.enabled", "Navigation", "Allows ROAM, MOVE, MAP, FOLLOW and PORTAL intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.combat.enabled", "Combat", "Allows future ATTACK and GRIND intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.loot.enabled", "Loot", "Allows nearby visible drop pickup through normal server pickup rules.", false),
            new AgentCapabilityPolicy("intent.npc.enabled", "NPC interaction", "Allows future NPC/TALK intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.shop.enabled", "Shop interaction", "Allows future SHOP/MERCHANT intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.trade.enabled", "Trade", "Allows future TRADE intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.party.enabled", "Party", "Allows future PARTY intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.inventory.enabled", "Inventory", "Allows future USEITEM and EQUIP intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.script.enabled", "Script fallback", "Allows unknown script intents to pass the policy gate. Keep disabled unless debugging parser behavior.", false)
    );
    private static final List<AgentCooldownPolicy> COOLDOWN_POLICIES = List.of(
            new AgentCooldownPolicy("cooldown.self.millis", "Self capability", "Default pacing for IDLE and WAIT no-op intents.", 0),
            new AgentCooldownPolicy("cooldown.chat.millis", "Chat capability", "Default pacing for chat intents.", 10_000),
            new AgentCooldownPolicy("cooldown.navigation.millis", "Navigation capability", "Default pacing for route, movement and portal intents.", 1_000),
            new AgentCooldownPolicy("cooldown.combat.millis", "Combat capability", "Default pacing for future attack and grind intents.", 1_000),
            new AgentCooldownPolicy("cooldown.loot.millis", "Loot capability", "Default pacing for nearby pickup attempts.", 750),
            new AgentCooldownPolicy("cooldown.npc.millis", "NPC capability", "Default pacing for future NPC interaction intents.", 2_000),
            new AgentCooldownPolicy("cooldown.shop.millis", "Shop capability", "Default pacing for future shop interaction intents.", 2_000),
            new AgentCooldownPolicy("cooldown.trade.millis", "Trade capability", "Default pacing for future trade intents.", 5_000),
            new AgentCooldownPolicy("cooldown.party.millis", "Party capability", "Default pacing for future party intents.", 3_000),
            new AgentCooldownPolicy("cooldown.inventory.millis", "Inventory capability", "Default pacing for future item use and equip intents.", 1_500),
            new AgentCooldownPolicy("cooldown.script.millis", "Script fallback capability", "Default pacing for unknown script intents.", 1_000),
            new AgentCooldownPolicy("cooldown.say.millis", "SAY override", "Optional override for SAY intent pacing. Falls back to chat capability when unset.", 10_000),
            new AgentCooldownPolicy("cooldown.move_to_map.millis", "MOVE_TO_MAP override", "Optional override for multi-map navigation pacing.", 1_000),
            new AgentCooldownPolicy("cooldown.follow_character.millis", "FOLLOW_CHARACTER override", "Optional override for companion/follow pacing.", 1_000),
            new AgentCooldownPolicy("cooldown.use_portal.millis", "USE_PORTAL override", "Optional override for portal transition pacing.", 1_000)
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
        audit(principal, "AGENT_PROFILE_CREATE", "agent:" + created.get("id"), null, created, "Created through Agent CMS");
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
        audit(principal, "AGENT_PROFILE_UPDATE", "agent:" + id, before, after, valueOr(body.reason(), "Updated through Agent CMS"));
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

    @GetMapping("/{id}/runtime/status")
    Map<String, Object> runtimeStatus(@PathVariable int id) {
        Map<String, Object> profile = agent(id);
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("profile", profile);
        status.put("latestSession", optionalGame("""
                SELECT *
                FROM agent_runtime_sessions
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 1
                """, id));
        status.put("latestAction", optionalGame("""
                SELECT *
                FROM agent_action_logs
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 1
                """, id));
        status.put("latestMemory", optionalGame("""
                SELECT *
                FROM agent_memory_events
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 1
                """, id));
        status.put("latestChat", optionalGame("""
                SELECT *
                FROM agent_chat_logs
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 1
                """, id));
        status.put("latestEconomy", optionalGame("""
                SELECT *
                FROM agent_economy_ledger
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 1
                """, id));
        return status;
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

    @GetMapping("/scripts")
    List<Map<String, Object>> scripts(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT id, name, version, enabled, script_type, body, created_by, created_at, updated_at
                FROM agent_scripts
                WHERE name LIKE ? OR body LIKE ? OR CAST(id AS CHAR) LIKE ?
                ORDER BY enabled DESC, name, version DESC, id DESC
                LIMIT 200
                """, like(q), like(q), like(q));
    }

    @PostMapping("/scripts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createScript(@Valid @RequestBody SaveScript body, Principal principal) {
        gameJdbc.update("""
                INSERT INTO agent_scripts(name, version, enabled, script_type, body, created_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                valueOr(body.name(), "default"),
                body.version() == null ? 1 : body.version(),
                Boolean.TRUE.equals(body.enabled()),
                valueOr(body.scriptType(), "TEXT"),
                valueOr(body.body(), ""),
                principal.getName());
        Map<String, Object> created = oneGame("""
                SELECT id, name, version, enabled, script_type, body, created_by, created_at, updated_at
                FROM agent_scripts
                ORDER BY id DESC LIMIT 1
                """);
        audit(principal, "AGENT_SCRIPT_CREATE", "agent_script:" + created.get("id"), null, created,
                valueOr(body.reason(), "Created agent script through Agent CMS"));
        return created;
    }

    @PutMapping("/scripts/{scriptId}")
    Map<String, Object> updateScript(@PathVariable int scriptId, @Valid @RequestBody SaveScript body, Principal principal) {
        Map<String, Object> before = oneGame("""
                SELECT id, name, version, enabled, script_type, body, created_by, created_at, updated_at
                FROM agent_scripts
                WHERE id=?
                """, scriptId);
        gameJdbc.update("""
                UPDATE agent_scripts
                SET name=?, version=?, enabled=?, script_type=?, body=?
                WHERE id=?
                """,
                valueOr(body.name(), String.valueOf(before.get("name"))),
                body.version() == null ? Number.class.cast(before.get("version")).intValue() : body.version(),
                Boolean.TRUE.equals(body.enabled()),
                valueOr(body.scriptType(), String.valueOf(before.get("script_type"))),
                valueOr(body.body(), ""),
                scriptId);
        Map<String, Object> after = oneGame("""
                SELECT id, name, version, enabled, script_type, body, created_by, created_at, updated_at
                FROM agent_scripts
                WHERE id=?
                """, scriptId);
        audit(principal, "AGENT_SCRIPT_UPDATE", "agent_script:" + scriptId, before, after,
                valueOr(body.reason(), "Updated agent script through Agent CMS"));
        return after;
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
        maybeUpsertCompanionRelationship(id, created, principal);
        audit(principal, "AGENT_GOAL_CREATE", "agent:" + id + ":goal:" + created.get("id"), null, created,
                "Created agent goal through Agent CMS");
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
                valueOr(body.reason(), "Updated agent goal status through Agent CMS"));
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
                valueOr(body.reason(), "Updated agent policy through Agent CMS"));
        return after;
    }

    @DeleteMapping("/{id}/policies/{key}")
    Map<String, Object> resetPolicy(@PathVariable int id, @PathVariable String key,
                                    @RequestParam(defaultValue = "Reset agent policy through Agent CMS") String reason,
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

    @GetMapping("/{id}/cooldowns")
    List<Map<String, Object>> cooldowns(@PathVariable int id) {
        agent(id);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCooldownPolicy cooldown : COOLDOWN_POLICIES) {
            String globalValue = policyValue(0, cooldown.key());
            String agentValue = policyValue(id, cooldown.key());
            long effective = parseNonNegativeLong(agentValue == null ? globalValue : agentValue, cooldown.defaultMillis());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", cooldown.key());
            row.put("label", cooldown.label());
            row.put("description", cooldown.description());
            row.put("defaultMillis", cooldown.defaultMillis());
            row.put("globalValue", globalValue);
            row.put("agentValue", agentValue);
            row.put("effectiveMillis", effective);
            row.put("overridden", agentValue != null);
            rows.add(row);
        }
        return rows;
    }

    @PutMapping("/{id}/cooldowns/{key}")
    Map<String, Object> updateCooldown(@PathVariable int id, @PathVariable String key,
                                       @Valid @RequestBody UpdateCooldown body, Principal principal) {
        agent(id);
        AgentCooldownPolicy cooldown = COOLDOWN_POLICIES.stream()
                .filter(policy -> policy.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent cooldown"));
        long millis = body.millis() == null ? cooldown.defaultMillis() : body.millis();
        if (millis < 0 || millis > 3_600_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cooldown must be between 0 and 3,600,000 ms");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("agentValue", policyValue(id, key));
        gameJdbc.update("""
                INSERT INTO agent_policies(agent_profile_id, scope, policy_key, policy_value)
                VALUES (?, 'AGENT', ?, ?)
                ON DUPLICATE KEY UPDATE policy_value=VALUES(policy_value)
                """, id, key, Long.toString(millis));
        Map<String, Object> after = cooldowns(id).stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_COOLDOWN_SET", "agent:" + id + ":" + key, before, after,
                valueOr(body.reason(), "Updated agent cooldown through Agent CMS"));
        return after;
    }

    @DeleteMapping("/{id}/cooldowns/{key}")
    Map<String, Object> resetCooldown(@PathVariable int id, @PathVariable String key,
                                      @RequestParam(defaultValue = "Reset agent cooldown through Agent CMS") String reason,
                                      Principal principal) {
        agent(id);
        if (COOLDOWN_POLICIES.stream().noneMatch(policy -> policy.key().equals(key))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown agent cooldown");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("agentValue", policyValue(id, key));
        gameJdbc.update("DELETE FROM agent_policies WHERE agent_profile_id=? AND policy_key=?", id, key);
        Map<String, Object> after = cooldowns(id).stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_COOLDOWN_RESET", "agent:" + id + ":" + key, before, after, reason);
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
                "Manual agent runtime action through Agent CMS");
        return result;
    }

    @GetMapping("/runtime/sessions")
    List<Map<String, Object>> runtimeSessions(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT s.*, p.display_name, c.name character_name, a.name account_name
                FROM agent_runtime_sessions s
                JOIN agent_profiles p ON p.id = s.agent_profile_id
                JOIN characters c ON c.id = s.character_id
                JOIN accounts a ON a.id = c.accountid
                WHERE c.name LIKE ? OR a.name LIKE ? OR p.display_name LIKE ?
                   OR s.state LIKE ? OR s.current_task LIKE ?
                ORDER BY s.id DESC
                LIMIT 200
                """, like(q), like(q), like(q), like(q), like(q));
    }

    @GetMapping("/runtime/summary")
    Map<String, Object> runtimeSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("sessions", oneSummary("""
                SELECT
                    SUM(CASE WHEN ended_at IS NULL THEN 1 ELSE 0 END) AS open_sessions,
                    SUM(CASE
                        WHEN ended_at IS NULL
                         AND COALESCE(last_tick_at, started_at) < DATE_SUB(NOW(), INTERVAL 2 MINUTE)
                        THEN 1 ELSE 0 END) AS stale_sessions,
                    SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END) AS failed_sessions
                FROM agent_runtime_sessions
                """));
        summary.put("actions24h", oneSummary("""
                SELECT
                    COUNT(*) AS total_actions,
                    SUM(CASE WHEN status = 'OK' THEN 1 ELSE 0 END) AS ok_actions,
                    SUM(CASE WHEN status = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked_actions,
                    SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_actions,
                    SUM(CASE
                        WHEN status = 'BLOCKED'
                         AND details_json LIKE '%"cooldownState":"BLOCKED"%'
                        THEN 1 ELSE 0 END) AS cooldown_blocks
                FROM agent_action_logs
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                """));
        summary.put("latestProblems", gameJdbc.queryForList("""
                SELECT l.*, p.display_name, c.name character_name
                FROM agent_action_logs l
                JOIN agent_profiles p ON p.id = l.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                WHERE l.status IN ('BLOCKED', 'FAILED')
                ORDER BY l.id DESC
                LIMIT 10
                """));
        return summary;
    }

    @GetMapping("/social/relationships")
    List<Map<String, Object>> relationships(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT r.*, p.display_name, c.name agent_character_name,
                       rc.name related_character_name, ra.name related_account_name
                FROM agent_relationships r
                JOIN agent_profiles p ON p.id = r.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                JOIN characters rc ON rc.id = r.related_character_id
                JOIN accounts ra ON ra.id = rc.accountid
                WHERE p.display_name LIKE ? OR c.name LIKE ? OR rc.name LIKE ?
                   OR ra.name LIKE ? OR r.relationship_type LIKE ? OR r.notes LIKE ?
                ORDER BY r.updated_at DESC
                LIMIT 200
                """, like(q), like(q), like(q), like(q), like(q), like(q));
    }

    @GetMapping("/social/chat")
    List<Map<String, Object>> chatLogs(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT l.*, p.display_name, c.name agent_character_name,
                       sender.name sender_name, recipient.name recipient_name
                FROM agent_chat_logs l
                JOIN agent_profiles p ON p.id = l.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                LEFT JOIN characters sender ON sender.id = l.sender_character_id
                LEFT JOIN characters recipient ON recipient.id = l.recipient_character_id
                WHERE p.display_name LIKE ? OR c.name LIKE ? OR l.channel_type LIKE ?
                   OR l.direction LIKE ? OR l.message LIKE ?
                ORDER BY l.id DESC
                LIMIT 200
                """, like(q), like(q), like(q), like(q), like(q));
    }

    @GetMapping("/social/proximity")
    List<Map<String, Object>> proximity(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT m.*, p.display_name, c.name agent_character_name, c.world, c.map
                FROM agent_memory_events m
                JOIN agent_profiles p ON p.id = m.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                WHERE m.event_type = 'PILOT_TICK'
                  AND m.details_json LIKE '%"players"%'
                  AND (p.display_name LIKE ? OR c.name LIKE ? OR m.summary LIKE ? OR m.details_json LIKE ?)
                ORDER BY m.id DESC
                LIMIT 100
                """, like(q), like(q), like(q), like(q));
    }

    @GetMapping("/economy/ledger")
    List<Map<String, Object>> economyLedger(@RequestParam(defaultValue = "") String q) {
        return gameJdbc.queryForList("""
                SELECT e.*, p.display_name, c.name agent_character_name, counterparty.name counterparty_name
                FROM agent_economy_ledger e
                JOIN agent_profiles p ON p.id = e.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                LEFT JOIN characters counterparty ON counterparty.id = e.counterparty_character_id
                WHERE p.display_name LIKE ? OR c.name LIKE ? OR e.entry_type LIKE ?
                   OR e.source_type LIKE ? OR CAST(e.item_id AS CHAR) LIKE ?
                   OR counterparty.name LIKE ?
                ORDER BY e.id DESC
                LIMIT 200
                """, like(q), like(q), like(q), like(q), like(q), like(q));
    }

    private Map<String, Object> oneGame(String sql, Object... args) {
        List<Map<String, Object>> rows = gameJdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent record not found");
        }
        return rows.getFirst();
    }

    private Map<String, Object> optionalGame(String sql, Object... args) {
        List<Map<String, Object>> rows = gameJdbc.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> oneSummary(String sql) {
        List<Map<String, Object>> rows = gameJdbc.queryForList(sql);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
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

    private long parseNonNegativeLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? fallback : parsed;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void maybeUpsertCompanionRelationship(int agentProfileId, Map<String, Object> goal, Principal principal) {
        String goalType = String.valueOf(goal.getOrDefault("goal_type", "")).trim().toUpperCase();
        if (!List.of("FOLLOW", "FOLLOW_CHARACTER", "COMPANION", "HANG_AROUND").contains(goalType)) {
            return;
        }

        Integer relatedCharacterId = resolveCharacterId(String.valueOf(goal.getOrDefault("target_ref", "")));
        if (relatedCharacterId == null) {
            return;
        }

        gameJdbc.update("""
                INSERT INTO agent_relationships(agent_profile_id, related_character_id, relationship_type,
                                                trust_score, affinity_score, notes)
                VALUES (?, ?, 'COMPANION', 0, 5, ?)
                ON DUPLICATE KEY UPDATE
                    relationship_type = 'COMPANION',
                    affinity_score = GREATEST(affinity_score, 5),
                    notes = VALUES(notes)
                """, agentProfileId, relatedCharacterId, "Assigned follow goal through Agent CMS by " + principal.getName());
    }

    private Integer resolveCharacterId(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        String trimmed = target.trim();
        List<Integer> rows;
        try {
            rows = gameJdbc.queryForList("SELECT id FROM characters WHERE id=? LIMIT 1", Integer.class, Integer.parseInt(trimmed));
        } catch (NumberFormatException ignored) {
            rows = gameJdbc.queryForList("SELECT id FROM characters WHERE name=? LIMIT 1", Integer.class, trimmed);
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void audit(Principal principal, String action, String key, Object before, Object after, String reason) {
        try {
            Long actor = cmsJdbc.queryForObject("SELECT id FROM agent_cms_users WHERE username=?", Long.class, principal.getName());
            cmsJdbc.update("""
                    INSERT INTO agent_cms_audit(actor_user_id,action,entity_key,before_json,after_json,reason,outcome)
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

    record UpdateCooldown(Long millis, String reason) {}

    record AgentCooldownPolicy(String key, String label, String description, long defaultMillis) {}

    record SaveScript(String name, Integer version, Boolean enabled, String scriptType, String body, String reason) {}

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
