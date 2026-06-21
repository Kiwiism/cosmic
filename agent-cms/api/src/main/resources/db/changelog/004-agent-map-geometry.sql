--liquibase formatted sql
--changeset cosmic:agent-map-geometry
CREATE TABLE agent_map_footholds (
    map_id INT NOT NULL,
    foothold_id INT NOT NULL,
    layer_index INT NOT NULL,
    group_index INT NOT NULL,
    x1 INT NOT NULL,
    y1 INT NOT NULL,
    x2 INT NOT NULL,
    y2 INT NOT NULL,
    prev_foothold_id INT NOT NULL DEFAULT 0,
    next_foothold_id INT NOT NULL DEFAULT 0,
    forbid_fall_down TINYINT(1) NOT NULL DEFAULT 0,
    source_path VARCHAR(500) NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (map_id, foothold_id),
    KEY ix_agent_map_footholds_span (map_id, x1, x2),
    KEY ix_agent_map_footholds_next (map_id, next_foothold_id),
    KEY ix_agent_map_footholds_prev (map_id, prev_foothold_id)
);

CREATE TABLE agent_map_ladder_ropes (
    map_id INT NOT NULL,
    object_index INT NOT NULL,
    is_ladder TINYINT(1) NOT NULL DEFAULT 0,
    is_upper_foothold TINYINT(1) NOT NULL DEFAULT 0,
    x INT NOT NULL,
    y1 INT NOT NULL,
    y2 INT NOT NULL,
    page INT NOT NULL DEFAULT 0,
    source_path VARCHAR(500) NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (map_id, object_index),
    KEY ix_agent_map_ladder_ropes_x (map_id, x)
);
