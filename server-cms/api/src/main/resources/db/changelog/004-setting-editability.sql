--liquibase formatted sql
--changeset cosmic:setting-editability
UPDATE setting_catalog
SET editable = FALSE,
    compatibility_note = 'This value is consumed before the game database connection exists, so it remains a bootstrap setting in config.yaml. It is shown here for provenance but cannot safely be overridden from the Server CMS database.'
WHERE setting_key = 'server.database.poolInitTimeout';
