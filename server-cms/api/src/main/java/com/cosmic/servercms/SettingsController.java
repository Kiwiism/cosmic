package com.cosmic.servercms;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api")
public class SettingsController {
    private final JdbcTemplate jdbc;
    private final BridgeClient bridge;
    private final ObjectMapper mapper;

    public SettingsController(JdbcTemplate jdbc, BridgeClient bridge, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.bridge = bridge;
        this.mapper = mapper;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard() {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) settings,
                 SUM(origin_type='YAML_EXISTING') yamlSettings,
                 SUM(origin_type='JAVA_HARDCODED') hardcodedSettings,
                 SUM(origin_type='SERVER_CMS_NEW') newSettings,
                 SUM(apply_mode='RESTART') restartSettings
                FROM setting_catalog
                """);
        counts = new LinkedHashMap<>(counts);
        counts.put("overrides", jdbc.queryForObject("SELECT COUNT(*) FROM setting_overrides WHERE active=1", Long.class));
        counts.put("server", bridge.health());
        return counts;
    }

    @GetMapping("/settings")
    List<Map<String, Object>> settings(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(defaultValue = "") String category,
                                       @RequestParam(defaultValue = "") String origin,
                                       @RequestParam(defaultValue = "") String compatibility) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.*,o.id override_id,o.value_text override_value,o.reason override_reason,
                       o.updated_at,o.scope_id,
                       COALESCE(o.value_text,c.default_value) effective_value
                FROM setting_catalog c
                LEFT JOIN setting_overrides o ON o.setting_key=c.setting_key AND o.active=1
                  AND o.scope_type=c.scope_type AND o.scope_id=0
                WHERE (c.display_name LIKE ? OR c.setting_key LIKE ? OR c.description LIKE ?)
                """);
        List<Object> params = new ArrayList<>(List.of("%" + q + "%", "%" + q + "%", "%" + q + "%"));
        if (!category.isBlank()) { sql.append(" AND c.category=?"); params.add(category); }
        if (!origin.isBlank()) { sql.append(" AND c.origin_type=?"); params.add(origin); }
        if (!compatibility.isBlank()) { sql.append(" AND c.compatibility=?"); params.add(compatibility); }
        sql.append(" ORDER BY c.category,c.sort_order,c.display_name");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @GetMapping("/settings/categories")
    List<String> categories() {
        return jdbc.queryForList("SELECT DISTINCT category FROM setting_catalog ORDER BY category", String.class);
    }

    @PutMapping("/settings/{key}")
    Map<String, Object> update(@PathVariable String key, @Valid @RequestBody Update body, Principal principal) {
        Map<String, Object> setting = one("SELECT * FROM setting_catalog WHERE setting_key=?", key);
        if (!Boolean.TRUE.equals(setting.get("editable"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This bootstrap setting is read-only because it is consumed before the Server CMS database is available");
        }
        validate(setting, body.value());
        String scope = String.valueOf(setting.get("scope_type"));
        List<Map<String, Object>> previous = jdbc.queryForList(
                "SELECT * FROM setting_overrides WHERE setting_key=? AND scope_type=? AND scope_id=0", key, scope);
        Long actor = jdbc.queryForObject("SELECT id FROM server_cms_users WHERE username=?", Long.class, principal.getName());
        jdbc.update("""
                INSERT INTO setting_overrides(setting_key,scope_type,scope_id,value_text,reason,active,updated_by)
                VALUES (?,?,0,?,?,1,?)
                ON DUPLICATE KEY UPDATE value_text=VALUES(value_text),reason=VALUES(reason),
                  active=1,updated_by=VALUES(updated_by)
                """, key, scope, body.value(), body.reason(), actor);
        audit(actor, "SETTING_OVERRIDE_SET", key, previous.isEmpty() ? null : previous.getFirst(),
                Map.of("value", body.value()), body.reason());
        Map<String, Object> result = one("""
                SELECT c.*,o.value_text override_value,o.reason override_reason,o.updated_at
                FROM setting_catalog c JOIN setting_overrides o ON o.setting_key=c.setting_key
                WHERE c.setting_key=? AND o.scope_type=c.scope_type AND o.scope_id=0
                """, key);
        result = new LinkedHashMap<>(result);
        result.put("runtimeApplied", false);
        result.put("pendingRestart", true);
        return result;
    }

    @DeleteMapping("/settings/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@PathVariable String key, @RequestParam @NotBlank String reason, Principal principal) {
        Map<String, Object> setting = one("SELECT * FROM setting_catalog WHERE setting_key=?", key);
        if (!Boolean.TRUE.equals(setting.get("editable"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This bootstrap setting is read-only");
        }
        String scope = String.valueOf(setting.get("scope_type"));
        List<Map<String, Object>> previous = jdbc.queryForList(
                "SELECT * FROM setting_overrides WHERE setting_key=? AND scope_type=? AND scope_id=0", key, scope);
        Long actor = jdbc.queryForObject("SELECT id FROM server_cms_users WHERE username=?", Long.class, principal.getName());
        jdbc.update("DELETE FROM setting_overrides WHERE setting_key=? AND scope_type=? AND scope_id=0", key, scope);
        audit(actor, "SETTING_OVERRIDE_RESET", key, previous.isEmpty() ? null : previous.getFirst(), null, reason);
    }

    @GetMapping("/audit")
    List<Map<String, Object>> audit() {
        return jdbc.queryForList("""
                SELECT a.*,u.username FROM server_cms_audit a
                LEFT JOIN server_cms_users u ON u.id=a.actor_user_id
                ORDER BY a.id DESC LIMIT 200
                """);
    }

    private Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Setting not found");
        return rows.getFirst();
    }

    private void validate(Map<String, Object> setting, String value) {
        try {
            String type = String.valueOf(setting.get("value_type"));
            BigDecimal number = switch (type) {
                case "BOOLEAN" -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException();
                    yield null;
                }
                case "INTEGER", "LONG", "DOUBLE" -> new BigDecimal(value);
                default -> null;
            };
            if (number != null) {
                BigDecimal min = (BigDecimal) setting.get("min_value");
                BigDecimal max = (BigDecimal) setting.get("max_value");
                if ((min != null && number.compareTo(min) < 0) || (max != null && number.compareTo(max) > 0)) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Value does not match the setting type or range");
        }
    }

    private void audit(Long actor, String action, String key, Object before, Object after, String reason) {
        try {
            jdbc.update("""
                    INSERT INTO server_cms_audit(actor_user_id,action,entity_key,before_json,after_json,reason,outcome)
                    VALUES (?,?,?,?,?,?,'SAVED')
                    """, actor, action, key, before == null ? null : mapper.writeValueAsString(before),
                    after == null ? null : mapper.writeValueAsString(after), reason);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to write audit record", exception);
        }
    }

    record Update(@NotBlank String value, @NotBlank String reason) {}
}
