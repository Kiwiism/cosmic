package server.agent;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public final class AgentRuntimeRepository {
    public AgentRuntimeSession startSession(AgentProfile profile, int world, int channel, int mapId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_runtime_sessions
                     (agent_profile_id, character_id, world, channel, map_id, state, current_task)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, profile.id());
            statement.setInt(2, profile.characterId());
            statement.setInt(3, world);
            statement.setInt(4, channel);
            statement.setInt(5, mapId);
            statement.setString(6, AgentRuntimeState.LOADING.name());
            statement.setString(7, "Starting agent runtime session");
            statement.executeUpdate();

            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    long id = generatedKeys.getLong(1);
                    return findSession(id).orElseThrow(() -> new SQLException("Created agent session was not found: " + id));
                }
            }
            throw new SQLException("Agent runtime session insert returned no generated key");
        }
    }

    public Optional<AgentRuntimeSession> findSession(long sessionId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, agent_profile_id, character_id, world, channel, map_id, state,
                            current_goal_id, current_task, started_at, last_tick_at, ended_at, stop_reason
                     FROM agent_runtime_sessions
                     WHERE id = ?
                     """)) {
            statement.setLong(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readSession(result));
            }
        }
    }

    public void updateSessionState(long sessionId, AgentRuntimeState state, String currentTask) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_runtime_sessions
                     SET state = ?, current_task = ?, last_tick_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND ended_at IS NULL
                     """)) {
            statement.setString(1, state.name());
            statement.setString(2, currentTask);
            statement.setLong(3, sessionId);
            statement.executeUpdate();
        }
    }

    public void heartbeat(long sessionId, String currentTask) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_runtime_sessions
                     SET current_task = ?, last_tick_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND ended_at IS NULL
                     """)) {
            statement.setString(1, currentTask);
            statement.setLong(2, sessionId);
            statement.executeUpdate();
        }
    }

    public void updateSessionLocation(long sessionId, int world, int channel, int mapId, String currentTask) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_runtime_sessions
                     SET world = ?, channel = ?, map_id = ?, current_task = ?, last_tick_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND ended_at IS NULL
                     """)) {
            statement.setInt(1, world);
            statement.setInt(2, channel);
            statement.setInt(3, mapId);
            statement.setString(4, currentTask);
            statement.setLong(5, sessionId);
            statement.executeUpdate();
        }
    }

    public void endSession(long sessionId, AgentRuntimeState state, String stopReason) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_runtime_sessions
                     SET state = ?, stop_reason = ?, ended_at = CURRENT_TIMESTAMP, last_tick_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND ended_at IS NULL
                     """)) {
            statement.setString(1, state.name());
            statement.setString(2, stopReason);
            statement.setLong(3, sessionId);
            statement.executeUpdate();
        }
    }

    public void logAction(AgentActionLogEntry entry) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_action_logs
                     (agent_profile_id, runtime_session_id, action_type, status, world, channel, map_id,
                      target_type, target_id, message, details_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setInt(1, entry.agentProfileId());
            setNullableLong(statement, 2, entry.runtimeSessionId());
            statement.setString(3, entry.actionType());
            statement.setString(4, entry.status().name());
            setNullableInt(statement, 5, entry.world());
            setNullableInt(statement, 6, entry.channel());
            setNullableInt(statement, 7, entry.mapId());
            statement.setString(8, entry.targetType());
            setNullableLong(statement, 9, entry.targetId());
            statement.setString(10, entry.message());
            statement.setString(11, entry.detailsJson());
            statement.executeUpdate();
        }
    }

    public long countSessionActions(long runtimeSessionId, String actionType) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*) AS action_count
                     FROM agent_action_logs
                     WHERE runtime_session_id = ? AND action_type = ?
                     """)) {
            statement.setLong(1, runtimeSessionId);
            statement.setString(2, actionType);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("action_count") : 0L;
            }
        }
    }

    public void remember(AgentMemoryEvent event) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_memory_events
                     (agent_profile_id, event_type, importance, related_character_id, related_agent_profile_id,
                      map_id, summary, details_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setInt(1, event.agentProfileId());
            statement.setString(2, event.eventType());
            statement.setInt(3, event.importance());
            setNullableInt(statement, 4, event.relatedCharacterId());
            setNullableInt(statement, 5, event.relatedAgentProfileId());
            setNullableInt(statement, 6, event.mapId());
            statement.setString(7, event.summary());
            statement.setString(8, event.detailsJson());
            statement.executeUpdate();
        }
    }

    public void upsertCompanionRelationship(
            int agentProfileId,
            int relatedCharacterId,
            int affinityFloor,
            String notes
    ) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_relationships(agent_profile_id, related_character_id, relationship_type,
                                                     trust_score, affinity_score, notes)
                     VALUES (?, ?, 'COMPANION', 0, ?, ?)
                     ON DUPLICATE KEY UPDATE
                         relationship_type = 'COMPANION',
                         affinity_score = GREATEST(affinity_score, VALUES(affinity_score)),
                         notes = VALUES(notes)
                     """)) {
            statement.setInt(1, agentProfileId);
            statement.setInt(2, relatedCharacterId);
            statement.setInt(3, affinityFloor);
            statement.setString(4, notes);
            statement.executeUpdate();
        }
    }

    public void recordEconomyLedger(
            int agentProfileId,
            long runtimeSessionId,
            String entryType,
            Integer itemId,
            int quantity,
            long mesoDelta,
            String sourceType,
            Long sourceId,
            Integer counterpartyCharacterId,
            Integer world,
            Integer channel,
            Integer mapId,
            String detailsJson
    ) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO agent_economy_ledger
                     (agent_profile_id, runtime_session_id, entry_type, item_id, quantity, meso_delta,
                      source_type, source_id, counterparty_character_id, world, channel, map_id, details_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setInt(1, agentProfileId);
            statement.setLong(2, runtimeSessionId);
            statement.setString(3, entryType);
            setNullableInt(statement, 4, itemId);
            statement.setInt(5, quantity);
            statement.setLong(6, mesoDelta);
            statement.setString(7, sourceType);
            setNullableLong(statement, 8, sourceId);
            setNullableInt(statement, 9, counterpartyCharacterId);
            setNullableInt(statement, 10, world);
            setNullableInt(statement, 11, channel);
            setNullableInt(statement, 12, mapId);
            statement.setString(13, detailsJson);
            statement.executeUpdate();
        }
    }

    private AgentRuntimeSession readSession(ResultSet result) throws SQLException {
        return new AgentRuntimeSession(
                result.getLong("id"),
                result.getInt("agent_profile_id"),
                result.getInt("character_id"),
                result.getInt("world"),
                result.getInt("channel"),
                result.getInt("map_id"),
                AgentRuntimeState.valueOf(result.getString("state")),
                nullableLong(result, "current_goal_id"),
                result.getString("current_task"),
                nullableInstant(result, "started_at"),
                nullableInstant(result, "last_tick_at"),
                nullableInstant(result, "ended_at"),
                result.getString("stop_reason")
        );
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}
