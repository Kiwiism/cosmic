package com.cosmic.agentclient;

import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AgentSpawnPositionResolver {
    private final JdbcTemplate jdbc;

    public AgentSpawnPositionResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Map<String, Object>> resolve(int mapId, int spawnPoint) {
        Optional<Map<String, Object>> exact = query("""
                SELECT portal_index, portal_name, portal_type, x, y, source_path
                FROM agent_map_portals
                WHERE map_id=? AND portal_index=?
                LIMIT 1
                """, mapId, spawnPoint);
        if (exact.isPresent()) {
            return exact;
        }

        Optional<Map<String, Object>> namedSpawn = query("""
                SELECT portal_index, portal_name, portal_type, x, y, source_path
                FROM agent_map_portals
                WHERE map_id=? AND portal_name IN (?, ?, ?)
                ORDER BY FIELD(portal_name, ?, ?, ?), portal_index
                LIMIT 1
                """, mapId, "sp", "sp0", "sp1", "sp", "sp0", "sp1");
        if (namedSpawn.isPresent()) {
            return namedSpawn;
        }

        return query("""
                SELECT foothold_id portal_index, 'foothold-fallback' portal_name, 0 portal_type,
                       FLOOR((x1 + x2) / 2) x, LEAST(y1, y2) y, source_path
                FROM agent_map_footholds
                WHERE map_id=? AND ABS(x2 - x1) >= 16
                ORDER BY LEAST(y1, y2) DESC, ABS(FLOOR((x1 + x2) / 2)) ASC
                LIMIT 1
                """, mapId)
                .map(row -> {
                    row.put("source", "agent-cms.agent_map_footholds");
                    row.put("fallback", true);
                    return row;
                });
    }

    private Optional<Map<String, Object>> query(String sql, Object... args) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
            result.put("source", "agent-cms.agent_map_portals");
            return Optional.of(result);
        } catch (BadSqlGrammarException exception) {
            return Optional.empty();
        }
    }
}
