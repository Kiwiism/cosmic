# Cosmic Database CMS

The CMS combines the existing `cosmic` game database with WZ XML catalog data and a separate
`cosmic_database_cms` schema for Database CMS authentication, audit history, drafts, catalog indexes, and queued
server operations.

## Services

- `web`: Next.js database interface.
- `api`: Spring Boot API, Liquibase migrations, catalog importer, and game database access.
- Cosmic live bridge: private endpoints hosted by the game server under `/internal/admin`.

## Local configuration

Copy `.env.example` to `.env` and set local credentials. Never commit `.env`.

```powershell
Copy-Item database-cms\.env.example database-cms\.env
```

Set both database passwords in `database-cms/.env`, then launch both services:

```powershell
.\database-cms\start-database-cms.cmd
```

Stop them with `.\database-cms\stop-database-cms.cmd`.

The launcher uses a system Node.js 22 installation when available and otherwise uses the Codex
bundled Node runtime already installed on this workstation. Docker deployments include both runtimes.

## WZ catalog sources

The default import root is `<project>\wz`. The importer currently reads:

- `String.wz` for names and descriptions
- `Character.wz` for equipment requirements, average stats, and server roll ranges
- `Item.wz` for item metadata and effects
- `Mob.wz` for monster levels, HP, and properties
- `Skill.wz` for skill metadata and every available skill level
- `Map.wz` for map life entries, monster spawn counts, NPC placements, and map metadata
- `String.wz/Map.img.xml` for map names, descriptions, streets, and region grouping

Every library detail drawer shows its relative XML source and catalog SQL lookup. A completed catalog
refresh also displays the absolute import root used by the API.

The map catalog powers region filters, regional monster browsing, monster spawn-map links, NPC and
shop locations, and gachapon placement information. Unknown or custom maps remain visible under the
`Unclassified` region rather than being silently omitted.

The default local configuration expects both schemas on MySQL at `localhost:3306`:

- `cosmic`: existing game data
- `cosmic_database_cms`: CMS-owned data

Run the API:

```powershell
cd database-cms\api
..\..\mvnw.cmd spring-boot:run
```

Run the web app with Node 22+:

```powershell
cd database-cms\web
npm install
npm run dev
```

Open `http://localhost:3000`. The API defaults to `http://localhost:8081`.

## First login

When `cms_users` is empty, `POST /api/setup` creates the first Owner account. Setup closes
automatically after the first account is created.

## Safety model

- Content and confirmed-offline records use validated MySQL transactions.
- Live state is changed only through the Cosmic bridge.
- Drop and shop changes remain saved while Cosmic is offline; cache reloads are queued.
- Item grants, destructive character changes, and shutdown actions are never silently replayed.
