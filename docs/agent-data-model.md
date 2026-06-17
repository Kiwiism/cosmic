# Cosmic Agent Data Model

Phase 2 adds persistent tables for future server-side agents. These tables are
created in the main `cosmic` database through Liquibase, because agents are
normal Cosmic characters that may be controlled by runtime code when enabled.

The schema is dormant by default. No gameplay behavior changes until an agent
runtime module is implemented and explicitly switched on.

## Core Tables

### `agent_profiles`

One row per character that can be controlled as an agent.

- links to `characters.id`
- records whether the agent is server-owned, player-owned, hybrid, or simulation
- stores behavior profile, personality profile, optional script name, and LLM flag
- `enabled` defaults to `0` so newly created profiles cannot run accidentally

### `agent_runtime_sessions`

Tracks each active or historical control session.

- records world/channel/map while the agent is controlled
- stores current task and current goal
- keeps start, tick, end, and stop reason timestamps

### `agent_goals`

Stores long-lived goals and side goals.

- supports priority and status
- supports map/world/channel targets
- keeps JSON text fields for goal parameters and progress

### `agent_scripts`

Stores server-side scripts that Agent CMS can manage later.

- scripts are disabled by default
- `script_type` starts as `YAML`, but the field is generic enough for future DSLs
- the dormant pilot tick service only reads enabled scripts by `agent_profiles.script_name`
- `script_name = inline:<script line>` is supported for development previews without creating a script row

The current pilot layer parses a script, captures map counts, chooses the next
intent, and writes an `INTENT_PLAN` row to `agent_action_logs`. It then passes
the intent through a policy-gated dispatcher that writes `INTENT_DISPATCH`.
`IDLE` and `WAIT` are accepted as no-ops. Navigation can execute conservative
open, non-scripted portal movement for `MOVE_TO_MAP`, `FOLLOW_CHARACTER`, and
`PORTAL` intents when the route is available. `SAY` broadcasts normal map chat
when policy allows it, while blocking command-like text. `LOOT` can pick nearby
visible drops through the normal server pickup path when policy allows it, and
successful pickups are written to `agent_economy_ledger`. The future-facing
verbs `ROAM`, `ATTACK`, `GRIND`, `NPC`, `SHOP`, `TRADE`, `PARTY`, `USEITEM`,
and `EQUIP` are parsed and audited, but blocked until their dedicated systems
are implemented.

When an agent has no active goal, script fallback advances through parsed script
lines using the current runtime session's previous `INTENT_PLAN` count as the
cursor. This keeps multi-line scripts deterministic, wraps at the end of the
script, and avoids adding extra state columns while the foundation is still
evolving.

When `USE_AGENT_RUNTIME` is enabled, the runtime starts a maintenance scheduler
that ticks already-entered agent characters every 5 seconds. The
scheduler does not create sessions, enter characters, or spawn agents by itself;
it only processes handles explicitly entered through `AgentSpawnCoordinator`.

## Observation And Social Tables

### `agent_memory_events`

Stores notable observations, summaries, and events that can later inform
behavior, personality, and LLM context.

### `agent_relationships`

Stores per-character relationship state such as trust and affinity.

This lets agents remember players, other agents, and future party members
without baking social state into gameplay classes.

### `agent_chat_logs`

Stores inbound, outbound, and internal agent chat records for moderation,
debugging, CMS viewing, and future personality tuning.

## Audit And Economy Tables

### `agent_action_logs`

Generic action history for runtime debugging and CMS inspection.

Actions should include movement, attack decisions, script failures, policy
denials, portal attempts, NPC interactions, and other runtime decisions.
The initial runtime service exposes lifecycle/session logging first so future
controllers do not scatter direct SQL writes through gameplay code.

### `agent_economy_ledger`

Economy-specific audit trail.

Agents may eventually loot, trade, buy, sell, open stores, and affect the
economy. These actions need a focused ledger so staff can inspect where mesos
and items moved.

### `agent_policies`

Stores global or per-agent technical limits.

`agent_profile_id = 0` represents a global policy. Per-agent policies can
override global values later.

The dispatcher reads capability toggles from this table before execution:

- `intent.self.enabled` defaults to enabled and only allows no-op `IDLE`/`WAIT`
- `intent.chat.enabled`
- `intent.navigation.enabled`
- `intent.combat.enabled`
- `intent.loot.enabled`
- `intent.npc.enabled`
- `intent.shop.enabled`
- `intent.trade.enabled`
- `intent.party.enabled`
- `intent.inventory.enabled`
- `intent.script.enabled`

All gameplay-facing capabilities default to disabled. Enabling a policy row only
passes the policy gate; runtime handlers still block the intent until that
capability is implemented.

## Migration Source

The schema is created from:

`src/main/resources/db/extensions/2026-06-16-agent-foundation.xml`

Liquibase runs files in `src/main/resources/db/extensions` during normal Cosmic
startup. If the tables already exist, the migration safely leaves them alone.
