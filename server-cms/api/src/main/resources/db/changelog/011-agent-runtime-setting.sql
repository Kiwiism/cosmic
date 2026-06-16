--liquibase formatted sql
--changeset cosmic:agent-runtime-setting
INSERT INTO setting_catalog
(setting_key,display_name,category,description,value_type,default_value,origin_type,source_file,source_symbol,implementation_files,apply_mode,compatibility,compatibility_note,scope_type,risk_level,min_value,max_value,sort_order)
VALUES
('server.agent.runtimeEnabled','Agent runtime enabled','Runtime & Performance','Enable the dormant server-side agent runtime module. Keep disabled until Agent CMS and runtime controls are ready.','BOOLEAN','false','YAML_EXISTING','config.yaml','server.USE_AGENT_RUNTIME','src/main/java/config/ServerConfig.java; src/main/java/net/server/Server.java; src/main/java/server/agent/AgentRuntimeModule.java','RESTART','SERVER_ONLY','No client or WZ edit is required. This only controls whether the server loads enabled agent profiles into the runtime module at startup.','GLOBAL','HIGH',NULL,NULL,970);
