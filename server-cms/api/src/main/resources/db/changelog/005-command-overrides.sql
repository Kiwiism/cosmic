--liquibase formatted sql
--changeset cosmic:command-overrides
CREATE TABLE command_overrides (
    command_name VARCHAR(80) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    required_level INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_command_override_user FOREIGN KEY (updated_by)
        REFERENCES server_cms_users(id) ON DELETE SET NULL
);
