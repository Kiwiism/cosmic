--liquibase formatted sql
--changeset cosmic:agent-map-catalog
CREATE TABLE agent_map_portals (
    map_id INT NOT NULL,
    portal_index INT NOT NULL,
    portal_name VARCHAR(120) NOT NULL,
    portal_type INT NOT NULL,
    target_map_id INT NOT NULL,
    target_portal_name VARCHAR(120) NOT NULL,
    x INT NOT NULL,
    y INT NOT NULL,
    source_path VARCHAR(500) NOT NULL,
    imported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (map_id, portal_index),
    KEY ix_agent_map_portals_target (target_map_id),
    KEY ix_agent_map_portals_name (map_id, portal_name)
);
