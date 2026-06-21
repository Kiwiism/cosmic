package com.cosmic.agentclient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class AgentMapGeometryRepository {
    private final JdbcTemplate cmsJdbc;

    AgentMapGeometryRepository(JdbcTemplate cmsJdbc) {
        this.cmsJdbc = cmsJdbc;
    }

    AgentMapGeometry load(int mapId) {
        List<AgentMapGeometry.Foothold> footholds = cmsJdbc.query("""
                        SELECT foothold_id, x1, y1, x2, y2, prev_foothold_id, next_foothold_id, forbid_fall_down
                        FROM agent_map_footholds
                        WHERE map_id = ?
                        """,
                (rs, rowNum) -> new AgentMapGeometry.Foothold(
                        rs.getInt("foothold_id"),
                        rs.getInt("x1"),
                        rs.getInt("y1"),
                        rs.getInt("x2"),
                        rs.getInt("y2"),
                        rs.getInt("prev_foothold_id"),
                        rs.getInt("next_foothold_id"),
                        rs.getInt("forbid_fall_down") != 0
                ),
                mapId);

        List<AgentMapGeometry.LadderRope> ladderRopes = cmsJdbc.query("""
                        SELECT object_index, is_ladder, is_upper_foothold, x, y1, y2, page, source_path
                        FROM agent_map_ladder_ropes
                        WHERE map_id = ?
                        """,
                (rs, rowNum) -> new AgentMapGeometry.LadderRope(
                        rs.getInt("object_index"),
                        rs.getInt("is_ladder") != 0,
                        rs.getInt("is_upper_foothold") != 0,
                        rs.getInt("x"),
                        rs.getInt("y1"),
                        rs.getInt("y2"),
                        rs.getInt("page"),
                        rs.getString("source_path")
                ),
                mapId);

        List<AgentMapGeometry.Portal> portals = cmsJdbc.query("""
                        SELECT portal_index, portal_name, portal_type, target_map_id, target_portal_name, x, y, source_path
                        FROM agent_map_portals
                        WHERE map_id = ?
                        """,
                (rs, rowNum) -> new AgentMapGeometry.Portal(
                        rs.getInt("portal_index"),
                        rs.getString("portal_name"),
                        rs.getInt("portal_type"),
                        rs.getInt("target_map_id"),
                        rs.getString("target_portal_name"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getString("source_path")
                ),
                mapId);

        return new AgentMapGeometry(mapId, List.copyOf(footholds), List.copyOf(ladderRopes), List.copyOf(portals));
    }
}
