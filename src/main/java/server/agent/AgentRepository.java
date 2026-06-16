package server.agent;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AgentRepository {
    public List<AgentProfile> findEnabledProfiles() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, character_id, ownership_type, owner_account_id, owner_character_id,
                            enabled, display_name, default_mode, behavior_profile, personality_profile,
                            script_name, llm_enabled, created_at, updated_at
                     FROM agent_profiles
                     WHERE enabled = 1
                     ORDER BY id
                     """);
             ResultSet result = statement.executeQuery()) {
            List<AgentProfile> profiles = new ArrayList<>();
            while (result.next()) {
                profiles.add(readProfile(result));
            }
            return profiles;
        }
    }

    public Optional<AgentProfile> findByCharacterId(int characterId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, character_id, ownership_type, owner_account_id, owner_character_id,
                            enabled, display_name, default_mode, behavior_profile, personality_profile,
                            script_name, llm_enabled, created_at, updated_at
                     FROM agent_profiles
                     WHERE character_id = ?
                     """)) {
            statement.setInt(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readProfile(result));
            }
        }
    }

    public Optional<AgentCharacterLocation> findCharacterLocation(int characterId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, world, map, spawnpoint
                     FROM characters
                     WHERE id = ?
                     """)) {
            statement.setInt(1, characterId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AgentCharacterLocation(
                        result.getInt("id"),
                        result.getInt("world"),
                        result.getInt("map"),
                        result.getInt("spawnpoint")
                ));
            }
        }
    }

    private AgentProfile readProfile(ResultSet result) throws SQLException {
        return new AgentProfile(
                result.getInt("id"),
                result.getInt("character_id"),
                AgentOwnershipType.fromDatabase(result.getString("ownership_type")),
                nullableInt(result, "owner_account_id"),
                nullableInt(result, "owner_character_id"),
                result.getBoolean("enabled"),
                result.getString("display_name"),
                result.getString("default_mode"),
                result.getString("behavior_profile"),
                result.getString("personality_profile"),
                result.getString("script_name"),
                result.getBoolean("llm_enabled"),
                nullableInstant(result, "created_at"),
                nullableInstant(result, "updated_at")
        );
    }

    private Integer nullableInt(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
