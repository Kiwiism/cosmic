--liquibase formatted sql
--changeset cosmic:server-cms-core
CREATE TABLE server_cms_users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role_name VARCHAR(32) NOT NULL DEFAULT 'OWNER',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE setting_catalog (
    setting_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    category VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    value_type VARCHAR(24) NOT NULL,
    default_value TEXT NULL,
    origin_type VARCHAR(32) NOT NULL,
    source_file VARCHAR(255) NOT NULL,
    source_symbol VARCHAR(255) NULL,
    source_excerpt TEXT NULL,
    implementation_files TEXT NULL,
    apply_mode VARCHAR(16) NOT NULL,
    compatibility VARCHAR(24) NOT NULL DEFAULT 'SERVER_ONLY',
    compatibility_note TEXT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
    editable BOOLEAN NOT NULL DEFAULT TRUE,
    min_value DECIMAL(30,6) NULL,
    max_value DECIMAL(30,6) NULL,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE setting_overrides (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    setting_key VARCHAR(160) NOT NULL,
    scope_type VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
    scope_id INT NOT NULL DEFAULT 0,
    value_text TEXT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by BIGINT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_setting_scope (setting_key, scope_type, scope_id),
    CONSTRAINT fk_override_catalog FOREIGN KEY (setting_key) REFERENCES setting_catalog(setting_key),
    CONSTRAINT fk_override_user FOREIGN KEY (updated_by) REFERENCES server_cms_users(id) ON DELETE SET NULL
);

CREATE TABLE server_cms_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    actor_user_id BIGINT NULL,
    action VARCHAR(64) NOT NULL,
    entity_key VARCHAR(180) NOT NULL,
    before_json JSON NULL,
    after_json JSON NULL,
    reason VARCHAR(500) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_server_audit_user FOREIGN KEY (actor_user_id) REFERENCES server_cms_users(id) ON DELETE SET NULL
);

CREATE TABLE configuration_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(500) NOT NULL,
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_profile_user FOREIGN KEY (created_by) REFERENCES server_cms_users(id) ON DELETE SET NULL
);

CREATE TABLE scheduled_operations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    scheduled_for TIMESTAMP NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_operation_user FOREIGN KEY (created_by) REFERENCES server_cms_users(id) ON DELETE SET NULL
);
