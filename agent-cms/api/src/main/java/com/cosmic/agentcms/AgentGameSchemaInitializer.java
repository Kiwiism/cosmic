package com.cosmic.agentcms;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AgentGameSchemaInitializer {
    private static final List<String> AGENT_TABLES = List.of(
            "agent_task_defaults",
            "agent_task_schedules",
            "agent_task_queue",
            "agent_card_loadouts",
            "agent_cards",
            "agent_cms_users",
            "agent_cms_audit",
            "agent_chat_logs",
            "agent_economy_ledger",
            "agent_relationships",
            "agent_memory_events",
            "agent_action_logs",
            "agent_scripts",
            "agent_goals",
            "agent_runtime_credentials",
            "agent_runtime_sessions",
            "agent_policies",
            "agent_profiles"
    );

    private final JdbcTemplate agentJdbc;
    private final JdbcTemplate gameJdbc;
    private final String agentSchema;
    private final String gameSchema;

    public AgentGameSchemaInitializer(JdbcTemplate agentJdbc,
                                      @Qualifier("gameJdbc") JdbcTemplate gameJdbc,
                                      @Value("${spring.datasource.url}") String agentDatabaseUrl,
                                      @Value("${cosmic.game-database.url}") String gameDatabaseUrl) {
        this.agentJdbc = agentJdbc;
        this.gameJdbc = gameJdbc;
        this.agentSchema = schemaFromJdbcUrl(agentDatabaseUrl, "cosmic_agent_cms");
        this.gameSchema = schemaFromJdbcUrl(gameDatabaseUrl, "cosmic");
    }

    @PostConstruct
    public void ensureAgentCardTables() {
        validateSeparateSchemas();
        ensureFoundationTables();
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_cards (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    card_key VARCHAR(96) NOT NULL,
                    card_type VARCHAR(32) NOT NULL,
                    name VARCHAR(120) NOT NULL,
                    description VARCHAR(1000) NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    built_in TINYINT NOT NULL DEFAULT 0,
                    config_json TEXT DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_cards_key (card_key),
                    KEY idx_agent_cards_type_enabled (card_type, enabled)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_card_loadouts (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    slot_key VARCHAR(64) NOT NULL,
                    card_id BIGINT NOT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    priority INT NOT NULL DEFAULT 0,
                    override_behavior TINYINT NOT NULL DEFAULT 0,
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_card_loadouts_slot (agent_profile_id, slot_key),
                    KEY idx_agent_card_loadouts_card (card_id),
                    KEY idx_agent_card_loadouts_profile_enabled (agent_profile_id, enabled)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_queue (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    card_id BIGINT NOT NULL,
                    queue_order INT NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    run_mode VARCHAR(32) NOT NULL DEFAULT 'FINITE',
                    repeat_policy VARCHAR(32) NOT NULL DEFAULT 'ONCE',
                    parameter_json TEXT DEFAULT NULL,
                    starts_at TIMESTAMP NULL DEFAULT NULL,
                    expires_at TIMESTAMP NULL DEFAULT NULL,
                    completed_at TIMESTAMP NULL DEFAULT NULL,
                    locked_reason VARCHAR(255) DEFAULT NULL,
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_agent_task_queue_profile_status_order (agent_profile_id, status, queue_order),
                    KEY idx_agent_task_queue_card (card_id)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_schedules (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    card_id BIGINT NOT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    schedule_name VARCHAR(120) NOT NULL DEFAULT 'Scheduled task',
                    days_of_week VARCHAR(32) NOT NULL DEFAULT 'ALL',
                    start_time TIME DEFAULT NULL,
                    end_time TIME DEFAULT NULL,
                    starts_at TIMESTAMP NULL DEFAULT NULL,
                    ends_at TIMESTAMP NULL DEFAULT NULL,
                    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Singapore',
                    priority INT NOT NULL DEFAULT 0,
                    run_mode VARCHAR(32) NOT NULL DEFAULT 'REUSABLE',
                    repeat_policy VARCHAR(32) NOT NULL DEFAULT 'LOOP',
                    parameter_json TEXT DEFAULT NULL,
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_agent_task_schedules_profile_enabled_priority (agent_profile_id, enabled, priority),
                    KEY idx_agent_task_schedules_card (card_id)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_defaults (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    card_id BIGINT NOT NULL,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    priority INT NOT NULL DEFAULT 0,
                    selection_rule VARCHAR(32) NOT NULL DEFAULT 'PRIORITY',
                    run_mode VARCHAR(32) NOT NULL DEFAULT 'REUSABLE',
                    repeat_policy VARCHAR(32) NOT NULL DEFAULT 'LOOP',
                    parameter_json TEXT DEFAULT NULL,
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_task_defaults_card (agent_profile_id, card_id),
                    KEY idx_agent_task_defaults_profile_enabled_priority (agent_profile_id, enabled, priority)
                )
                """);
        ensureColumn("agent_task_queue", "parameter_json", "TEXT DEFAULT NULL AFTER repeat_policy");
        ensureColumn("agent_task_schedules", "parameter_json", "TEXT DEFAULT NULL AFTER repeat_policy");
        ensureColumn("agent_task_schedules", "queue_rule", "VARCHAR(32) NOT NULL DEFAULT 'BACK' AFTER parameter_json");
        ensureColumn("agent_task_defaults", "parameter_json", "TEXT DEFAULT NULL AFTER repeat_policy");
        ensureColumn("agent_profiles", "autonomous_enabled", "TINYINT NOT NULL DEFAULT 0 AFTER enabled");
        ensureColumn("agent_profiles", "deployment_channel", "INT NOT NULL DEFAULT 1 AFTER llm_enabled");
        ensureColumn("agent_profiles", "allowed_channels", "VARCHAR(128) NOT NULL DEFAULT '1' AFTER deployment_channel");
        ensureColumn("agent_profiles", "account_name", "VARCHAR(64) DEFAULT NULL AFTER character_id");
        ensureColumn("agent_profiles", "character_name", "VARCHAR(64) DEFAULT NULL AFTER account_name");
        ensureColumn("agent_profiles", "level", "INT NOT NULL DEFAULT 1 AFTER character_name");
        ensureColumn("agent_profiles", "job", "INT NOT NULL DEFAULT 0 AFTER level");
        ensureColumn("agent_profiles", "world", "INT NOT NULL DEFAULT 0 AFTER job");
        ensureColumn("agent_profiles", "map", "INT NOT NULL DEFAULT 0 AFTER world");
        ensureColumn("agent_profiles", "spawnpoint", "INT NOT NULL DEFAULT 0 AFTER map");
        ensureColumn("agent_profiles", "loggedin", "INT NOT NULL DEFAULT 0 AFTER spawnpoint");
        migrateLegacyAgentTablesFromGameDatabase();
        seedBuiltInCards();
        seedExistingAgentLoadouts();
        dropLegacyAgentTablesFromGameDatabase();
    }

    private void ensureFoundationTables() {
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_profiles (
                    id INT NOT NULL AUTO_INCREMENT,
                    character_id INT NOT NULL,
                    ownership_type VARCHAR(16) NOT NULL DEFAULT 'SERVER',
                    owner_account_id INT DEFAULT NULL,
                    owner_character_id INT DEFAULT NULL,
                    enabled TINYINT NOT NULL DEFAULT 0,
                    autonomous_enabled TINYINT NOT NULL DEFAULT 0,
                    display_name VARCHAR(32) DEFAULT NULL,
                    script_name VARCHAR(128) DEFAULT NULL,
                    llm_enabled TINYINT NOT NULL DEFAULT 0,
                    deployment_channel INT NOT NULL DEFAULT 1,
                    allowed_channels VARCHAR(128) NOT NULL DEFAULT '1',
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_profiles_character (character_id),
                    KEY idx_agent_profiles_owner_account (owner_account_id),
                    KEY idx_agent_profiles_owner_character (owner_character_id),
                    KEY idx_agent_profiles_enabled (enabled),
                    KEY idx_agent_profiles_autonomous (autonomous_enabled)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_runtime_credentials (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    account_name VARCHAR(64) NOT NULL,
                    password_secret TEXT NOT NULL,
                    secret_format VARCHAR(32) NOT NULL DEFAULT 'PLAINTEXT_LOCAL_DEV',
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_runtime_credentials_profile (agent_profile_id),
                    KEY idx_agent_runtime_credentials_account (account_name)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_runtime_sessions (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    character_id INT NOT NULL,
                    world INT NOT NULL,
                    channel INT NOT NULL,
                    map_id INT NOT NULL,
                    state VARCHAR(32) NOT NULL DEFAULT 'LOADING',
                    current_goal_id BIGINT DEFAULT NULL,
                    current_task VARCHAR(128) DEFAULT NULL,
                    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_tick_at TIMESTAMP NULL DEFAULT NULL,
                    ended_at TIMESTAMP NULL DEFAULT NULL,
                    stop_reason VARCHAR(255) DEFAULT NULL,
                    PRIMARY KEY (id),
                    KEY idx_agent_sessions_profile_state (agent_profile_id, state),
                    KEY idx_agent_sessions_character_ended (character_id, ended_at),
                    KEY idx_agent_sessions_location (world, channel, map_id)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_goals (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    goal_type VARCHAR(32) NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
                    target_world INT DEFAULT NULL,
                    target_channel INT DEFAULT NULL,
                    target_map INT DEFAULT NULL,
                    target_ref VARCHAR(128) DEFAULT NULL,
                    parameters_json TEXT DEFAULT NULL,
                    progress_json TEXT DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    started_at TIMESTAMP NULL DEFAULT NULL,
                    completed_at TIMESTAMP NULL DEFAULT NULL,
                    PRIMARY KEY (id),
                    KEY idx_agent_goals_profile_status_priority (agent_profile_id, status, priority),
                    KEY idx_agent_goals_type_status (goal_type, status)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_scripts (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    name VARCHAR(128) NOT NULL,
                    version INT NOT NULL DEFAULT 1,
                    enabled TINYINT NOT NULL DEFAULT 1,
                    script_type VARCHAR(32) NOT NULL DEFAULT 'TEXT',
                    body MEDIUMTEXT NOT NULL,
                    created_by VARCHAR(64) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_scripts_name (name),
                    KEY idx_agent_scripts_enabled (enabled)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_memory_events (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    event_type VARCHAR(48) NOT NULL,
                    importance INT NOT NULL DEFAULT 0,
                    related_character_id INT DEFAULT NULL,
                    related_agent_profile_id INT DEFAULT NULL,
                    map_id INT DEFAULT NULL,
                    summary VARCHAR(500) NOT NULL,
                    details_json TEXT DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_agent_memory_profile_time (agent_profile_id, created_at),
                    KEY idx_agent_memory_type_importance (event_type, importance)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_relationships (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    related_character_id INT NOT NULL,
                    relationship_type VARCHAR(32) NOT NULL DEFAULT 'NEUTRAL',
                    trust_score INT NOT NULL DEFAULT 0,
                    affinity_score INT NOT NULL DEFAULT 0,
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_relationship_pair (agent_profile_id, related_character_id),
                    KEY idx_agent_relationship_related (related_character_id)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_action_logs (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    runtime_session_id BIGINT DEFAULT NULL,
                    action_type VARCHAR(48) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    world INT DEFAULT NULL,
                    channel INT DEFAULT NULL,
                    map_id INT DEFAULT NULL,
                    target_type VARCHAR(48) DEFAULT NULL,
                    target_id BIGINT DEFAULT NULL,
                    message VARCHAR(512) DEFAULT NULL,
                    details_json TEXT DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_agent_actions_profile_time (agent_profile_id, created_at),
                    KEY idx_agent_actions_session_time (runtime_session_id, created_at),
                    KEY idx_agent_actions_type_status (action_type, status)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_chat_logs (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    runtime_session_id BIGINT DEFAULT NULL,
                    channel_type VARCHAR(32) NOT NULL DEFAULT 'MAP',
                    direction VARCHAR(16) NOT NULL DEFAULT 'OUT',
                    sender_character_id INT DEFAULT NULL,
                    recipient_character_id INT DEFAULT NULL,
                    message VARCHAR(500) NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_agent_chat_profile_time (agent_profile_id, created_at),
                    KEY idx_agent_chat_sender_time (sender_character_id, created_at)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_economy_ledger (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL,
                    runtime_session_id BIGINT DEFAULT NULL,
                    entry_type VARCHAR(32) NOT NULL,
                    item_id INT DEFAULT NULL,
                    quantity INT NOT NULL DEFAULT 0,
                    meso_delta BIGINT NOT NULL DEFAULT 0,
                    source_type VARCHAR(48) DEFAULT NULL,
                    source_id BIGINT DEFAULT NULL,
                    counterparty_character_id INT DEFAULT NULL,
                    world INT DEFAULT NULL,
                    channel INT DEFAULT NULL,
                    map_id INT DEFAULT NULL,
                    details_json TEXT DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    KEY idx_agent_economy_profile_time (agent_profile_id, created_at),
                    KEY idx_agent_economy_item_time (item_id, created_at),
                    KEY idx_agent_economy_counterparty (counterparty_character_id)
                )
                """);
        agentJdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_policies (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    agent_profile_id INT NOT NULL DEFAULT 0,
                    policy_key VARCHAR(96) NOT NULL,
                    policy_value VARCHAR(500) NOT NULL,
                    notes VARCHAR(500) DEFAULT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_policies_profile_key (agent_profile_id, policy_key)
                )
                """);
        ensureColumn("agent_scripts", "created_by", "VARCHAR(64) DEFAULT NULL AFTER body");
    }

    private void ensureColumn(String table, String column, String definition) {
        Integer exists = agentJdbc.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                """, Integer.class, table, column);
        if (exists != null && exists == 0) {
            agentJdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private void migrateLegacyAgentTablesFromGameDatabase() {
        validateSeparateSchemas();
        boolean migrationSucceeded = true;
        for (String table : AGENT_TABLES) {
            if (!legacyTableExists(table)) {
                continue;
            }
            try {
                migrateLegacyTable(table);
            } catch (Exception exception) {
                migrationSucceeded = false;
                System.err.println("Unable to migrate legacy agent table " + table + ": " + exception.getMessage());
            }
        }
        if (!migrationSucceeded) {
            throw new IllegalStateException("Agent legacy table migration failed; refusing to drop legacy game-db agent tables");
        }
    }

    private void dropLegacyAgentTablesFromGameDatabase() {
        validateSeparateSchemas();
        List<String> tables = new ArrayList<>(AGENT_TABLES);
        Collections.reverse(tables);
        for (String table : tables) {
            if (legacyTableExists(table)) {
                gameJdbc.execute("DROP TABLE IF EXISTS " + quoteIdentifier(table));
            }
        }
    }

    private void validateSeparateSchemas() {
        if (agentSchema.equalsIgnoreCase(gameSchema)) {
            throw new IllegalStateException("Agent CMS database must be separate from the Cosmic game database. "
                    + "Resolved both datasources to '" + agentSchema + "'. Set AGENT_CMS_DB_URL to cosmic_agent_cms.");
        }
    }

    private boolean legacyTableExists(String table) {
        Integer exists = gameJdbc.queryForObject("""
                SELECT COUNT(*)
                FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                """, Integer.class, table);
        return exists != null && exists > 0;
    }

    private void migrateLegacyTable(String table) {
        List<String> sourceColumns = columns(gameJdbc, null, table);
        List<String> destinationColumns = columns(agentJdbc, null, table);
        Set<String> sourceColumnSet = new HashSet<>(sourceColumns);
        List<String> commonColumns = destinationColumns.stream()
                .filter(sourceColumnSet::contains)
                .toList();
        if (commonColumns.isEmpty()) {
            return;
        }

        String columnList = commonColumns.stream()
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        agentJdbc.update("INSERT IGNORE INTO " + quoteIdentifier(table)
                + " (" + columnList + ") SELECT " + columnList
                + " FROM " + quoteIdentifier(gameSchema) + "." + quoteIdentifier(table));
    }

    private List<String> columns(JdbcTemplate jdbc, String schema, String table) {
        String schemaPredicate = schema == null ? "DATABASE()" : "?";
        Object[] args = schema == null ? new Object[]{table} : new Object[]{schema, table};
        return jdbc.queryForList("""
                SELECT COLUMN_NAME
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = %s
                  AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """.formatted(schemaPredicate), String.class, args);
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

    private void seedBuiltInCards() {
        agentJdbc.update("UPDATE agent_cards SET enabled=0 WHERE card_type NOT IN ('TASK', 'HOBBY', 'PERSONALITY') OR card_key LIKE 'behavior.%'");
        agentJdbc.update("""
                INSERT INTO agent_cards(card_key, card_type, name, description, priority, enabled, built_in, config_json) VALUES
                    ('personality.default', 'PERSONALITY', 'Default', 'Neutral server-safe agent personality.', 0, 1, 1, '{"tone":"neutral"}'),
                    ('personality.friendly', 'PERSONALITY', 'Friendly', 'Warm, helpful, and more likely to greet players.', 10, 1, 1, '{"tone":"friendly"}'),
                    ('personality.quiet', 'PERSONALITY', 'Quiet', 'Keeps chatter low and focuses on assigned tasks.', 10, 1, 1, '{"tone":"quiet"}'),
                    ('personality.playful', 'PERSONALITY', 'Playful', 'Light and social without bypassing safety rules.', 10, 1, 1, '{"tone":"playful"}'),
                    ('personality.focused', 'PERSONALITY', 'Focused', 'Task-first personality for grinding or quest routines.', 10, 1, 1, '{"tone":"focused"}'),
                    ('personality.chatty_neighbor', 'PERSONALITY', 'Chatty neighbor', 'More likely to greet nearby players and agents with short friendly lines.', 30, 1, 1, '{"tone":"chatty","chatFrequency":"medium","allowAgentChat":true,"allowPlayerChat":true}'),
                    ('personality.relaxed_sitter', 'PERSONALITY', 'Relaxed sitter', 'Calm personality for agents that sit around town and talk occasionally.', 25, 1, 1, '{"tone":"relaxed","chatFrequency":"low","likesChairs":true}'),
                    ('personality.helpful_newcomer', 'PERSONALITY', 'Helpful newcomer', 'Friendly beginner-style personality that can explain simple places and directions.', 25, 1, 1, '{"tone":"helpful","chatFrequency":"medium","topicBias":["directions","quests","training"]}'),
                    ('personality.expressive_mood', 'PERSONALITY', 'Expressive mood', 'Uses fitting facial expressions like F3 or F7 and small playful movements when idle or social.', 30, 1, 1, '{"tone":"expressive","faces":["F1","F3","F7"],"motions":["jump","duck","playful_swing"],"frequency":"medium"}'),
                    ('hobby.questing', 'HOBBY', 'Questing', 'Prefers task cards that advance quests, NPC routes, and checklist completion.', 80, 1, 1, '{"preferenceWeights":{"quest":1.0},"fallbackIntent":"QUEST_ROUTE","avoidForwardOnlyLockout":true,"lootQuestItems":true}'),
                    ('hobby.mob_grinding', 'HOBBY', 'Mob grinding', 'Prefers level, combat, and routine monster-hunting task cards.', 60, 1, 1, '{"preferenceWeights":{"combat":0.8,"loot":0.2},"fallbackIntent":"GRIND","lootNearby":true}'),
                    ('hobby.rare_drop_hunting', 'HOBBY', 'Rare drop hunting', 'Prefers repeatable tasks that hunt specific mobs or items.', 55, 1, 1, '{"preferenceWeights":{"rareDrop":0.7,"combat":0.2,"loot":0.1},"fallbackIntent":"GRIND","lootNearby":true}'),
                    ('hobby.town_social', 'HOBBY', 'Town social', 'Prefers town hangout, chatting, sitting, and light roaming task cards.', 45, 1, 1, '{"preferenceWeights":{"social":0.8,"exploration":0.2},"fallbackIntent":"WAIT","socialDrift":true,"safeMapsOnly":true}'),
                    ('hobby.free_market_browser', 'HOBBY', 'Free Market browser', 'Prefers browsing shops and market-related task cards.', 40, 1, 1, '{"preferenceWeights":{"market":0.9,"social":0.1},"fallbackIntent":"WAIT","marketBias":true}'),
                    ('hobby.chair_sitter', 'HOBBY', 'Chair sitter', 'Prefers safe idle tasks that sit on a Relaxer or other configured chair.', 35, 1, 1, '{"preferenceWeights":{"social":0.4,"idle":0.6},"fallbackIntent":"WAIT","preferChair":true,"chairItemId":3010000}'),
                    ('hobby.roaming', 'HOBBY', 'Roaming', 'Prefers exploration and local movement tasks when no stronger plan exists.', 30, 1, 1, '{"preferenceWeights":{"exploration":0.8,"social":0.2},"fallbackIntent":"ROAM"}'),
                    ('hobby.movement_validation', 'HOBBY', 'Movement validation', 'Operator-oriented hobby that favors movement validation task cards.', 70, 1, 1, '{"preferenceWeights":{"exploration":1.0},"fallbackIntent":"ROAM","hint":"movement_validation","edgeTypes":["WALK","FOOTHOLD_LINK","JUMP","DROP","CLIMB"],"avoidPortals":true}'),
                    ('task.idle', 'TASK', 'Idle until assigned', 'A no-op task card for agents that should do nothing unless another task is assigned.', 0, 1, 1, '{"intent":"IDLE","runMode":"REUSABLE","repeatPolicy":"LOOP","categoryWeights":{"idle":1.0},"endGoal":{"type":"MANUAL_REPLACE"}}'),
                    ('task.chill_town', 'TASK', 'Chill in town', 'Hang around social town spaces when no explicit goal is active.', 10, 1, 1, '{"intent":"WAIT","runMode":"REUSABLE","repeatPolicy":"LOOP","categoryWeights":{"social":0.7,"idle":0.3},"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.grind_to_level', 'TASK', 'Grind toward next level', 'Fight safe monsters until a target level is reached.', 20, 1, 1, '{"intent":"GRIND","runMode":"FINITE","repeatPolicy":"ONCE","categoryWeights":{"combat":0.8,"loot":0.2},"endGoal":{"type":"REACH_LEVEL","parameter":"targetLevel"},"loot":true}'),
                    ('task.follow_player', 'TASK', 'Follow a player', 'Companion task that stays near a configured player or party member.', 20, 1, 1, '{"intent":"FOLLOW_CHARACTER","runMode":"REUSABLE","repeatPolicy":"LOOP","categoryWeights":{"social":0.7,"exploration":0.3},"endGoal":{"type":"MANUAL_REPLACE"}}'),
                    ('task.mapleisland_complete_all_quests', 'TASK', 'Complete Maple Island quests', 'Finish Maple Island quests in route order without taking one-way exits early, then end at Southperry.', 100, 1, 1, '{"intent":"QUEST_ROUTE","instructionSet":"mapleisland.full_questline.v1","runMode":"FINITE","repeatPolicy":"ONCE","region":"Maple Island","categoryWeights":{"quest":0.85,"combat":0.1,"loot":0.05},"endGoal":{"type":"MAPLE_ISLAND_QUESTS_COMPLETE_AND_REACH_MAP","mapId":60000,"mapName":"Southperry"},"lockoutPolicy":"complete_map_quests_before_forward_only_transition","steps":["Complete local NPC quests before leaving each Maple Island map","Collect required Snail and Mushroom ETC before advancing forward-only route steps","Submit Biggs and Shanks preparation quests before taking the Southperry exit","Move to Southperry and mark the task complete"]}'),
                    ('task.hang_southperry', 'TASK', 'Hang around Southperry', 'Reusable town task that keeps the agent around Southperry and available for social actions.', 45, 1, 1, '{"intent":"WAIT","runMode":"REUSABLE","repeatPolicy":"LOOP","targetMapId":60000,"targetName":"Southperry","categoryWeights":{"social":0.75,"idle":0.25},"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.hang_henesys', 'TASK', 'Hang around Henesys', 'Reusable town task that keeps the agent around Henesys for social roaming and light chatter.', 45, 1, 1, '{"intent":"WAIT","runMode":"REUSABLE","repeatPolicy":"LOOP","targetMapId":100000000,"targetName":"Henesys","categoryWeights":{"social":0.75,"exploration":0.25},"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.hang_lith_harbor', 'TASK', 'Hang around Lith Harbor', 'Reusable town task that keeps the agent around Lith Harbor near new arrivals.', 45, 1, 1, '{"intent":"WAIT","runMode":"REUSABLE","repeatPolicy":"LOOP","targetMapId":104000000,"targetName":"Lith Harbor","categoryWeights":{"social":0.7,"exploration":0.3},"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.grind_right_around_lith_harbor', 'TASK', 'Grind Right Around Lith Harbor', 'Reusable light grinding task for the first field after Lith Harbor.', 60, 1, 1, '{"intent":"GRIND","runMode":"REUSABLE","repeatPolicy":"LOOP","targetMapId":104000100,"targetName":"Right Around Lith Harbor","categoryWeights":{"combat":0.75,"loot":0.25},"loot":true,"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.grind_henesys_hunting_ground_i', 'TASK', 'Grind Henesys Hunting Ground I', 'Reusable grinding task for the Henesys Hunting Ground I route near Henesys.', 60, 1, 1, '{"intent":"GRIND","runMode":"REUSABLE","repeatPolicy":"LOOP","targetMapId":104040000,"targetName":"Henesys Hunting Ground I","categoryWeights":{"combat":0.75,"loot":0.25},"loot":true,"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.hang_kerning_subway', 'TASK', 'Hang around Kerning Subway', 'Reusable hangout task around the Kerning City Subway entrance.', 40, 1, 1, '{"intent":"WAIT","runMode":"REUSABLE","repeatPolicy":"LOOP","targetMapId":103000100,"targetName":"Subway Ticketing Booth","categoryWeights":{"social":0.7,"exploration":0.3},"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.sit_on_relaxer', 'TASK', 'Sit on a Relaxer chair', 'Reusable calm task that prefers sitting on a Relaxer chair in a safe social map.', 35, 1, 1, '{"intent":"USE_CHAIR","runMode":"REUSABLE","repeatPolicy":"LOOP","preferredItemId":3010000,"preferredItemName":"Relaxer","categoryWeights":{"idle":0.8,"social":0.2},"endGoal":{"type":"DURATION_OR_REPLACE","durationMinutes":30}}'),
                    ('task.validate_movement', 'TASK', 'Validate movement animation', 'Reusable operator task that exercises walk, step, jump, drop and ladder or rope graph edges on the current map for live client validation.', 95, 1, 1, '{"intent":"ROAM","runMode":"REUSABLE","repeatPolicy":"LOOP","validation":"walk_step_jump_drop_climb","avoidPortals":true,"categoryWeights":{"exploration":1.0},"endGoal":{"type":"MANUAL_REPLACE"}}')
                ON DUPLICATE KEY UPDATE
                    card_type = VALUES(card_type),
                    name = VALUES(name),
                    description = VALUES(description),
                    priority = VALUES(priority),
                    enabled = VALUES(enabled),
                    built_in = VALUES(built_in),
                    config_json = VALUES(config_json)
                """);
    }

    private void seedExistingAgentLoadouts() {
        agentJdbc.update("""
                DELETE FROM agent_card_loadouts
                WHERE slot_key LIKE 'default_behavior_%'
                   OR slot_key LIKE 'task_override_behavior_%'
                   OR slot_key LIKE 'safety_%'
                   OR slot_key LIKE 'passive_%'
                   OR slot_key = 'default_active_task'
                """);
        agentJdbc.update("""
                INSERT IGNORE INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, notes)
                SELECT p.id, 'active_task', c.id, 1, 100, 'Default Maple Island task equipped by Agent CMS startup initializer'
                FROM agent_profiles p
                JOIN agent_cards c ON c.card_key = 'task.mapleisland_complete_all_quests'
                """);
        agentJdbc.update("""
                INSERT IGNORE INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, notes)
                SELECT p.id, 'hobby_1', c.id, 1, 60, 'Default questing hobby equipped by Agent CMS startup initializer'
                FROM agent_profiles p
                JOIN agent_cards c ON c.card_key = 'hobby.questing'
                """);
        agentJdbc.update("""
                INSERT IGNORE INTO agent_card_loadouts(agent_profile_id, slot_key, card_id, enabled, priority, notes)
                SELECT p.id, 'personality_1', c.id, 1, 0, 'Default personality equipped by Agent CMS startup initializer'
                FROM agent_profiles p
                JOIN agent_cards c ON c.card_key = 'personality.default'
                """);
        agentJdbc.update("""
                INSERT INTO agent_task_queue(agent_profile_id, card_id, queue_order, status, run_mode, repeat_policy,
                                             parameter_json, notes)
                SELECT p.id, c.id, 100, 'PENDING', 'REUSABLE', 'LOOP',
                       '{"targetMapId":60000,"targetName":"Southperry"}',
                       'Default post-Maple-Island hangout queued by Agent CMS startup initializer'
                FROM agent_profiles p
                JOIN agent_cards c ON c.card_key = 'task.hang_southperry'
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM agent_task_queue q
                    WHERE q.agent_profile_id = p.id
                      AND q.card_id = c.id
                      AND q.status IN ('PENDING', 'ACTIVE')
                )
                """);
    }
}
