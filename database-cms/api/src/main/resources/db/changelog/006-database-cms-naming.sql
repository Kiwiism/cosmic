--liquibase formatted sql
--changeset cosmic-database-cms:006

UPDATE cms_roles
SET description = 'Full Database CMS ownership and user management'
WHERE name = 'OWNER';

UPDATE cms_permissions
SET code = 'database.manage',
    description = 'Manage Database CMS users'
WHERE code = 'staff.manage';
