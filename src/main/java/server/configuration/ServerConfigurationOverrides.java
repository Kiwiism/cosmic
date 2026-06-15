package server.configuration;

import config.ServerConfig;
import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Applies allowlisted Server CMS overrides after the game database pool starts but before worlds,
 * channels, commands, and schedulers are initialized.
 *
 * Missing schemas, invalid values, and connection failures deliberately leave the original
 * config.yaml/Java values untouched. This is the Server CMS fallback boundary.
 */
public final class ServerConfigurationOverrides {
    private static final Logger log = LoggerFactory.getLogger(ServerConfigurationOverrides.class);
    private static final Map<String, Consumer<String>> APPLIERS = new LinkedHashMap<>();
    private static volatile int loginPort = 8484;
    private static volatile int channelBasePort = 7575;
    private static volatile boolean freezeLogins;

    static {
        ServerConfig config = YamlConfig.config.server;
        integer("server.worlds.count", value -> config.WORLDS = value);
        integer("server.channel.capacity", value -> config.CHANNEL_LOAD = value);
        integer("server.channel.locks", value -> config.CHANNEL_LOCKS = value);
        string("server.network.host", value -> config.HOST = value);
        string("server.network.lanHost", value -> config.LANHOST = value);
        integer("server.network.loginPort", value -> loginPort = value);
        integer("server.network.channelBasePort", value -> channelBasePort = value);
        bool("server.login.pinEnabled", value -> config.ENABLE_PIN = value);
        bool("server.login.picEnabled", value -> config.ENABLE_PIC = value);
        integer("server.login.pinBypassMinutes", value -> config.BYPASS_PIN_EXPIRATION = value);
        integer("server.login.picBypassMinutes", value -> config.BYPASS_PIC_EXPIRATION = value);
        bool("server.login.automaticRegister", value -> config.AUTOMATIC_REGISTER = value);
        bool("server.login.multiclientDetection", value -> config.DETERRED_MULTICLIENT = value);
        bool("server.login.ipValidation", value -> config.USE_IP_VALIDATION = value);
        integer("server.login.maxAttempts", value -> config.MAX_ACCOUNT_LOGIN_ATTEMPT = value);
        integer("server.login.attemptWindowSeconds", value -> config.LOGIN_ATTEMPT_DURATION = value);
        integer("server.login.maxAccountHwids", value -> config.MAX_ALLOWED_ACCOUNT_HWID = value);
        longValue("server.login.timeoutMs", value -> config.TIMEOUT_DURATION = value);
        bool("server.security.autoban", value -> config.USE_AUTOBAN = value);
        bool("server.security.autobanLog", value -> config.USE_AUTOBAN_LOG = value);
        bool("server.logging.chat", value -> config.USE_ENABLE_CHAT_LOG = value);
        bool("server.logging.exp", value -> config.USE_EXP_GAIN_LOG = value);
        bool("server.logging.receivedPackets", value -> config.USE_DEBUG_SHOW_RCVD_PACKET = value);
        bool("server.logging.packetBodies", value -> config.USE_DEBUG_SHOW_PACKET = value);
        bool("server.persistence.autosave", value -> config.USE_AUTOSAVE = value);
        bool("server.persistence.merchantSave", value -> config.USE_ENFORCE_MERCHANT_SAVE = value);
        integer("server.database.maxPoolSize", value -> config.DB_MAX_POOL_SIZE = value);
        integer("server.database.connectionTimeoutMs", value -> config.DB_CONNECTION_TIMEOUT_MS = value);
        integer("server.runtime.nettyBossThreads", value -> config.NETTY_BOSS_THREADS = value);
        integer("server.runtime.nettyWorkerThreads", value -> config.NETTY_WORKER_THREADS = value);
        integer("server.runtime.backgroundThreads", value -> config.BACKGROUND_THREADS = value);
        integer("server.runtime.backgroundQueueCapacity", value -> config.BACKGROUND_QUEUE_CAPACITY = value);
        integer("server.runtime.persistenceThreads", value -> config.PERSISTENCE_THREADS = value);
        integer("server.runtime.persistenceQueueCapacity", value -> config.PERSISTENCE_QUEUE_CAPACITY = value);
        integer("server.runtime.gameplaySchedulerThreads", value -> config.GAMEPLAY_SCHEDULER_THREADS = value);
        integer("server.runtime.maintenanceSchedulerThreads", value -> config.MAINTENANCE_SCHEDULER_THREADS = value);
        longValue("server.runtime.slowPacketWarningMs", value -> config.SLOW_PACKET_WARNING_MS = value);
        longValue("server.runtime.slowTaskWarningMs", value -> config.SLOW_TASK_WARNING_MS = value);
        longValue("server.runtime.healthLogIntervalMs", value -> config.RUNTIME_HEALTH_LOG_INTERVAL_MS = value);
        bool("server.runtime.movementDiagnostics", value -> config.MOVEMENT_DIAGNOSTICS = value);
        longValue("server.runtime.movementGapWarningMs", value -> config.MOVEMENT_GAP_WARNING_MS = value);
        longValue("server.runtime.movementHandlerWarningMs", value -> config.MOVEMENT_HANDLER_WARNING_MS = value);
        longValue("server.runtime.movementVisibilityWarningMs", value -> config.MOVEMENT_VISIBILITY_WARNING_MS = value);
        longValue("server.persistence.autosaveBatchIntervalMs", value -> config.AUTOSAVE_BATCH_INTERVAL_MS = value);
        integer("server.persistence.autosaveBatchSize", value -> config.AUTOSAVE_BATCH_SIZE = value);
        longValue("server.persistence.autosaveCharacterIntervalMs", value -> config.AUTOSAVE_CHARACTER_INTERVAL_MS = value);
        bool("server.persistence.dirtyAutosave", value -> config.USE_DIRTY_AUTOSAVE = value);
        longValue("server.scheduler.rankingMs", value -> config.RANKING_INTERVAL = value);
        longValue("server.scheduler.purgeMs", value -> config.PURGING_INTERVAL = value);
        bool("server.feature.mts", value -> config.USE_MTS = value);
        bool("server.feature.cpq", value -> config.USE_CPQ = value);
        bool("server.feature.family", value -> config.USE_FAMILY_SYSTEM = value);
        bool("server.feature.duey", value -> config.USE_DUEY = value);
        bool("server.feature.fishing", value -> config.USE_FISHING_SYSTEM = value);
        bool("server.feature.storageSort", value -> config.USE_STORAGE_ITEM_SORT = value);
        bool("server.feature.inventorySort", value -> config.USE_ITEM_SORT = value);
        bool("server.feature.recallEvent", value -> config.USE_ENABLE_RECALL_EVENT = value);
        bool("server.feature.soloExpeditions", value -> config.USE_ENABLE_SOLO_EXPEDITIONS = value);
        bool("server.feature.mapOwnership", value -> config.USE_MAP_OWNERSHIP_SYSTEM = value);
        bool("server.feature.npcScripts", value -> config.USE_NPCS_SCRIPTABLE = value);
        bool("server.feature.nameChange", value -> config.ALLOW_CASHSHOP_NAME_CHANGE = value);
        bool("server.feature.worldTransfer", value -> config.ALLOW_CASHSHOP_WORLD_TRANSFER = value);
        bool("server.announcement.shopSale", value -> config.USE_ANNOUNCE_SHOPITEMSOLD = value);
        longValue("server.transfer.nameCooldownMs", value -> config.NAME_CHANGE_COOLDOWN = value);
        longValue("server.transfer.worldCooldownMs", value -> config.WORLD_TRANSFER_COOLDOWN = value);
        integer("server.monitor.lockTimeoutMs", value -> config.LOCK_MONITOR_TIME = value);
        integer("server.monitor.itemScanMs", value -> config.ITEM_MONITOR_TIME = value);
        integer("server.monitor.itemLimit", value -> config.ITEM_LIMIT_ON_MAP = value);
        bool("server.command.blockCashGeneration", value -> config.BLOCK_GENERATE_CASH_ITEM = value);
        bool("server.command.wholeServerRanking", value -> config.USE_WHOLE_SERVER_RANKING = value);
        integer("server.gm.minimumTradeLevel", value -> config.MINIMUM_GM_LEVEL_TO_TRADE = value);
        integer("server.gm.minimumStorageLevel", value -> config.MINIMUM_GM_LEVEL_TO_USE_STORAGE = value);
        integer("server.gm.minimumDueyLevel", value -> config.MINIMUM_GM_LEVEL_TO_USE_DUEY = value);
        integer("server.gm.minimumDropLevel", value -> config.MINIMUM_GM_LEVEL_TO_DROP = value);
        bool("server.maintenance.freezeLogins", value -> freezeLogins = value);
    }

