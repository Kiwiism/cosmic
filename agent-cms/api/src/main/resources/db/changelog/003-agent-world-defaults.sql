--liquibase formatted sql
--changeset cosmic:agent-world-defaults
CREATE TABLE agent_world_defaults (
    world_id INT NOT NULL,
    equip_slots INT NOT NULL DEFAULT 24,
    use_slots INT NOT NULL DEFAULT 24,
    setup_slots INT NOT NULL DEFAULT 24,
    etc_slots INT NOT NULL DEFAULT 24,
    storage_slots INT NOT NULL DEFAULT 8,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (world_id)
);

INSERT INTO agent_world_defaults(world_id, equip_slots, use_slots, setup_slots, etc_slots, storage_slots)
VALUES (-1, 24, 24, 24, 24, 8);
