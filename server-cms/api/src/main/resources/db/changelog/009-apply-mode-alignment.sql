--liquibase formatted sql
--changeset cosmic:server-setting-apply-mode-alignment
UPDATE setting_catalog
SET apply_mode = 'RESTART'
WHERE setting_key IN (
    'server.security.autoban',
    'server.security.autobanLog',
    'server.logging.chat',
    'server.logging.receivedPackets',
    'server.feature.nameChange',
    'server.feature.worldTransfer',
    'server.maintenance.freezeLogins',
    'server.maintenance.profile'
);
