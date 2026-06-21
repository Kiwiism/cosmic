package com.cosmic.agentcms;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AgentCharacterCreator {
    private static final Pattern ACCOUNT_NAME = Pattern.compile("[a-zA-Z0-9]{3,13}");
    private static final Pattern CHARACTER_NAME = Pattern.compile("[a-zA-Z0-9]{3,12}");
    private static final String DEFAULT_AGENT_PASSWORD = "agent";
    private static final int[] DEFAULT_KEY = {18, 65, 2, 23, 3, 4, 5, 6, 16, 17, 19, 25, 26, 27, 31, 34, 35, 37, 38, 40, 43, 44, 45, 46, 50, 56, 59, 60, 61, 62, 63, 64, 57, 48, 29, 7, 24, 33, 41, 39};
    private static final int[] DEFAULT_TYPE = {4, 6, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 4, 4, 5, 6, 6, 6, 6, 6, 6, 5, 4, 5, 4, 4, 4, 4, 4};
    private static final int[] DEFAULT_ACTION = {0, 106, 10, 1, 12, 13, 18, 24, 8, 5, 4, 19, 14, 15, 2, 17, 11, 3, 20, 16, 9, 50, 51, 6, 7, 53, 100, 101, 102, 103, 104, 105, 54, 22, 52, 21, 25, 26, 23, 27};

    private final DataSource gameDataSource;
    private final JdbcTemplate gameJdbc;
    private final JdbcTemplate cmsJdbc;
    private final PasswordEncoder passwordEncoder;
    private final CharacterCosmeticCatalog cosmetics;
    private final boolean storeCreatedAccountPasswords;

    public AgentCharacterCreator(@Qualifier("gameDataSource") DataSource gameDataSource,
                                 @Qualifier("gameJdbc") JdbcTemplate gameJdbc,
                                 JdbcTemplate cmsJdbc,
                                 PasswordEncoder passwordEncoder,
                                 CharacterCosmeticCatalog cosmetics,
                                 @Value("${cosmic.agent-runtime.store-created-account-passwords:true}") boolean storeCreatedAccountPasswords) {
        this.gameDataSource = gameDataSource;
        this.gameJdbc = gameJdbc;
        this.cmsJdbc = cmsJdbc;
        this.passwordEncoder = passwordEncoder;
        this.cosmetics = cosmetics;
        this.storeCreatedAccountPasswords = storeCreatedAccountPasswords;
    }

    public CreatedAgentCharacter create(CreateAgentCharacter body) {
        String accountPassword = effectivePassword(body);
        validateRequest(body, accountPassword);
        int gender = body.gender() == null ? 0 : body.gender();
        CharacterCosmeticCatalog.CharacterCosmetics defaults = cosmetics.defaultsFor(gender);
        int face = body.face() == null ? defaults.face() : body.face();
        int hair = body.hair() == null ? defaults.hair() : body.hair();
        int skin = body.skin() == null ? defaults.skin() : body.skin();
        int top = body.top() == null ? defaults.top() : body.top();
        int bottom = body.bottom() == null ? defaults.bottom() : body.bottom();
        int shoes = body.shoes() == null ? defaults.shoes() : body.shoes();
        int weapon = body.weapon() == null ? defaults.weapon() : body.weapon();
        CharacterCosmeticCatalog.CharacterCosmetics selectedCosmetics =
                new CharacterCosmeticCatalog.CharacterCosmetics(face, hair, skin, top, bottom, shoes, weapon);
        cosmetics.validateSelection(gender, face, hair, skin, top, bottom, shoes, weapon);

        String accountName = body.accountName().trim();
        String characterName = body.characterName().trim();
        if (!gameJdbc.queryForList("SELECT id FROM characters WHERE name=? LIMIT 1", Integer.class, characterName).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Character name is already taken");
        }

        try (Connection connection = gameDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                AccountResolution account = resolveOrCreateAccount(connection, accountName, accountPassword, gender, Boolean.TRUE.equals(body.useExistingAccount()));
                prepareAccountForCharacterCreation(connection, account.id(), gender, account.created());
                int characterId = insertCharacter(connection, account.id(), characterName, body.world(), gender, skin, hair, face, selectedCosmetics);
                insertDefaultKeymap(connection, characterId);
                insertStarterItems(connection, characterId, selectedCosmetics);
                Integer agentProfileId = null;
                connection.commit();
                if (Boolean.TRUE.equals(body.createAgentProfile())) {
                    agentProfileId = insertAgentProfile(characterId, characterName, account.id(), body);
                    storeRuntimeCredentialIfEligible(agentProfileId, account, accountPassword);
                }
                return new CreatedAgentCharacter(account.id(), account.name(), account.created(), characterId, characterName,
                        body.world(), gender, skin, hair, face, agentProfileId);
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof ResponseStatusException responseStatusException) {
                    throw responseStatusException;
                }
                throw new IllegalStateException("Unable to create agent character", exception);
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create agent character", exception);
        }
    }

    private String effectivePassword(CreateAgentCharacter body) {
        if (body.password() != null && !body.password().isBlank()) {
            return body.password().trim();
        }
        return Boolean.TRUE.equals(body.useExistingAccount()) ? "" : DEFAULT_AGENT_PASSWORD;
    }

    private void validateRequest(CreateAgentCharacter body, String accountPassword) {
        if (body.accountName() == null || !ACCOUNT_NAME.matcher(body.accountName().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account name must be 3-13 letters or numbers");
        }
        if (body.characterName() == null || !CHARACTER_NAME.matcher(body.characterName().trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Character name must be 3-12 letters or numbers");
        }
        if (body.world() == null || body.world() < 0 || body.world() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "World must be between 0 and 20");
        }
        if (body.gender() != null && body.gender() != 0 && body.gender() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Gender must be 0 or 1");
        }
        if (!Boolean.TRUE.equals(body.useExistingAccount())
                && accountPassword.length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New account password must be at least 5 characters");
        }
        if (Boolean.TRUE.equals(body.useExistingAccount())
                && !accountPassword.isBlank() && accountPassword.length() < 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Runtime credential password must be at least 5 characters");
        }
    }

    private AccountResolution resolveOrCreateAccount(Connection connection, String accountName, String password,
                                                     int gender, boolean useExistingAccount) throws Exception {
        try (PreparedStatement select = connection.prepareStatement("SELECT id, name, loggedin FROM accounts WHERE name=? LIMIT 1")) {
            select.setString(1, accountName);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    if (!useExistingAccount) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Account already exists. Enable reuse to add this character to it.");
                    }
                    if (rows.getInt("loggedin") != 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is currently online. Log it out before adding an agent character.");
                    }
                    return new AccountResolution(rows.getInt("id"), rows.getString("name"), false);
                }
            }
        }

        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO accounts (name, password, birthday, tempban, gender)
                VALUES (?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, accountName);
            insert.setString(2, passwordEncoder.encode(password));
            insert.setDate(3, Date.valueOf(LocalDate.of(2005, 5, 11)));
            insert.setTimestamp(4, Timestamp.valueOf(LocalDateTime.of(2005, 5, 11, 0, 0)));
            insert.setInt(5, gender);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Account insert did not return an id");
                }
                return new AccountResolution(keys.getInt(1), accountName, true);
            }
        }
    }

    private void prepareAccountForCharacterCreation(Connection connection, int accountId, int gender, boolean created) throws Exception {
        int requiredSlots = Math.max(3, characterCount(connection, accountId) + 1);
        if (created) {
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE accounts
                    SET loggedin = 0, tos = 1, gender = ?, characterslots = GREATEST(characterslots, ?)
                    WHERE id = ?
                    """)) {
                update.setInt(1, gender);
                update.setInt(2, requiredSlots);
                update.setInt(3, accountId);
                update.executeUpdate();
            }
            return;
        }

        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE accounts
                SET characterslots = GREATEST(characterslots, ?)
                WHERE id = ?
                """)) {
            update.setInt(1, requiredSlots);
            update.setInt(2, accountId);
            update.executeUpdate();
        }
    }

    private int characterCount(Connection connection, int accountId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement("SELECT COUNT(*) FROM characters WHERE accountid=?")) {
            select.setInt(1, accountId);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    private int insertCharacter(Connection connection, int accountId, String name, int world, int gender, int skin,
                                int hair, int face, CharacterCosmeticCatalog.CharacterCosmetics defaults) throws Exception {
        WorldSlotDefaults slots = worldSlotDefaults(world);
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO characters (str, dex, luk, `int`, gm, skincolor, gender, job, hair, face, map, meso,
                                        spawnpoint, accountid, name, world, hp, mp, maxhp, maxmp, level, ap, sp,
                                        equipslots, useslots, setupslots, etcslots)
                VALUES (12, 5, 4, 4, 0, ?, ?, 0, ?, ?, 10000, 0, 0, ?, ?, ?, 50, 5, 50, 5, 1, 0,
                        '0,0,0,0,0,0,0,0,0,0', ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, skin);
            insert.setInt(2, gender);
            insert.setInt(3, hair);
            insert.setInt(4, face);
            insert.setInt(5, accountId);
            insert.setString(6, name);
            insert.setInt(7, world);
            insert.setInt(8, slots.equip());
            insert.setInt(9, slots.use());
            insert.setInt(10, slots.setup());
            insert.setInt(11, slots.etc());
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Character insert did not return an id");
                }
                return keys.getInt(1);
            }
        }
    }

    private WorldSlotDefaults worldSlotDefaults(int world) {
        try {
            return Optional.ofNullable(cmsJdbc.queryForMap("""
                            SELECT equip_slots, use_slots, setup_slots, etc_slots
                            FROM agent_world_defaults
                            WHERE world_id IN (?, -1)
                            ORDER BY CASE WHEN world_id = ? THEN 0 ELSE 1 END
                            LIMIT 1
                            """, world, world))
                    .map(row -> new WorldSlotDefaults(
                            intOrDefault(row.get("equip_slots"), 24),
                            intOrDefault(row.get("use_slots"), 24),
                            intOrDefault(row.get("setup_slots"), 24),
                            intOrDefault(row.get("etc_slots"), 24)))
                    .orElse(new WorldSlotDefaults(24, 24, 24, 24));
        } catch (Exception ignored) {
            return new WorldSlotDefaults(24, 24, 24, 24);
        }
    }

    private int intOrDefault(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private void insertDefaultKeymap(Connection connection, int characterId) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO keymap (characterid, `key`, `type`, `action`) VALUES (?, ?, ?, ?)
                """)) {
            for (int index = 0; index < DEFAULT_KEY.length; index++) {
                insert.setInt(1, characterId);
                insert.setInt(2, DEFAULT_KEY[index]);
                insert.setInt(3, DEFAULT_TYPE[index]);
                insert.setInt(4, DEFAULT_ACTION[index]);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void insertStarterItems(Connection connection, int characterId,
                                    CharacterCosmeticCatalog.CharacterCosmetics defaults) throws Exception {
        insertEquippedItem(connection, characterId, defaults.top(), -5);
        insertEquippedItem(connection, characterId, defaults.bottom(), -6);
        insertEquippedItem(connection, characterId, defaults.shoes(), -7);
        insertEquippedItem(connection, characterId, defaults.weapon(), -11);
        insertInventoryItem(connection, characterId, 4161001, 4, 1, 1);
    }

    private void insertInventoryItem(Connection connection, int characterId, int itemId, int inventoryType,
                                     int position, int quantity) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO inventoryitems (`type`, characterid, accountid, itemid, inventorytype, position,
                                            quantity, owner, petid, flag, expiration, giftFrom)
                VALUES (1, ?, NULL, ?, ?, ?, ?, '', -1, 0, -1, '')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, characterId);
            insert.setInt(2, itemId);
            insert.setInt(3, inventoryType);
            insert.setInt(4, position);
            insert.setInt(5, quantity);
            insert.executeUpdate();
        }
    }

    private void insertEquippedItem(Connection connection, int characterId, int itemId, int position) throws Exception {
        if (itemId <= 0) {
            return;
        }
        CharacterCosmeticCatalog.StarterEquipStats stats = cosmetics.starterEquipStats(itemId);
        int inventoryItemId;
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO inventoryitems (`type`, characterid, accountid, itemid, inventorytype, position,
                                            quantity, owner, petid, flag, expiration, giftFrom)
                VALUES (1, ?, NULL, ?, -1, ?, 1, '', -1, 0, -1, '')
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setInt(1, characterId);
            insert.setInt(2, itemId);
            insert.setInt(3, position);
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("Starter equip insert did not return an id");
                }
                inventoryItemId = keys.getInt(1);
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO inventoryequipment (inventoryitemid, upgradeslots, level, str, dex, `int`, luk, hp, mp,
                                                watk, matk, wdef, mdef, acc, avoid, hands, speed, jump, locked,
                                                vicious, itemlevel, itemexp, ringid)
                VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, 0, 0, 1, 0, -1)
                """)) {
            insert.setInt(1, inventoryItemId);
            insert.setInt(2, stats.upgradeSlots());
            insert.setInt(3, stats.str());
            insert.setInt(4, stats.dex());
            insert.setInt(5, stats.intStat());
            insert.setInt(6, stats.luk());
            insert.setInt(7, stats.hp());
            insert.setInt(8, stats.mp());
            insert.setInt(9, stats.watk());
            insert.setInt(10, stats.matk());
            insert.setInt(11, stats.wdef());
            insert.setInt(12, stats.mdef());
            insert.setInt(13, stats.acc());
            insert.setInt(14, stats.avoid());
            insert.setInt(15, stats.speed());
            insert.setInt(16, stats.jump());
            insert.executeUpdate();
        }
    }

    private Integer insertAgentProfile(int characterId, String characterName, int accountId,
                                       CreateAgentCharacter body) {
        cmsJdbc.update("""
                INSERT INTO agent_profiles(character_id, ownership_type, owner_account_id, owner_character_id,
                                           enabled, autonomous_enabled, display_name, script_name, llm_enabled)
                VALUES (?, 'SERVER', ?, NULL, ?, 0, ?, ?, ?)
                """, characterId, accountId, Boolean.TRUE.equals(body.enabled()),
                valueOr(body.displayName(), characterName), body.scriptName(), Boolean.TRUE.equals(body.llmEnabled()));
        Integer profileId = cmsJdbc.queryForObject("SELECT id FROM agent_profiles WHERE character_id=?",
                Integer.class, characterId);
        if (profileId == null) {
            throw new IllegalStateException("Agent profile insert did not return an id");
        }
        cmsJdbc.update("""
                UPDATE agent_profiles
                SET account_name=?, character_name=?, level=1, job=0, world=?, map=10000, spawnpoint=0, loggedin=0
                WHERE id=?
                """, body.accountName().trim(), characterName, body.world(), profileId);
        seedDefaultLoadout(profileId);
        return profileId;
    }

    private void storeRuntimeCredentialIfEligible(Integer agentProfileId, AccountResolution account,
                                                  String password) {
        if (agentProfileId == null || !storeCreatedAccountPasswords
                || password == null || password.isBlank()) {
            return;
        }
        cmsJdbc.update("""
                INSERT INTO agent_runtime_credentials(agent_profile_id, account_name, password_secret, secret_format, notes)
                VALUES (?, ?, ?, 'PLAINTEXT_LOCAL_DEV', 'Stored for headless agent login through Agent CMS character creator')
                ON DUPLICATE KEY UPDATE
                    account_name=VALUES(account_name),
                    password_secret=VALUES(password_secret),
                    secret_format=VALUES(secret_format),
                    notes=VALUES(notes),
                    updated_at=CURRENT_TIMESTAMP
                """, agentProfileId, account.name(), password);
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
                       '{"targetMapId":60000,"targetName":"Southperry"}',
                       'Default post-Maple-Island hangout queued when agent profile was created'
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

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public List<Map<String, Object>> worlds() {
        List<String> names = List.of("Scania", "Bera", "Broa", "Windia", "Khaini", "Bellocan", "Mardia",
                "Kradia", "Yellonde", "Demethos", "Galicia", "El Nido", "Zenith", "Arcenia", "Kastia",
                "Judis", "Plana", "Kalluna", "Stius", "Croa", "Medere");
        return names.stream().map(name -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", names.indexOf(name));
            row.put("name", name);
            return row;
        }).toList();
    }

    private record AccountResolution(int id, String name, boolean created) {}

    private record WorldSlotDefaults(int equip, int use, int setup, int etc) {}

    public record CreateAgentCharacter(String accountName, String password, Boolean useExistingAccount,
                                       String characterName, Integer world, Integer gender, Integer skin,
                                       Integer hair, Integer face, Integer top, Integer bottom, Integer shoes,
                                       Integer weapon, Boolean createAgentProfile, Boolean enabled,
                                       String displayName, String scriptName, Boolean llmEnabled) {}

    public record CreatedAgentCharacter(int accountId, String accountName, boolean accountCreated,
                                        int characterId, String characterName, int world, int gender,
                                        int skin, int hair, int face, Integer agentProfileId) {}
}
