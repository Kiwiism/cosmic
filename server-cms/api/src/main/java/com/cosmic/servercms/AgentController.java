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
}
