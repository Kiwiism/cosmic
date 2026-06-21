# Cosmic Agent CMS

Agent CMS manages server-side agent profiles, goals, policies, scripts, memory, route state, and runtime actions.

It is intentionally separate from Server CMS. Server CMS manages server configuration; Agent CMS manages agent life and behavior.

## Ports

- Web: http://localhost:3002
- API: http://localhost:8084

## Databases

- CMS auth/audit data: `cosmic_agent_cms`
- Agent profiles, cards, runtime state, memory, routes, credentials, and audit data: `cosmic_agent_cms`
- Game account/character/inventory data: the normal `cosmic` database only when Agent CMS creates or attaches a real game character.

Agent CMS is the operator UI and configuration surface. The headless `agent-client` runtime is separate and connects to Cosmic like a normal v83 client; Agent CMS does not run autonomous gameplay inside the server.

## Start

```powershell
.\agent-cms\start-agent-cms.ps1
```

Copy `.env.example` to `.env` and fill credentials if your local MySQL password is not blank.

The API uses Liquibase and `createDatabaseIfNotExist=true`, so `cosmic_agent_cms` and its tables are created on first startup when the configured MySQL user has permission to create databases.

The web app normally uses dependencies installed in `agent-cms/web`. If they are not present, the start script can reuse `server-cms/web/node_modules` when Server CMS dependencies are already installed.
