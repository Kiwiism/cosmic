package com.cosmic.agentcms;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private static final int MAX_SCRIPT_REPEAT = 50;
    private final JdbcTemplate cmsJdbc;
    private final JdbcTemplate gameJdbc;
    private final ObjectMapper mapper;
    private final RestClient agentClientRest;
    private final CharacterCosmeticCatalog cosmeticCatalog;
    private final AgentCharacterCreator characterCreator;
    private final AgentMapCatalogImporter mapCatalogImporter;
    private final String gameSchema;
    private final String agentClientBaseUrl;
    private static final List<AgentCapabilityPolicy> CAPABILITY_POLICIES = List.of(
            new AgentCapabilityPolicy("intent.self.enabled", "Self timing", "Allows no-op IDLE and WAIT runtime intents.", true),
            new AgentCapabilityPolicy("intent.chat.enabled", "Chat", "Allows SAY intents to broadcast normal map chat.", false),
            new AgentCapabilityPolicy("intent.navigation.enabled", "Navigation", "Allows ROAM, MOVE, MAP, FOLLOW and PORTAL intents to pass the policy gate.", false),
            new AgentCapabilityPolicy("intent.combat.enabled", "Combat", "Allows ATTACK and GRIND intents to approach and basic-attack non-boss monsters.", false),
            new AgentCapabilityPolicy("intent.loot.enabled", "Loot", "Allows nearby visible drop pickup through normal server pickup rules.", false),
            new AgentCapabilityPolicy("intent.npc.enabled", "NPC interaction", "Allows NPC/TALK intents to approach visible NPCs and record readiness. Dialog scripts are not opened yet.", false),
            new AgentCapabilityPolicy("intent.shop.enabled", "Shop interaction", "Allows SHOP/MERCHANT intents to approach visible shop NPCs. BUY, SELL, RECHARGE and recovery aliases use normal shop rules.", false),
            new AgentCapabilityPolicy("intent.trade.enabled", "Trade readiness", "Allows TRADE readiness checks to inspect current trade state and nearby target players without opening trade or moving items/mesos.", false),
            new AgentCapabilityPolicy("intent.party.enabled", "Party readiness", "Allows PARTY readiness checks to inspect current party and nearby target players without inviting or joining.", false),
            new AgentCapabilityPolicy("intent.inventory.enabled", "Inventory", "Allows USEITEM and EQUIP actions. Recovery aliases consume one safe HP/MP item; EQUIP uses normal WZ slot and equip validation.", false),
            new AgentCapabilityPolicy("intent.skill.enabled", "Skill readiness", "Allows SKILL/CAST readiness and safe self-buff application for no-cooldown, no-HP-cost statup skills.", false),
            new AgentCapabilityPolicy("intent.express.enabled", "Expressive actions", "Allows facial expressions and safe expressive movement records such as F3, F7, jump, duck, and playful swing.", true),
            new AgentCapabilityPolicy("intent.script.enabled", "Script fallback", "Allows unknown script intents to pass the policy gate. Keep disabled unless debugging parser behavior.", false),
            new AgentCapabilityPolicy("safety.sustain.enabled", "Emergency sustain", "Allows an agent with no usable recovery item to receive a small HP/MP and meso safety top-up. Disable for strict normal-gameplay agents.", false)
    );
    private static final List<AgentCooldownPolicy> COOLDOWN_POLICIES = List.of(
            new AgentCooldownPolicy("cooldown.self.millis", "Self capability", "Default pacing for IDLE and WAIT no-op intents.", 0),
            new AgentCooldownPolicy("cooldown.chat.millis", "Chat capability", "Default pacing for chat intents.", 10_000),
            new AgentCooldownPolicy("cooldown.navigation.millis", "Navigation capability", "Default pacing for route, movement and portal intents.", 1_000),
            new AgentCooldownPolicy("cooldown.combat.millis", "Combat capability", "Default pacing for attack and grind intents.", 1_000),
            new AgentCooldownPolicy("cooldown.loot.millis", "Loot capability", "Default pacing for nearby pickup attempts.", 750),
            new AgentCooldownPolicy("cooldown.npc.millis", "NPC capability", "Default pacing for NPC approach and readiness intents.", 2_000),
            new AgentCooldownPolicy("cooldown.shop.millis", "Shop capability", "Default pacing for shop approach, buy, sell, recharge and recovery purchase intents.", 2_000),
            new AgentCooldownPolicy("cooldown.trade.millis", "Trade capability", "Default pacing for trade readiness checks.", 5_000),
            new AgentCooldownPolicy("cooldown.party.millis", "Party capability", "Default pacing for party readiness checks.", 3_000),
            new AgentCooldownPolicy("cooldown.inventory.millis", "Inventory capability", "Default pacing for item use and equip attempts.", 1_500),
            new AgentCooldownPolicy("cooldown.skill.millis", "Skill capability", "Default pacing for skill readiness and safe self-buff attempts.", 1_000),
            new AgentCooldownPolicy("cooldown.express.millis", "Expressive capability", "Default pacing for facial expressions and lively idle motions.", 2_000),
            new AgentCooldownPolicy("cooldown.script.millis", "Script fallback capability", "Default pacing for unknown script intents.", 1_000),
            new AgentCooldownPolicy("cooldown.say.millis", "SAY override", "Optional override for SAY intent pacing. Falls back to chat capability when unset.", 10_000),
            new AgentCooldownPolicy("cooldown.move_to_map.millis", "MOVE_TO_MAP override", "Optional override for multi-map navigation pacing.", 1_000),
            new AgentCooldownPolicy("cooldown.follow_character.millis", "FOLLOW_CHARACTER override", "Optional override for companion/follow pacing.", 1_000),
            new AgentCooldownPolicy("cooldown.use_portal.millis", "USE_PORTAL override", "Optional override for portal transition pacing.", 1_000),
            new AgentCooldownPolicy("safety.sustain.min_meso", "Emergency sustain meso floor", "Minimum meso balance restored when emergency sustain is enabled.", 5_000)
    );

    public AgentController(JdbcTemplate cmsJdbc, @Qualifier("gameJdbc") JdbcTemplate gameJdbc, ObjectMapper mapper,
                           RestClient.Builder restClientBuilder, CharacterCosmeticCatalog cosmeticCatalog,
                           AgentCharacterCreator characterCreator,
                           AgentMapCatalogImporter mapCatalogImporter,
                           @Value("${cosmic.game-database.url}") String gameDatabaseUrl,
                           @Value("${cosmic.agent-client.url:http://127.0.0.1:8094}") String agentClientUrl) {
        this.cmsJdbc = cmsJdbc;
        this.gameJdbc = gameJdbc;
        this.mapper = mapper;
        this.agentClientBaseUrl = trimTrailingSlash(agentClientUrl);
        this.agentClientRest = restClientBuilder.baseUrl(this.agentClientBaseUrl).build();
        this.cosmeticCatalog = cosmeticCatalog;
        this.characterCreator = characterCreator;
        this.mapCatalogImporter = mapCatalogImporter;
        this.gameSchema = schemaFromJdbcUrl(gameDatabaseUrl, "cosmic");
    }

    @GetMapping("/map-catalog/status")
    Map<String, Object> mapCatalogStatus() {
        return mapCatalogImporter.status();
    }

    @PostMapping("/map-catalog/import")
    Map<String, Object> importMapCatalog() {
        return mapCatalogImporter.importPortals();
    }

    @GetMapping
    List<Map<String, Object>> agents(@RequestParam(defaultValue = "") String q) {
        return agentQuery("""
                SELECT p.*, c.name character_name, c.level, c.job, c.world, c.map, c.hair, c.face, c.skincolor, c.gender, a.name account_name,
                       EXISTS(
                           SELECT 1
                           FROM agent_runtime_credentials rc
                           WHERE rc.agent_profile_id = p.id
                       ) has_runtime_credential,
                       (
                           SELECT GROUP_CONCAT(ii.itemid ORDER BY ii.position SEPARATOR ',')
                           FROM inventoryitems ii
                           WHERE ii.characterid = c.id
                             AND ii.inventorytype = -1
                             AND ii.position < 0
                       ) equipped_item_ids,
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
                SELECT p.*, c.name character_name, c.level, c.job, c.world, c.map, c.spawnpoint, c.hair, c.face, c.skincolor, c.gender,
                       EXISTS(
                           SELECT 1
                           FROM agent_runtime_credentials rc
                           WHERE rc.agent_profile_id = p.id
                       ) has_runtime_credential,
                       (
                           SELECT GROUP_CONCAT(ii.itemid ORDER BY ii.position SEPARATOR ',')
                           FROM inventoryitems ii
                           WHERE ii.characterid = c.id
                             AND ii.inventorytype = -1
                             AND ii.position < 0
                       ) equipped_item_ids,
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
        if (!cmsJdbc.queryForList("SELECT id FROM agent_profiles WHERE character_id=?", body.characterId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This character already has an agent profile");
        }

        cmsJdbc.update("""
                INSERT INTO agent_profiles(character_id, ownership_type, owner_account_id, owner_character_id,
                                           enabled, autonomous_enabled, display_name, script_name, llm_enabled, deployment_channel, allowed_channels)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                body.characterId(),
                valueOr(body.ownershipType(), "SERVER"),
                body.ownerAccountId(),
                body.ownerCharacterId(),
                Boolean.TRUE.equals(body.enabled()),
                Boolean.TRUE.equals(body.autonomousEnabled()),
                valueOr(body.displayName(), String.valueOf(character.get("character_name"))),
                body.scriptName(),
                Boolean.TRUE.equals(body.llmEnabled()),
                sanitizeDeploymentChannel(body.deploymentChannel()),
                sanitizeAllowedChannels(body.allowedChannels(), sanitizeDeploymentChannel(body.deploymentChannel())));

        Map<String, Object> created = oneGame("""
                SELECT p.*, c.name character_name, c.level, c.job, c.world, c.map, c.hair, c.face, c.skincolor, c.gender, a.name account_name,
                       EXISTS(
                           SELECT 1
                           FROM agent_runtime_credentials rc
                           WHERE rc.agent_profile_id = p.id
                       ) has_runtime_credential,
                       (
                           SELECT GROUP_CONCAT(ii.itemid ORDER BY ii.position SEPARATOR ',')
                           FROM inventoryitems ii
                           WHERE ii.characterid = c.id
                             AND ii.inventorytype = -1
                             AND ii.position < 0
                       ) equipped_item_ids
                FROM agent_profiles p
                JOIN characters c ON c.id = p.character_id
                JOIN accounts a ON a.id = c.accountid
                WHERE p.character_id=?
                """, body.characterId());
        syncCachedRuntimeProfile(((Number) created.get("id")).intValue(), created);
        seedDefaultLoadout(((Number) created.get("id")).intValue());
        audit(principal, "AGENT_PROFILE_CREATE", "agent:" + created.get("id"), null, created, "Created through Agent CMS");
        return created;
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@PathVariable int id, @Valid @RequestBody UpdateAgent body, Principal principal) {
        Map<String, Object> before = agent(id);
        cmsJdbc.update("""
                UPDATE agent_profiles
                SET enabled=?, autonomous_enabled=?, display_name=?, script_name=?, llm_enabled=?, deployment_channel=?, allowed_channels=?
                WHERE id=?
                """,
                body.enabled(),
                Boolean.TRUE.equals(body.autonomousEnabled()),
                valueOr(body.displayName(), String.valueOf(before.get("character_name"))),
                body.scriptName(),
                Boolean.TRUE.equals(body.llmEnabled()),
                sanitizeDeploymentChannel(body.deploymentChannel()),
                sanitizeAllowedChannels(body.allowedChannels(), sanitizeDeploymentChannel(body.deploymentChannel())),
                id);
        Map<String, Object> after = agent(id);
        audit(principal, "AGENT_PROFILE_UPDATE", "agent:" + id, before, after, valueOr(body.reason(), "Updated through Agent CMS"));
        return after;
    }

    @PutMapping("/{id}/runtime-credential")
    Map<String, Object> saveRuntimeCredential(@PathVariable int id,
                                              @Valid @RequestBody SaveRuntimeCredential body,
                                              Principal principal) {
        Map<String, Object> profile = agent(id);
        String password = body.password() == null ? "" : body.password().trim();
        if (password.length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Runtime credential password must be at least 5 characters");
        }
        String accountName = valueOr(body.accountName(), String.valueOf(profile.get("account_name")));
        cmsJdbc.update("""
                INSERT INTO agent_runtime_credentials(agent_profile_id, account_name, password_secret, secret_format, notes)
                VALUES (?, ?, ?, 'PLAINTEXT_LOCAL_DEV', ?)
                ON DUPLICATE KEY UPDATE
                    account_name=VALUES(account_name),
                    password_secret=VALUES(password_secret),
                    secret_format=VALUES(secret_format),
                    notes=VALUES(notes),
                    updated_at=CURRENT_TIMESTAMP
                """, id, accountName, password, valueOr(body.notes(), "Stored for headless agent login through Agent CMS"));
        Map<String, Object> after = agent(id);
        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("agent_profile_id", id);
        auditAfter.put("account_name", accountName);
        auditAfter.put("has_runtime_credential", true);
        audit(principal, "AGENT_RUNTIME_CREDENTIAL_SAVE", "agent:" + id,
                Map.of("has_runtime_credential", profile.get("has_runtime_credential")),
                auditAfter,
                valueOr(body.reason(), "Saved runtime credential through Agent CMS"));
        return after;
    }

    @GetMapping("/cards")
    List<Map<String, Object>> cards(@RequestParam(defaultValue = "") String q,
                                    @RequestParam(defaultValue = "") String type) {
        String normalizedType = type == null || type.isBlank() ? "%" : type.trim().toUpperCase(Locale.ROOT);
        return agentQuery("""
                SELECT *
                FROM agent_cards
                WHERE enabled = 1
                  AND card_type IN ('TASK', 'HOBBY', 'PERSONALITY')
                  AND card_type LIKE ?
                  AND (name LIKE ? OR card_key LIKE ? OR description LIKE ? OR CAST(id AS CHAR) LIKE ?)
                ORDER BY FIELD(card_type, 'TASK', 'HOBBY', 'PERSONALITY'),
                         priority DESC, name
                LIMIT 300
                """, normalizedType, like(q), like(q), like(q), like(q));
    }

    @GetMapping("/{id}/card-loadout")
    Map<String, Object> cardLoadout(@PathVariable int id) {
        Map<String, Object> profile = agent(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile);
        result.put("loadout", agentQuery("""
                SELECT l.*, c.card_key, c.card_type, c.name, c.description, c.config_json, c.built_in
                FROM agent_card_loadouts l
                JOIN agent_cards c ON c.id = l.card_id
                WHERE l.agent_profile_id = ?
                ORDER BY l.priority DESC, l.slot_key
                """, id));
        return result;
    }

    @PutMapping("/{id}/card-loadout/{slotKey}")
    Map<String, Object> saveCardLoadout(@PathVariable int id, @PathVariable String slotKey,
                                        @Valid @RequestBody SaveCardLoadout body, Principal principal) {
        Map<String, Object> profile = agent(id);
        Map<String, Object> card = oneGame("""
                SELECT id, card_key, card_type, name
                FROM agent_cards
                WHERE id = ? AND enabled = 1
                """, body.cardId());
        validateCardSlot(slotKey, String.valueOf(card.get("card_type")));
        validateUniqueCardInSlotFamily(id, slotKey, body.cardId());
        Map<String, Object> before = cardLoadout(id);
        cmsJdbc.update("""
                INSERT INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, override_behavior, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    card_id = VALUES(card_id),
                    enabled = VALUES(enabled),
                    priority = VALUES(priority),
                    override_behavior = VALUES(override_behavior),
                    notes = VALUES(notes)
                """,
                id,
                slotKey,
                body.cardId(),
                !Boolean.FALSE.equals(body.enabled()),
                body.priority() == null ? 0 : body.priority(),
                Boolean.TRUE.equals(body.overrideBehavior()),
                body.notes());
        Map<String, Object> after = cardLoadout(id);
        audit(principal, "AGENT_CARD_EQUIP", "agent:" + id + ":" + slotKey, before, after,
                valueOr(body.reason(), "Equipped " + card.get("card_key") + " on " + profile.get("display_name")));
        return after;
    }

    @DeleteMapping("/{id}/card-loadout/{slotKey}")
    Map<String, Object> deleteCardLoadout(@PathVariable int id, @PathVariable String slotKey,
                                          @RequestParam(defaultValue = "Removed through Agent CMS") String reason,
                                          Principal principal) {
        agent(id);
        Map<String, Object> before = cardLoadout(id);
        cmsJdbc.update("DELETE FROM agent_card_loadouts WHERE agent_profile_id=? AND slot_key=?", id, slotKey);
        Map<String, Object> after = cardLoadout(id);
        audit(principal, "AGENT_CARD_UNEQUIP", "agent:" + id + ":" + slotKey, before, after, reason);
        return after;
    }

    @GetMapping("/{id}/task-stack")
    Map<String, Object> taskStack(@PathVariable int id) {
        Map<String, Object> profile = agent(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile);
        result.put("queue", agentQuery("""
                SELECT q.*, c.card_key, c.card_type, c.name, c.description
                FROM agent_task_queue q
                JOIN agent_cards c ON c.id = q.card_id
                WHERE q.agent_profile_id = ?
                ORDER BY CASE q.status WHEN 'ACTIVE' THEN 0 WHEN 'PENDING' THEN 1 WHEN 'COMPLETED' THEN 2 ELSE 3 END,
                         q.queue_order, q.id
                """, id));
        result.put("schedules", agentQuery("""
                SELECT s.*, c.card_key, c.card_type, c.name, c.description
                FROM agent_task_schedules s
                JOIN agent_cards c ON c.id = s.card_id
                WHERE s.agent_profile_id = ?
                ORDER BY s.enabled DESC, s.priority DESC, s.start_time, s.id
                """, id));
        result.put("defaults", agentQuery("""
                SELECT d.*, c.card_key, c.card_type, c.name, c.description
                FROM agent_task_defaults d
                JOIN agent_cards c ON c.id = d.card_id
                WHERE d.agent_profile_id = ?
                ORDER BY d.enabled DESC, d.priority DESC, d.id
                """, id));
        return result;
    }

    @PostMapping("/{id}/task-queue")
    Map<String, Object> addTaskQueue(@PathVariable int id, @Valid @RequestBody SaveTaskQueue body,
                                     Principal principal) {
        agent(id);
        validateTaskCard(body.cardId());
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("""
                INSERT INTO agent_task_queue(agent_profile_id, card_id, queue_order, status, run_mode,
                                             repeat_policy, parameter_json, starts_at, expires_at, locked_reason, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, body.cardId(), body.queueOrder() == null ? 0 : body.queueOrder(),
                valueOr(body.status(), "PENDING"), valueOr(body.runMode(), "FINITE"),
                valueOr(body.repeatPolicy(), "ONCE"), body.parameterJson(), body.startsAt(), body.expiresAt(),
                body.lockedReason(), body.notes());
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_QUEUE_ADD", "agent:" + id, before, after,
                valueOr(body.reason(), "Added task card to agent queue"));
        return after;
    }

    @PutMapping("/{id}/task-queue/{queueId}")
    Map<String, Object> updateTaskQueue(@PathVariable int id, @PathVariable long queueId,
                                        @Valid @RequestBody SaveTaskQueue body, Principal principal) {
        agent(id);
        validateTaskCard(body.cardId());
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("""
                UPDATE agent_task_queue
                SET card_id=?, queue_order=?, status=?, run_mode=?, repeat_policy=?, parameter_json=?,
                    starts_at=?, expires_at=?, completed_at=?, locked_reason=?, notes=?
                WHERE id=? AND agent_profile_id=?
                """,
                body.cardId(), body.queueOrder() == null ? 0 : body.queueOrder(),
                valueOr(body.status(), "PENDING"), valueOr(body.runMode(), "FINITE"),
                valueOr(body.repeatPolicy(), "ONCE"), body.parameterJson(), body.startsAt(), body.expiresAt(),
                body.completedAt(), body.lockedReason(), body.notes(), queueId, id);
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_QUEUE_UPDATE", "agent:" + id + ":queue:" + queueId, before, after,
                valueOr(body.reason(), "Updated queued task card"));
        return after;
    }

    @DeleteMapping("/{id}/task-queue/{queueId}")
    Map<String, Object> deleteTaskQueue(@PathVariable int id, @PathVariable long queueId,
                                        @RequestParam(defaultValue = "Removed queued task through Agent CMS") String reason,
                                        Principal principal) {
        agent(id);
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("DELETE FROM agent_task_queue WHERE id=? AND agent_profile_id=?", queueId, id);
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_QUEUE_DELETE", "agent:" + id + ":queue:" + queueId, before, after, reason);
        return after;
    }

    @PostMapping("/{id}/task-schedules")
    Map<String, Object> addTaskSchedule(@PathVariable int id, @Valid @RequestBody SaveTaskSchedule body,
                                        Principal principal) {
        agent(id);
        validateTaskCard(body.cardId());
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("""
                INSERT INTO agent_task_schedules(agent_profile_id, card_id, enabled, schedule_name, days_of_week,
                                                 start_time, end_time, starts_at, ends_at, timezone, priority,
                                                 run_mode, repeat_policy, parameter_json, queue_rule, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, body.cardId(), !Boolean.FALSE.equals(body.enabled()),
                valueOr(body.scheduleName(), "Scheduled task"), valueOr(body.daysOfWeek(), "ALL"),
                body.startTime(), body.endTime(), body.startsAt(), body.endsAt(),
                valueOr(body.timezone(), "Asia/Singapore"), body.priority() == null ? 0 : body.priority(),
                valueOr(body.runMode(), "REUSABLE"), valueOr(body.repeatPolicy(), "LOOP"), body.parameterJson(),
                sanitizeQueueRule(body.queueRule()), body.notes());
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_SCHEDULE_ADD", "agent:" + id, before, after,
                valueOr(body.reason(), "Added scheduled task card"));
        return after;
    }

    @PutMapping("/{id}/task-schedules/{scheduleId}")
    Map<String, Object> updateTaskSchedule(@PathVariable int id, @PathVariable long scheduleId,
                                           @Valid @RequestBody SaveTaskSchedule body, Principal principal) {
        agent(id);
        validateTaskCard(body.cardId());
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("""
                UPDATE agent_task_schedules
                SET card_id=?, enabled=?, schedule_name=?, days_of_week=?, start_time=?, end_time=?,
                    starts_at=?, ends_at=?, timezone=?, priority=?, run_mode=?, repeat_policy=?, parameter_json=?,
                    queue_rule=?, notes=?
                WHERE id=? AND agent_profile_id=?
                """,
                body.cardId(), !Boolean.FALSE.equals(body.enabled()),
                valueOr(body.scheduleName(), "Scheduled task"), valueOr(body.daysOfWeek(), "ALL"),
                body.startTime(), body.endTime(), body.startsAt(), body.endsAt(),
                valueOr(body.timezone(), "Asia/Singapore"), body.priority() == null ? 0 : body.priority(),
                valueOr(body.runMode(), "REUSABLE"), valueOr(body.repeatPolicy(), "LOOP"),
                body.parameterJson(), sanitizeQueueRule(body.queueRule()), body.notes(), scheduleId, id);
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_SCHEDULE_UPDATE", "agent:" + id + ":schedule:" + scheduleId, before, after,
                valueOr(body.reason(), "Updated scheduled task card"));
        return after;
    }

    @DeleteMapping("/{id}/task-schedules/{scheduleId}")
    Map<String, Object> deleteTaskSchedule(@PathVariable int id, @PathVariable long scheduleId,
                                           @RequestParam(defaultValue = "Removed scheduled task through Agent CMS") String reason,
                                           Principal principal) {
        agent(id);
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("DELETE FROM agent_task_schedules WHERE id=? AND agent_profile_id=?", scheduleId, id);
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_SCHEDULE_DELETE", "agent:" + id + ":schedule:" + scheduleId, before, after, reason);
        return after;
    }

    @PostMapping("/{id}/task-defaults")
    Map<String, Object> addTaskDefault(@PathVariable int id, @Valid @RequestBody SaveTaskDefault body,
                                       Principal principal) {
        agent(id);
        validateTaskCard(body.cardId());
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("""
                INSERT INTO agent_task_defaults(agent_profile_id, card_id, enabled, priority, selection_rule,
                                                run_mode, repeat_policy, parameter_json, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE enabled=VALUES(enabled), priority=VALUES(priority),
                    selection_rule=VALUES(selection_rule), run_mode=VALUES(run_mode),
                    repeat_policy=VALUES(repeat_policy), parameter_json=VALUES(parameter_json), notes=VALUES(notes)
                """,
                id, body.cardId(), !Boolean.FALSE.equals(body.enabled()),
                body.priority() == null ? 0 : body.priority(), valueOr(body.selectionRule(), "PRIORITY"),
                valueOr(body.runMode(), "REUSABLE"), valueOr(body.repeatPolicy(), "LOOP"), body.parameterJson(), body.notes());
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_DEFAULT_ADD", "agent:" + id, before, after,
                valueOr(body.reason(), "Added default task card"));
        return after;
    }

    @DeleteMapping("/{id}/task-defaults/{defaultId}")
    Map<String, Object> deleteTaskDefault(@PathVariable int id, @PathVariable long defaultId,
                                          @RequestParam(defaultValue = "Removed default task through Agent CMS") String reason,
                                          Principal principal) {
        agent(id);
        Map<String, Object> before = taskStack(id);
        cmsJdbc.update("DELETE FROM agent_task_defaults WHERE id=? AND agent_profile_id=?", defaultId, id);
        Map<String, Object> after = taskStack(id);
        audit(principal, "AGENT_TASK_DEFAULT_DELETE", "agent:" + id + ":default:" + defaultId, before, after, reason);
        return after;
    }

    @GetMapping("/characters")
    List<Map<String, Object>> characters(@RequestParam(defaultValue = "") String q) {
        return agentQuery("""
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

    @GetMapping("/character-creator/genders")
    List<CharacterCosmeticCatalog.GenderSummary> characterCreatorGenders() {
        return cosmeticCatalog.genders();
    }

    @GetMapping("/character-creator/worlds")
    List<Map<String, Object>> characterCreatorWorlds() {
        return characterCreator.worlds();
    }

    @GetMapping("/character-creator/cosmetics")
    List<CharacterCosmeticCatalog.CosmeticOption> characterCreatorCosmetics(@RequestParam String kind,
                                                                            @RequestParam(defaultValue = "0") int gender,
                                                                            @RequestParam(defaultValue = "") String q,
                                                                            @RequestParam(defaultValue = "30") int limit) {
        return cosmeticCatalog.search(kind, gender, q, limit);
    }

    @PostMapping("/character-creator")
    @ResponseStatus(HttpStatus.CREATED)
    AgentCharacterCreator.CreatedAgentCharacter createAgentCharacter(@Valid @RequestBody AgentCharacterCreator.CreateAgentCharacter body,
                                                                     Principal principal) {
        AgentCharacterCreator.CreatedAgentCharacter created = characterCreator.create(body);
        audit(principal, "AGENT_CHARACTER_CREATE", "character:" + created.characterId(), null, created,
                "Created account/character through Agent CMS character creator");
        return created;
    }

    @GetMapping("/{id}/spawn-plan")
    Map<String, Object> spawnPlan(@PathVariable int id) {
        Map<String, Object> row = agent(id);
        boolean enabled = truthy(row.get("enabled"));
        boolean accountOnline = numeric(row.get("loggedin"), 0) != 0;
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("ready", enabled && !accountOnline);
        plan.put("world", row.get("world"));
        int deploymentChannel = numeric(row.get("deployment_channel"), 1);
        plan.put("channel", deploymentChannel);
        plan.put("allowedChannels", sanitizeAllowedChannels((String) row.get("allowed_channels"), deploymentChannel));
        plan.put("mapId", row.get("map"));
        plan.put("spawnPoint", row.get("spawnpoint"));
        plan.put("message", !enabled ? "Agent profile is disabled" : accountOnline ? "Account is currently logged in" : "Ready for external Agent Client");
        return plan;
    }

    @GetMapping("/{id}/sessions")
    List<Map<String, Object>> sessions(@PathVariable int id) {
        return agentQuery("""
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
        status.put("latestSafety", optionalGame("""
                SELECT *
                FROM agent_memory_events
                WHERE agent_profile_id=?
                  AND event_type='SAFETY_CHECK'
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
        return agentQuery("""
                SELECT * FROM agent_action_logs
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 100
                """, id);
    }

    @GetMapping("/{id}/memory")
    List<Map<String, Object>> memory(@PathVariable int id) {
        agent(id);
        return agentQuery("""
                SELECT *
                FROM agent_memory_events
                WHERE agent_profile_id=?
                ORDER BY id DESC LIMIT 50
                """, id);
    }

    @GetMapping("/scripts")
    List<Map<String, Object>> scripts(@RequestParam(defaultValue = "") String q) {
        return agentQuery("""
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
        cmsJdbc.update("""
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

    @PostMapping("/scripts/preview")
    Map<String, Object> previewScript(@Valid @RequestBody PreviewScript body) {
        List<Map<String, Object>> steps = new ArrayList<>();
        int lineNumber = 0;
        for (String rawLine : valueOr(body.body(), "").split("\\R", -1)) {
            lineNumber++;
            String line = stripInlineComment(rawLine).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            ExpandedScriptLine expandedLine = expandScriptRepeat(line);
            for (int repeat = 1; repeat <= expandedLine.repeatCount(); repeat++) {
                Map<String, Object> step = previewScriptLine(lineNumber, expandedLine.commandLine());
                step.put("repeatIndex", repeat);
                step.put("repeatCount", expandedLine.repeatCount());
                steps.add(step);
            }
        }

        if (steps.isEmpty()) {
            steps.add(previewDefaultIdle());
        }

        long unknown = steps.stream().filter(step -> "UNKNOWN".equals(step.get("intent"))).count();
        long futureGated = steps.stream().filter(step -> Boolean.TRUE.equals(step.get("futureGated"))).count();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("steps", steps);
        preview.put("stepCount", steps.size());
        preview.put("unknownCount", unknown);
        preview.put("futureGatedCount", futureGated);
        preview.put("safeToSave", unknown == 0);
        preview.put("message", unknown > 0
                ? "Script contains unknown commands. They will be blocked by the runtime script fallback policy."
                : futureGated > 0
                ? "Script parses cleanly, but some intents need future runtime adapters or enabled capability policies."
                : "Script parses cleanly.");
        return preview;
    }

    @PutMapping("/scripts/{scriptId}")
    Map<String, Object> updateScript(@PathVariable int scriptId, @Valid @RequestBody SaveScript body, Principal principal) {
        Map<String, Object> before = oneGame("""
                SELECT id, name, version, enabled, script_type, body, created_by, created_at, updated_at
                FROM agent_scripts
                WHERE id=?
                """, scriptId);
        cmsJdbc.update("""
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
        return agentQuery("""
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
        cmsJdbc.update("""
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
        cmsJdbc.update("""
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
        return capabilityPolicyRows(id);
    }

    @GetMapping("/policies/global")
    List<Map<String, Object>> globalPolicies() {
        return capabilityPolicyRows(0);
    }

    @PutMapping("/policies/global/{key}")
    Map<String, Object> updateGlobalPolicy(@PathVariable String key,
                                           @Valid @RequestBody UpdatePolicy body, Principal principal) {
        AgentCapabilityPolicy capability = CAPABILITY_POLICIES.stream()
                .filter(policy -> policy.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown global agent policy"));

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("globalValue", policyValue(0, key));
        before.put("effective", parseBoolean(policyValue(0, key), capability.defaultEnabled()));
        cmsJdbc.update("""
                INSERT INTO agent_policies(agent_profile_id, scope, policy_key, policy_value)
                VALUES (0, 'GLOBAL', ?, ?)
                ON DUPLICATE KEY UPDATE policy_value=VALUES(policy_value), scope=VALUES(scope)
                """, key, Boolean.toString(Boolean.TRUE.equals(body.enabled())));
        Map<String, Object> after = globalPolicies().stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_GLOBAL_POLICY_SET", "agent_policy_global:" + key, before, after,
                valueOr(body.reason(), "Updated global agent policy through Agent CMS"));
        return after;
    }

    @DeleteMapping("/policies/global/{key}")
    Map<String, Object> resetGlobalPolicy(@PathVariable String key,
                                          @RequestParam(defaultValue = "Reset global agent policy through Agent CMS") String reason,
                                          Principal principal) {
        if (CAPABILITY_POLICIES.stream().noneMatch(policy -> policy.key().equals(key))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown global agent policy");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("globalValue", policyValue(0, key));
        cmsJdbc.update("DELETE FROM agent_policies WHERE agent_profile_id=0 AND policy_key=?", key);
        Map<String, Object> after = globalPolicies().stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_GLOBAL_POLICY_RESET", "agent_policy_global:" + key, before, after, reason);
        return after;
    }

    private List<Map<String, Object>> capabilityPolicyRows(int agentProfileId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCapabilityPolicy capability : CAPABILITY_POLICIES) {
            String globalValue = policyValue(0, capability.key());
            String agentValue = agentProfileId == 0 ? null : policyValue(agentProfileId, capability.key());
            boolean effective = parseBoolean(agentValue == null ? globalValue : agentValue, capability.defaultEnabled());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", capability.key());
            row.put("label", capability.label());
            row.put("description", capability.description());
            row.put("defaultEnabled", capability.defaultEnabled());
            row.put("globalValue", globalValue);
            row.put("agentValue", agentValue);
            row.put("effective", effective);
            row.put("overridden", agentProfileId == 0 ? globalValue != null : agentValue != null);
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
        cmsJdbc.update("""
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
        cmsJdbc.update("DELETE FROM agent_policies WHERE agent_profile_id=? AND policy_key=?", id, key);
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
        return cooldownPolicyRows(id);
    }

    @GetMapping("/cooldowns/global")
    List<Map<String, Object>> globalCooldowns() {
        return cooldownPolicyRows(0);
    }

    @PutMapping("/cooldowns/global/{key}")
    Map<String, Object> updateGlobalCooldown(@PathVariable String key,
                                             @Valid @RequestBody UpdateCooldown body, Principal principal) {
        AgentCooldownPolicy cooldown = COOLDOWN_POLICIES.stream()
                .filter(policy -> policy.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown global agent cooldown"));
        long millis = body.millis() == null ? cooldown.defaultMillis() : body.millis();
        if (millis < 0 || millis > 3_600_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cooldown must be between 0 and 3,600,000 ms");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("globalValue", policyValue(0, key));
        cmsJdbc.update("""
                INSERT INTO agent_policies(agent_profile_id, scope, policy_key, policy_value)
                VALUES (0, 'GLOBAL', ?, ?)
                ON DUPLICATE KEY UPDATE policy_value=VALUES(policy_value), scope=VALUES(scope)
                """, key, Long.toString(millis));
        Map<String, Object> after = globalCooldowns().stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_GLOBAL_COOLDOWN_SET", "agent_cooldown_global:" + key, before, after,
                valueOr(body.reason(), "Updated global agent cooldown through Agent CMS"));
        return after;
    }

    @DeleteMapping("/cooldowns/global/{key}")
    Map<String, Object> resetGlobalCooldown(@PathVariable String key,
                                            @RequestParam(defaultValue = "Reset global agent cooldown through Agent CMS") String reason,
                                            Principal principal) {
        if (COOLDOWN_POLICIES.stream().noneMatch(policy -> policy.key().equals(key))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown global agent cooldown");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("key", key);
        before.put("globalValue", policyValue(0, key));
        cmsJdbc.update("DELETE FROM agent_policies WHERE agent_profile_id=0 AND policy_key=?", key);
        Map<String, Object> after = globalCooldowns().stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_GLOBAL_COOLDOWN_RESET", "agent_cooldown_global:" + key, before, after, reason);
        return after;
    }

    private List<Map<String, Object>> cooldownPolicyRows(int agentProfileId) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCooldownPolicy cooldown : COOLDOWN_POLICIES) {
            String globalValue = policyValue(0, cooldown.key());
            String agentValue = agentProfileId == 0 ? null : policyValue(agentProfileId, cooldown.key());
            long effective = parseNonNegativeLong(agentValue == null ? globalValue : agentValue, cooldown.defaultMillis());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", cooldown.key());
            row.put("label", cooldown.label());
            row.put("description", cooldown.description());
            row.put("defaultMillis", cooldown.defaultMillis());
            row.put("globalValue", globalValue);
            row.put("agentValue", agentValue);
            row.put("effectiveMillis", effective);
            row.put("overridden", agentProfileId == 0 ? globalValue != null : agentValue != null);
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
        cmsJdbc.update("""
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
        cmsJdbc.update("DELETE FROM agent_policies WHERE agent_profile_id=? AND policy_key=?", id, key);
        Map<String, Object> after = cooldowns(id).stream()
                .filter(row -> key.equals(row.get("key")))
                .findFirst()
                .orElseThrow();
        audit(principal, "AGENT_COOLDOWN_RESET", "agent:" + id + ":" + key, before, after, reason);
        return after;
    }

    @PostMapping("/{id}/runtime/{action:prepare|enter|tick|release}")
    Map<String, Object> runtimeAction(@PathVariable int id, @PathVariable String action, Principal principal) {
        Map<String, Object> before = agent(id);
        syncCachedRuntimeProfile(id);
        Map<String, Object> result = agentClientPost("/api/client/sessions/" + id + "/" + action, Map.of());
        audit(principal, "AGENT_RUNTIME_" + action.toUpperCase(), "agent:" + id, before, result,
                "Manual agent runtime action through Agent CMS");
        return result;
    }

    @GetMapping("/{id}/runtime/snapshot")
    Map<String, Object> runtimeSnapshot(@PathVariable int id) {
        syncCachedRuntimeProfile(id);
        return agentClientGet("/api/client/sessions/" + id);
    }

    @PostMapping("/{id}/runtime/manual/key/{state:start|stop}")
    Map<String, Object> runtimeManualKey(@PathVariable int id, @PathVariable String state,
                                         @Valid @RequestBody ManualKey body, Principal principal) {
        Map<String, Object> before = agent(id);
        syncCachedRuntimeProfile(id);
        Map<String, Object> result = agentClientPost("/api/client/sessions/" + id + "/manual/key/" + state,
                Map.of("key", body.key()));
        audit(principal, "AGENT_RUNTIME_MANUAL_KEY_" + state.toUpperCase(), "agent:" + id, before, result,
                "Manual key " + state + " through Agent CMS");
        return result;
    }

    @PostMapping("/{id}/runtime/manual/action")
    Map<String, Object> runtimeManualAction(@PathVariable int id, @Valid @RequestBody ManualAction body,
                                            Principal principal) {
        Map<String, Object> before = agent(id);
        syncCachedRuntimeProfile(id);
        Map<String, Object> result = agentClientPost("/api/client/sessions/" + id + "/manual/action",
                Map.of("action", body.action(), "payload", body.payload() == null ? Map.of() : body.payload()));
        audit(principal, "AGENT_RUNTIME_MANUAL_ACTION", "agent:" + id, before, result,
                "Manual action through Agent CMS");
        return result;
    }

    @PostMapping("/{id}/runtime/reset-location")
    Map<String, Object> resetRuntimeLocation(@PathVariable int id,
                                             @Valid @RequestBody ResetLocation body,
                                             Principal principal) {
        Map<String, Object> before = agent(id);
        int mapId = body.mapId() == null ? 10000 : body.mapId();
        int spawnPoint = body.spawnPoint() == null ? 0 : body.spawnPoint();
        int characterId = numeric(before.get("character_id"), 0);
        if (characterId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent profile has no linked character");
        }

        gameJdbc.update("""
                UPDATE characters
                SET map=?, spawnpoint=?
                WHERE id=?
                """, mapId, spawnPoint, characterId);
        cmsJdbc.update("""
                UPDATE agent_profiles
                SET map=?, spawnpoint=?, loggedin=0
                WHERE id=?
                """, mapId, spawnPoint, id);
        Map<String, Object> after = agent(id);
        audit(principal, "AGENT_RUNTIME_RESET_LOCATION", "agent:" + id, before, after,
                valueOr(body.reason(), "Reset agent runtime location through Agent CMS"));
        return after;
    }

    @PostMapping("/{id}/runtime/smoke-test")
    Map<String, Object> runtimeSmokeTest(@PathVariable int id, Principal principal) {
        Map<String, Object> before = agent(id);
        syncCachedRuntimeProfile(id);
        List<String> actions = List.of("prepare", "enter", "tick");
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean completed = true;
        String stoppedAt = null;
        for (String action : actions) {
            Map<String, Object> result = agentClientPost("/api/client/sessions/" + id + "/" + action, Map.of());
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("action", action);
            step.put("result", result);
            step.put("ok", runtimeStepSucceeded(action, result));
            steps.add(step);
            if (!runtimeStepSucceeded(action, result)) {
                completed = false;
                stoppedAt = action;
                break;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", completed ? "COMPLETED" : "STOPPED");
        response.put("completed", completed);
        response.put("stoppedAt", stoppedAt);
        response.put("profileId", id);
        response.put("steps", steps);
        audit(principal, "AGENT_RUNTIME_SMOKE_TEST", "agent:" + id, before, response,
                "Prepare, enter and tick smoke test through Agent CMS");
        return response;
    }

    @GetMapping("/runtime/sessions")
    List<Map<String, Object>> runtimeSessions(@RequestParam(defaultValue = "") String q) {
        return agentQuery("""
                SELECT s.*, p.display_name, c.name character_name, a.name account_name,
                       TIMESTAMPDIFF(SECOND, COALESCE(s.last_tick_at, s.started_at), NOW()) AS seconds_since_tick,
                       safety.summary AS latest_safety_summary,
                       safety.created_at AS latest_safety_at,
                       CASE
                           WHEN s.ended_at IS NULL
                            AND COALESCE(s.last_tick_at, s.started_at) < DATE_SUB(NOW(), INTERVAL 2 MINUTE)
                           THEN 1 ELSE 0
                       END AS stale
                FROM agent_runtime_sessions s
                JOIN agent_profiles p ON p.id = s.agent_profile_id
                JOIN characters c ON c.id = s.character_id
                JOIN accounts a ON a.id = c.accountid
                LEFT JOIN agent_memory_events safety ON safety.id = (
                    SELECT m.id
                    FROM agent_memory_events m
                    WHERE m.agent_profile_id = p.id
                      AND m.event_type = 'SAFETY_CHECK'
                    ORDER BY m.id DESC
                    LIMIT 1
                )
                WHERE c.name LIKE ? OR a.name LIKE ? OR p.display_name LIKE ?
                   OR s.state LIKE ? OR s.current_task LIKE ?
                ORDER BY s.id DESC
                LIMIT 200
                """, like(q), like(q), like(q), like(q), like(q));
    }

    @PostMapping("/runtime/sessions/{sessionId}/mark-stale-stopped")
    Map<String, Object> markStaleSessionStopped(@PathVariable long sessionId,
                                                @Valid @RequestBody StopSession body,
                                                Principal principal) {
        Map<String, Object> before = runtimeSession(sessionId);
        if (before.get("ended_at") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Runtime session is already closed");
        }

        int updated = cmsJdbc.update("""
                UPDATE agent_runtime_sessions
                SET state='STOPPED',
                    current_task='Stopped stale session through Agent CMS',
                    stop_reason=?,
                    ended_at=CURRENT_TIMESTAMP,
                    last_tick_at=CURRENT_TIMESTAMP
                WHERE id=?
                  AND ended_at IS NULL
                  AND COALESCE(last_tick_at, started_at) < DATE_SUB(NOW(), INTERVAL 2 MINUTE)
                """, valueOr(body.reason(), "Stopped stale session through Agent CMS"), sessionId);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only stale open sessions can be stopped from Agent CMS");
        }

        Map<String, Object> after = runtimeSession(sessionId);
        audit(principal, "AGENT_RUNTIME_STALE_STOP", "agent_runtime_session:" + sessionId, before, after,
                valueOr(body.reason(), "Stopped stale session through Agent CMS"));
        return after;
    }

    private Map<String, Object> runtimeSession(long sessionId) {
        return oneGame("""
                SELECT s.*, p.display_name, c.name character_name, a.name account_name
                FROM agent_runtime_sessions s
                JOIN agent_profiles p ON p.id = s.agent_profile_id
                JOIN characters c ON c.id = s.character_id
                JOIN accounts a ON a.id = c.accountid
                WHERE s.id=?
                """, sessionId);
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
        summary.put("safety24h", oneSummary("""
                SELECT
                    COUNT(*) AS safety_warnings,
                    COUNT(DISTINCT agent_profile_id) AS affected_agents,
                    MAX(created_at) AS latest_safety_at
                FROM agent_memory_events
                WHERE event_type = 'SAFETY_CHECK'
                  AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                """));
        summary.put("economy24h", oneSummary("""
                SELECT
                    COUNT(*) AS total_entries,
                    SUM(CASE WHEN entry_type = 'LOOT_PICKUP' THEN 1 ELSE 0 END) AS loot_pickups,
                    SUM(CASE WHEN entry_type = 'SHOP_BUY' THEN 1 ELSE 0 END) AS shop_buys,
                    COALESCE(SUM(meso_delta), 0) AS net_meso_delta,
                    COALESCE(SUM(CASE WHEN meso_delta < 0 THEN meso_delta ELSE 0 END), 0) AS meso_spent,
                    COALESCE(SUM(CASE WHEN meso_delta > 0 THEN meso_delta ELSE 0 END), 0) AS meso_gained
                FROM agent_economy_ledger
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
                """));
        summary.put("latestProblems", agentQuery("""
                SELECT l.*, p.display_name, c.name character_name
                FROM agent_action_logs l
                JOIN agent_profiles p ON p.id = l.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                WHERE l.status IN ('BLOCKED', 'FAILED')
                ORDER BY l.id DESC
                LIMIT 10
                """));
        summary.put("latestSafety", agentQuery("""
                SELECT m.*, p.display_name, c.name character_name
                FROM agent_memory_events m
                JOIN agent_profiles p ON p.id = m.agent_profile_id
                JOIN characters c ON c.id = p.character_id
                WHERE m.event_type = 'SAFETY_CHECK'
                ORDER BY m.id DESC
                LIMIT 10
                """));
        return summary;
    }

    @GetMapping("/social/relationships")
    List<Map<String, Object>> relationships(@RequestParam(defaultValue = "") String q) {
        return agentQuery("""
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
        return agentQuery("""
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
        return agentQuery("""
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
        return agentQuery("""
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
        List<Map<String, Object>> rows = agentQuery(sql, args);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent record not found");
        }
        return rows.getFirst();
    }

    private Map<String, Object> optionalGame(String sql, Object... args) {
        List<Map<String, Object>> rows = agentQuery(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Map<String, Object> oneSummary(String sql) {
        List<Map<String, Object>> rows = agentQuery(sql);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    private List<Map<String, Object>> agentQuery(String sql, Object... args) {
        return cmsJdbc.queryForList(qualifyGameTables(sql), args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> agentClientGet(String path) {
        try {
            Map<String, Object> result = agentClientRest.get()
                    .uri(path)
                    .retrieve()
                    .body(Map.class);
            return result == null ? Map.of("status", "EMPTY") : result;
        } catch (Exception exception) {
            throw agentClientException(path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> agentClientPost(String path, Map<String, Object> body) {
        try {
            Map<String, Object> result = agentClientRest.post()
                    .uri(path)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return result == null ? Map.of("status", "EMPTY") : result;
        } catch (Exception exception) {
            throw agentClientException(path, exception);
        }
    }

    private ResponseStatusException agentClientException(String path, Exception exception) {
        if (exception instanceof ResourceAccessException) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Agent Client is not reachable at " + agentClientBaseUrl
                            + ". Start agent-client first, then retry " + path + ".",
                    exception);
        }
        if (exception instanceof RestClientResponseException responseException) {
            String body = responseException.getResponseBodyAsString();
            String detail = body == null || body.isBlank()
                    ? responseException.getStatusText()
                    : abbreviate(body.replaceAll("\\s+", " "), 500);
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Agent Client returned HTTP " + responseException.getStatusCode().value()
                            + " for " + path + ": " + detail,
                    exception);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Agent Client proxy failed for " + path + ": " + exception.getClass().getSimpleName(),
                exception);
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value == null || value.isBlank() ? "http://127.0.0.1:8094" : value.trim();
        while (trimmed.endsWith("/") && trimmed.length() > 1) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void syncCachedRuntimeProfile(int agentProfileId) {
        Map<String, Object> row = oneGame("""
                SELECT p.id, c.name character_name, c.level, c.job, c.world, c.map, c.spawnpoint,
                       a.name account_name, a.loggedin
                FROM agent_profiles p
                JOIN characters c ON c.id = p.character_id
                JOIN accounts a ON a.id = c.accountid
                WHERE p.id=?
                """, agentProfileId);
        syncCachedRuntimeProfile(agentProfileId, row);
    }

    private void syncCachedRuntimeProfile(int agentProfileId, Map<String, Object> row) {
        cmsJdbc.update("""
                UPDATE agent_profiles
                SET account_name=?, character_name=?, level=?, job=?, world=?, map=?, spawnpoint=?, loggedin=?
                WHERE id=?
                """,
                row.get("account_name"),
                row.get("character_name"),
                numeric(row.get("level"), 1),
                numeric(row.get("job"), 0),
                numeric(row.get("world"), 0),
                numeric(row.get("map"), 0),
                numeric(row.get("spawnpoint"), 0),
                numeric(row.get("loggedin"), 0),
                agentProfileId);
    }

    private String qualifyGameTables(String sql) {
        String qualified = sql;
        for (String table : List.of("characters", "accounts", "inventoryitems")) {
            qualified = qualified.replaceAll("(?i)\\b(FROM|JOIN|LEFT\\s+JOIN)\\s+" + table + "\\b",
                    "$1 " + gameTable(table));
        }
        return qualified;
    }

    private String gameTable(String table) {
        return quoteIdentifier(gameSchema) + "." + quoteIdentifier(table);
    }

    private String quoteIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String schemaFromJdbcUrl(String jdbcUrl, String fallback) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return fallback;
        }
        int scheme = jdbcUrl.indexOf("://");
        int start = jdbcUrl.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        if (start < 0 || start + 1 >= jdbcUrl.length()) {
            return fallback;
        }
        int end = jdbcUrl.indexOf('?', start + 1);
        String schema = (end < 0 ? jdbcUrl.substring(start + 1) : jdbcUrl.substring(start + 1, end)).trim();
        return schema.isBlank() ? fallback : schema;
    }

    private String like(String value) {
        return "%" + value + "%";
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private int numeric(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return parseBoolean(value == null ? null : String.valueOf(value), false);
    }

    private int sanitizeDeploymentChannel(Integer value) {
        if (value == null) {
            return 1;
        }
        return Math.max(1, Math.min(20, value));
    }

    private String sanitizeAllowedChannels(String raw, int deploymentChannel) {
        List<Integer> channels = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                try {
                    int channel = Integer.parseInt(part.trim());
                    if (channel >= 1 && channel <= 20 && !channels.contains(channel)) {
                        channels.add(channel);
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed checkbox values and keep the policy valid.
                }
            }
        }
        if (!channels.contains(deploymentChannel)) {
            channels.add(deploymentChannel);
        }
        channels.sort(Integer::compareTo);
        return channels.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("1");
    }

    private void validateCardSlot(String slotKey, String cardType) {
        String normalizedSlot = slotKey == null ? "" : slotKey.trim().toLowerCase(Locale.ROOT);
        String normalizedType = cardType == null ? "" : cardType.trim().toUpperCase(Locale.ROOT);
        boolean valid = "active_task".equals(normalizedSlot) && "TASK".equals(normalizedType)
                || normalizedSlot.matches("hobby_[1-5]") && "HOBBY".equals(normalizedType)
                || normalizedSlot.matches("personality_[1-3]") && "PERSONALITY".equals(normalizedType);
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Card type " + normalizedType + " cannot be equipped into slot " + slotKey);
        }
    }

    private void validateUniqueCardInSlotFamily(int agentProfileId, String slotKey, long cardId) {
        String family = numberedSlotFamily(slotKey);
        if (family == null) {
            return;
        }
        Integer duplicateCount = cmsJdbc.queryForObject("""
                SELECT COUNT(*)
                FROM agent_card_loadouts
                WHERE agent_profile_id = ?
                  AND card_id = ?
                  AND slot_key <> ?
                  AND slot_key REGEXP ?
                """, Integer.class, agentProfileId, cardId, slotKey, "^" + family + "_[0-9]+$");
        if (duplicateCount != null && duplicateCount > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This card is already equipped in another " + family + " slot");
        }
    }

    private String numberedSlotFamily(String slotKey) {
        if (slotKey == null) {
            return null;
        }
        String normalized = slotKey.trim().toLowerCase(Locale.ROOT);
        int suffix = normalized.lastIndexOf('_');
        if (suffix < 0 || suffix + 1 >= normalized.length()) {
            return null;
        }
        String tail = normalized.substring(suffix + 1);
        if (!tail.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return normalized.substring(0, suffix);
    }

    private String sanitizeQueueRule(String value) {
        String normalized = value == null ? "BACK" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FRONT", "BACK", "REPLACE_ACTIVE" -> normalized;
            default -> "BACK";
        };
    }

    private void validateTaskCard(long cardId) {
        Map<String, Object> card = oneGame("""
                SELECT id, card_type
                FROM agent_cards
                WHERE id = ? AND enabled = 1
                """, cardId);
        if (!"TASK".equals(String.valueOf(card.get("card_type")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only TASK cards can be assigned as active tasks");
        }
    }

    private void seedDefaultLoadout(int agentProfileId) {
        cmsJdbc.update("""
                INSERT IGNORE INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, notes)
                SELECT ?, 'active_task', c.id, 1, 100, 'Default active task equipped when agent profile was created'
                FROM agent_cards c
                WHERE c.card_key = 'task.mapleisland_complete_all_quests'
                """, agentProfileId);
        cmsJdbc.update("""
                INSERT IGNORE INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, notes)
                SELECT ?, 'hobby_1', c.id, 1, 60, 'Default questing hobby equipped when agent profile was created'
                FROM agent_cards c
                WHERE c.card_key = 'hobby.questing'
                """, agentProfileId);
        cmsJdbc.update("""
                INSERT IGNORE INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, notes)
                SELECT ?, 'personality_1', c.id, 1, 0, 'Default personality equipped when agent profile was created'
                FROM agent_cards c
                WHERE c.card_key = 'personality.default'
                """, agentProfileId);
        cmsJdbc.update("""
                INSERT INTO agent_task_queue(agent_profile_id, card_id, queue_order, status, run_mode, repeat_policy,
                                             parameter_json, notes)
                SELECT ?, c.id, 100, 'PENDING', 'REUSABLE', 'LOOP',
                       '{"reason":"Default post-Maple-Island fallback queued for new agents"}',
                       'Default Southperry hangout queued when agent profile was created'
                FROM agent_cards c
                WHERE c.card_key = 'task.hang_southperry'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM agent_task_queue q
                    WHERE q.agent_profile_id = ?
                      AND q.card_id = c.id
                      AND q.status IN ('PENDING', 'ACTIVE')
                  )
                """, agentProfileId, agentProfileId);
    }

    private String policyValue(int agentProfileId, String key) {
        List<String> rows = cmsJdbc.queryForList("""
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

    private Map<String, Object> previewDefaultIdle() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("line", 0);
        row.put("command", "IDLE");
        row.put("intent", "IDLE");
        row.put("argument", null);
        row.put("durationMillis", 30_000);
        row.put("capability", "SELF");
        row.put("futureGated", false);
        row.put("warning", "Empty script falls back to IDLE 30.");
        return row;
    }

    private Map<String, Object> previewScriptLine(int lineNumber, String line) {
        String[] parts = line.split("\\s+", 2);
        String command = parts[0].toUpperCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1].strip() : "";
        String intent = switch (command) {
            case "IDLE" -> "IDLE";
            case "WAIT" -> "WAIT";
            case "SAY" -> "SAY";
            case "ROAM" -> "ROAM";
            case "FOLLOW", "FOLLOW_CHARACTER", "COMPANION" -> "FOLLOW_CHARACTER";
            case "MOVE" -> "MOVE";
            case "MAP", "MOVEMAP", "MOVE_TO_MAP" -> "MOVE_TO_MAP";
            case "PORTAL", "USEPORTAL", "USE_PORTAL" -> "USE_PORTAL";
            case "ATTACK", "KILL" -> "ATTACK";
            case "GRIND", "TRAIN" -> "GRIND";
            case "LOOT", "PICKUP" -> "LOOT";
            case "NPC", "TALK" -> "NPC";
            case "SHOP", "MERCHANT" -> "SHOP";
            case "TRADE" -> "TRADE";
            case "PARTY" -> "PARTY";
            case "USEITEM", "USE_ITEM", "CONSUME" -> "USE_ITEM";
            case "EQUIP" -> "EQUIP";
            case "SKILL", "CAST", "USESKILL", "USE_SKILL" -> "SKILL";
            default -> "UNKNOWN";
        };

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("line", lineNumber);
        row.put("command", command);
        row.put("intent", intent);
        row.put("argument", argument.isBlank() ? null : argument);
        row.put("durationMillis", switch (intent) {
            case "IDLE", "WAIT" -> secondsToMillis(argument, 30_000);
            default -> 0;
        });
        row.put("capability", capabilityForIntent(intent));
        row.put("futureGated", futureGatedIntent(intent));
        row.put("warning", warningForIntent(intent));
        return row;
    }

    private String stripInlineComment(String line) {
        int marker = line.indexOf(" #");
        return marker < 0 ? line : line.substring(0, marker);
    }

    private ExpandedScriptLine expandScriptRepeat(String line) {
        String[] parts = line.split("\\s+", 3);
        if (parts.length >= 3 && "REPEAT".equalsIgnoreCase(parts[0])) {
            Integer repeat = scriptRepeatCount(parts[1]);
            if (repeat != null) {
                return new ExpandedScriptLine(parts[2].strip(), repeat);
            }
        }

        if (parts.length >= 2 && parts[0].toLowerCase(Locale.ROOT).endsWith("x")) {
            Integer repeat = scriptRepeatCount(parts[0].substring(0, parts[0].length() - 1));
            if (repeat != null) {
                return new ExpandedScriptLine((parts.length == 2 ? parts[1] : parts[1] + " " + parts[2]).strip(), repeat);
            }
        }

        return new ExpandedScriptLine(line, 1);
    }

    private Integer scriptRepeatCount(String value) {
        try {
            int repeat = Integer.parseInt(value.trim());
            return repeat < 1 ? null : Math.min(repeat, MAX_SCRIPT_REPEAT);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private long secondsToMillis(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1L, Long.parseLong(value.trim())) * 1000L;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String capabilityForIntent(String intent) {
        return switch (intent) {
            case "IDLE", "WAIT" -> "SELF";
            case "SAY" -> "CHAT";
            case "ROAM", "MOVE", "MOVE_TO_MAP", "FOLLOW_CHARACTER", "USE_PORTAL" -> "NAVIGATION";
            case "ATTACK", "GRIND" -> "COMBAT";
            case "LOOT" -> "LOOT";
            case "NPC" -> "NPC";
            case "SHOP" -> "SHOP";
            case "TRADE" -> "TRADE";
            case "PARTY" -> "PARTY";
            case "USE_ITEM", "EQUIP" -> "INVENTORY";
            case "SKILL" -> "SKILL";
            default -> "SCRIPT";
        };
    }

    private boolean futureGatedIntent(String intent) {
        return List.of("UNKNOWN").contains(intent);
    }

    private boolean runtimeStepSucceeded(String action, Map<String, Object> result) {
        if (result == null || result.containsKey("error") || "OFFLINE".equals(String.valueOf(result.get("status")))) {
            return false;
        }
        if ("prepare".equals(action) || "enter".equals(action)) {
            return Boolean.TRUE.equals(result.get("accepted"));
        }
        if ("tick".equals(action)) {
            return result.containsKey("dispatchStatus");
        }
        return true;
    }

    private String warningForIntent(String intent) {
        if ("UNKNOWN".equals(intent)) {
            return "Unknown command. Runtime will parse this as UNKNOWN and block unless script fallback is enabled.";
        }
        if (futureGatedIntent(intent)) {
            return "Parsed, but this intent still needs a dedicated runtime adapter before it can affect gameplay.";
        }
        if (List.of("SAY", "ROAM", "MOVE", "MOVE_TO_MAP", "FOLLOW_CHARACTER", "USE_PORTAL", "LOOT", "ATTACK", "GRIND", "NPC", "SHOP", "TRADE", "PARTY", "USE_ITEM", "EQUIP", "SKILL").contains(intent)) {
            return "Parsed. Execution still depends on the agent capability policy and cooldown settings.";
        }
        return "Parsed and supported by the current no-op/self runtime.";
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

        cmsJdbc.update("""
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
                       Integer ownerCharacterId, Boolean enabled, String displayName, String scriptName,
                       Boolean llmEnabled, Boolean autonomousEnabled, Integer deploymentChannel, String allowedChannels) {}

    record UpdateAgent(Boolean enabled, String displayName, String scriptName, Boolean llmEnabled,
                       Boolean autonomousEnabled, Integer deploymentChannel, String allowedChannels, String reason) {}

    record SaveRuntimeCredential(String accountName, String password, String notes, String reason) {}

    record SaveCardLoadout(@NotNull Long cardId, Boolean enabled, Integer priority, Boolean overrideBehavior,
                           String notes, String reason) {}

    record SaveTaskQueue(@NotNull Long cardId, Integer queueOrder, String status, String runMode,
                         String repeatPolicy, String parameterJson, String startsAt, String expiresAt, String completedAt,
                         String lockedReason, String notes, String reason) {}

    record SaveTaskSchedule(@NotNull Long cardId, Boolean enabled, String scheduleName, String daysOfWeek,
                             String startTime, String endTime, String startsAt, String endsAt, String timezone,
                             Integer priority, String runMode, String repeatPolicy, String parameterJson,
                             String queueRule, String notes, String reason) {}

    record SaveTaskDefault(@NotNull Long cardId, Boolean enabled, Integer priority, String selectionRule,
                           String runMode, String repeatPolicy, String parameterJson, String notes, String reason) {}

    record UpdatePolicy(Boolean enabled, String reason) {}

    record AgentCapabilityPolicy(String key, String label, String description, boolean defaultEnabled) {}

    record UpdateCooldown(Long millis, String reason) {}

    record AgentCooldownPolicy(String key, String label, String description, long defaultMillis) {}

    record StopSession(String reason) {}

    record SaveScript(String name, Integer version, Boolean enabled, String scriptType, String body, String reason) {}

    record PreviewScript(String body) {}

    record ExpandedScriptLine(String commandLine, int repeatCount) {}

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

    record ManualKey(@NotNull String key) {}

    record ManualAction(@NotNull String action, Map<String, Object> payload) {}

    record ResetLocation(Integer mapId, Integer spawnPoint, String reason) {}
}
