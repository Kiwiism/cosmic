package server.agent;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

public final class AgentGoalRepository {
    public void recordPlanningTick(
            AgentGoal goal,
            AgentIntent intent,
            AgentIntentDispatchResult dispatchResult,
            AgentPerceptionSnapshot perception,
            String plannerReason
    ) throws SQLException {
        String progressJson = """
                {"lastIntent":"%s","lastArgument":%s,"dispatchStatus":"%s","dispatchMessage":%s,"policyAllowed":%s,"capability":"%s","world":%d,"channel":%d,"mapId":%d,"x":%d,"y":%d,"players":%d,"monsters":%d,"drops":%d,"npcs":%d,"reactors":%d,"plannerReason":%s}
                """.formatted(
                escapeJson(intent.type().name()),
                nullableString(intent.argument()),
                escapeJson(dispatchResult.status().name()),
                nullableString(dispatchResult.message()),
                dispatchResult.policyAllowed(),
                escapeJson(dispatchResult.capability().name()),
                perception.world(),
                perception.channel(),
                perception.mapId(),
                perception.x(),
                perception.y(),
                perception.players(),
                perception.monsters(),
                perception.drops(),
                perception.npcs(),
                perception.reactors(),
                nullableString(plannerReason)
        ).strip();

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE agent_goals
                     SET status = CASE WHEN status IN ('PENDING', 'ACTIVE') THEN 'RUNNING' ELSE status END,
                         started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                         progress_json = ?,
                         updated_at = CURRENT_TIMESTAMP
                     WHERE id = ?
                       AND agent_profile_id = ?
                       AND status IN ('PENDING', 'ACTIVE', 'RUNNING')
                     """)) {
            statement.setString(1, progressJson);
            statement.setLong(2, goal.id());
            statement.setInt(3, goal.agentProfileId());
            statement.executeUpdate();
        }
    }

    public Optional<AgentGoal> findNextActiveGoal(int agentProfileId) throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, agent_profile_id, goal_type, priority, status, target_world, target_channel,
                            target_map, target_ref, parameters_json, progress_json, created_at, updated_at,
                            started_at, completed_at
                     FROM agent_goals
                     WHERE agent_profile_id = ?
                       AND status IN ('PENDING', 'ACTIVE', 'RUNNING')
                     ORDER BY priority DESC, id ASC
                     LIMIT 1
                     """)) {
            statement.setInt(1, agentProfileId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(readGoal(result));
            }
        }
    }

    private AgentGoal readGoal(ResultSet result) throws SQLException {
        return new AgentGoal(
                result.getLong("id"),
                result.getInt("agent_profile_id"),
                result.getString("goal_type"),
                result.getInt("priority"),
                result.getString("status"),
                nullableInt(result, "target_world"),
                nullableInt(result, "target_channel"),
                nullableInt(result, "target_map"),
                result.getString("target_ref"),
                result.getString("parameters_json"),
                result.getString("progress_json"),
                nullableInstant(result, "created_at"),
                nullableInstant(result, "updated_at"),
                nullableInstant(result, "started_at"),
                nullableInstant(result, "completed_at")
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

    private String nullableString(String value) {
        return value == null ? "null" : "\"" + escapeJson(value) + "\"";
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
