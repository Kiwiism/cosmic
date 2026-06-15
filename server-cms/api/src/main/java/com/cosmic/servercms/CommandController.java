package com.cosmic.servercms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/commands")
public class CommandController {
    private static final Pattern REGISTRATION = Pattern.compile(
            "addCommand\\(\"([^\"]+)\",\\s*(\\d+),\\s*([A-Za-z0-9_]+)\\.class\\);\\s*(?://\\s*(.*))?");
    private final JdbcTemplate jdbc;
    private final Path source;

    public CommandController(JdbcTemplate jdbc, @Value("${cosmic.project-path:.}") String projectPath) {
        this.jdbc = jdbc;
        source = Path.of(projectPath).toAbsolutePath().normalize()
                .resolve("src/main/java/client/command/CommandsExecutor.java");
    }

    @GetMapping
    List<Map<String, Object>> commands(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(required = false) Integer level) {
        Map<String, Map<String, Object>> overrides = new HashMap<>();
        jdbc.queryForList("SELECT * FROM command_overrides").forEach(
                row -> overrides.put(String.valueOf(row.get("command_name")), row));
        String needle = q.toLowerCase(Locale.ROOT);
        return sourceCommands().stream().filter(command ->
                        needle.isBlank() || String.valueOf(command.get("name")).contains(needle)
                                || String.valueOf(command.get("description")).toLowerCase(Locale.ROOT).contains(needle))
                .map(command -> {
                    Map<String, Object> result = new LinkedHashMap<>(command);
                    Map<String, Object> override = overrides.get(command.get("name"));
                    result.put("enabled", override == null || Boolean.TRUE.equals(override.get("enabled")));
                    result.put("effectiveLevel", override == null ? command.get("originalLevel")
                            : override.get("required_level"));
                    result.put("overridden", override != null);
                    result.put("reason", override == null ? null : override.get("reason"));
                    return result;
                })
                .filter(command -> level == null || Objects.equals(command.get("effectiveLevel"), level))
                .toList();
    }

    @PutMapping("/{name}")
    Map<String, Object> update(@PathVariable String name, @Valid @RequestBody Update body, Principal principal) {
        Map<String, Object> original = sourceCommands().stream()
                .filter(command -> command.get("name").equals(name)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Command not found"));
        Long actor = jdbc.queryForObject("SELECT id FROM server_cms_users WHERE username=?",
                Long.class, principal.getName());
        jdbc.update("""
                INSERT INTO command_overrides(command_name,enabled,required_level,reason,updated_by)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),required_level=VALUES(required_level),
                  reason=VALUES(reason),updated_by=VALUES(updated_by)
                """, name, body.enabled(), body.requiredLevel(), body.reason(), actor);
        jdbc.update("""
                INSERT INTO server_cms_audit(actor_user_id,action,entity_key,before_json,after_json,reason,outcome)
                VALUES (?,'COMMAND_POLICY_SET',?,?,?,?,'SAVED')
                """, actor, name, null,
                "{\"enabled\":" + body.enabled() + ",\"requiredLevel\":" + body.requiredLevel() + "}",
                body.reason());
        Map<String, Object> result = new LinkedHashMap<>(original);
        result.put("enabled", body.enabled());
        result.put("effectiveLevel", body.requiredLevel());
        result.put("overridden", true);
        return result;
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reset(@PathVariable String name, @RequestParam @NotBlank String reason, Principal principal) {
        Long actor = jdbc.queryForObject("SELECT id FROM server_cms_users WHERE username=?",
                Long.class, principal.getName());
        jdbc.update("DELETE FROM command_overrides WHERE command_name=?", name);
        jdbc.update("""
                INSERT INTO server_cms_audit(actor_user_id,action,entity_key,reason,outcome)
                VALUES (?,'COMMAND_POLICY_RESET',?,?,'SAVED')
                """, actor, name, reason);
    }

    private List<Map<String, Object>> sourceCommands() {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (String line : Files.readAllLines(source)) {
                Matcher matcher = REGISTRATION.matcher(line.trim());
                if (matcher.find()) {
                    Map<String, Object> command = new LinkedHashMap<>();
                    command.put("name", matcher.group(1));
                    command.put("originalLevel", Integer.parseInt(matcher.group(2)));
                    command.put("implementation", matcher.group(3));
                    command.put("description", matcher.group(4) == null ? "" : matcher.group(4).trim());
                    command.put("sourceFile", "src/main/java/client/command/CommandsExecutor.java");
                    result.add(command);
                }
            }
            return result;
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cannot read Cosmic command registrations from " + source, exception);
        }
    }

    record Update(boolean enabled, @Min(0) @Max(6) int requiredLevel, @NotBlank String reason) {}
}