    private ServerConfigurationOverrides() {
    }

    public static void applyStartupOverrides() {
        if (!YamlConfig.config.server.USE_SERVER_CMS_OVERRIDES) {
            log.info("Server CMS startup overrides disabled by config.yaml");
            return;
        }

        int applied = 0;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT setting_key, scope_type, scope_id, value_text
                     FROM cosmic_server_cms.setting_overrides
                     WHERE active = 1
                     """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String key = result.getString("setting_key");
                if ("WORLD".equals(result.getString("scope_type"))) {
                    applyWorldOverride(key, result.getInt("scope_id"), result.getString("value_text"));
                    applied++;
                    continue;
                }
                Consumer<String> applier = APPLIERS.get(key);
                if (applier == null) {
                    continue;
                }
                try {
                    applier.accept(result.getString("value_text"));
                    applied++;
                } catch (RuntimeException invalid) {
                    log.warn("Ignoring invalid Server CMS override for {}", key, invalid);
                }
            }
            log.info("Applied {} Server CMS startup overrides", applied);
        } catch (Exception unavailable) {
            log.info("Server CMS overrides unavailable; using config.yaml and Java defaults ({})",
                    unavailable.getClass().getSimpleName());
        }
    }

    public static int loginPort() {
        return loginPort;
    }

    public static int channelBasePort() {
        return channelBasePort;
    }

    public static boolean freezeLogins() {
        return freezeLogins;
    }

    private static void applyWorldOverride(String key, int worldId, String value) {
        if (worldId < 0 || worldId >= YamlConfig.config.worlds.size()) {
            throw new IllegalArgumentException("Unknown world " + worldId);
        }
        config.WorldConfig world = YamlConfig.config.worlds.get(worldId);
        switch (key) {
            case "server.world.expRate" -> world.exp_rate = Integer.parseInt(value);
            case "server.world.mesoRate" -> world.meso_rate = Integer.parseInt(value);
            case "server.world.dropRate" -> world.drop_rate = Integer.parseInt(value);
            case "server.world.bossDropRate" -> world.boss_drop_rate = Integer.parseInt(value);
            case "server.world.questRate" -> world.quest_rate = Integer.parseInt(value);
            case "server.world.fishingRate" -> world.fishing_rate = Integer.parseInt(value);
            case "server.world.travelRate" -> world.travel_rate = Integer.parseInt(value);
            case "server.world.channels" -> world.channels = Integer.parseInt(value);
            case "server.world.flag" -> world.flag = Integer.parseInt(value);
            case "server.world.serverMessage" -> world.server_message = value;
            case "server.world.eventMessage" -> world.event_message = value;
            case "server.world.recommendationMessage" -> world.why_am_i_recommended = value;
            case "server.world.defaultGmLevel" -> world.default_gm_level = Math.max(0, Math.min(Integer.parseInt(value), 6));
            case "server.world.defaultEquipSlots" -> world.default_equip_slots = Math.max(4, Math.min(Integer.parseInt(value), 96));
            case "server.world.defaultUseSlots" -> world.default_use_slots = Math.max(4, Math.min(Integer.parseInt(value), 96));
            case "server.world.defaultSetupSlots" -> world.default_setup_slots = Math.max(4, Math.min(Integer.parseInt(value), 96));
            case "server.world.defaultEtcSlots" -> world.default_etc_slots = Math.max(4, Math.min(Integer.parseInt(value), 96));
            case "server.world.defaultStorageSlots" -> world.default_storage_slots = Math.max(4, Math.min(Integer.parseInt(value), 48));
            default -> throw new IllegalArgumentException("Unsupported world setting " + key);
        }
    }

    private static void bool(String key, Consumer<Boolean> target) {
        APPLIERS.put(key, value -> {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("Expected boolean");
            }
            target.accept(Boolean.parseBoolean(value));
        });
    }

    private static void integer(String key, Consumer<Integer> target) {
        APPLIERS.put(key, value -> target.accept(Integer.parseInt(value)));
    }

    private static void longValue(String key, Consumer<Long> target) {
        APPLIERS.put(key, value -> target.accept(Long.parseLong(value)));
    }

    private static void string(String key, Consumer<String> target) {
        APPLIERS.put(key, target);
    }
}
