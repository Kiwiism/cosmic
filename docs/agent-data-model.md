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

Runtime ticks update `progress_json` with the latest intent, dispatch status,
perception counts, goal reason, and a compact diagnosis. The diagnosis includes
`diagnosisState`, `diagnosisReason`, and `recommendedAction` so Agent CMS can
show why a goal is running, blocked by policy, waiting for cooldown, blocked by
an unimplemented runtime adapter, completed, or failed without requiring a
schema migration for each new diagnostic field.

Each pilot tick also records a `TARGET_SCAN` memory event with the nearest
player, monster, drop, NPC, and reactor from the current perception snapshot.
This targeting groundwork gives Agent CMS and future behavior modules a
consistent view of what the agent could interact with next.

Combat v1 starts with conservative nearby monster combat. When combat policy is
enabled, `ATTACK` and `GRIND` select the nearest matching alive monster, move
toward the monster until inside conservative attack range, and then prefer a
learned attack skill only when the skill has no cooldown, does not consume HP,
and the agent has enough MP. Skill combat consumes MP through the normal
character stat path and applies capped server-side damage through
`MapleMap.damageMonster`. If no safe skill is usable, the adapter falls back to
a capped basic non-skill hit. Boss monsters are blocked for now; this keeps
target selection, approach movement, policy gates, cooldowns, damage, drops,
and Agent CMS observability testable without imitating the full client attack
packet pipeline.

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
`PORTAL` intents when the route is available. Portal execution is staged: if the
agent is not near the portal, the adapter records an `APPROACH_PORTAL` movement
instead of entering immediately; the portal is only used once the agent is close
enough on a later tick. Navigation also supports bounded in-map repositioning for
`MOVE x y`, `ROAM` toward the nearest safe traversal portal, and visible
`FOLLOW_CHARACTER` targets. These local moves update server position and
visibility with a capped step size; they do not synthesize client movement
packets. Local movement records `MOVED`, `ALREADY_NEARBY`, `STUCK`, or
`NO_PROGRESS` details so Agent CMS can show whether the bounded step actually
reduced the remaining distance. `SAY` broadcasts normal map chat when policy
allows it, while blocking command-like text. `LOOT` can pick nearby visible drops
through the normal server pickup path when policy allows it, and successful
pickups are written to `agent_economy_ledger`. `NPC` can select and approach a
visible NPC, then records `NPC_READY` once inside interaction range; it does not
open scripts or advance dialogs yet. `SHOP` can select and approach a visible
NPC that has a database-backed shop, then records `SHOP_READY` once inside shop
range; it does not open the shop or buy/sell items yet. `USEITEM` and `EQUIP`
can inspect the agent's current inventory, resolve an item by id/name or simple
aliases such as `hp`, `mp`, and `potion`, then record `ITEM_READY`,
`EQUIP_READY`, `NO_ITEM`, or `NO_EQUIP`; they do not consume items or equip gear
yet. `SKILL` and `CAST` can inspect learned skills, resolve a skill by id or
generic aliases such as `attack`, `buff`, `active`, and `passive`, then record
`SKILL_READY` or `NO_SKILL`; direct skill intents still do not cast skills, but
combat intents may use safe attack skills through the combat adapter. `PARTY` can inspect
the agent's current party plus visible nearby player targets and records
`PARTY_STATUS`, `PARTY_TARGET_READY`, `INVITE_TARGET_READY`, `ALREADY_PARTIED`,
`PARTY_FULL`, or `NO_PARTY_TARGET`; it does not create, invite, join, leave, or
expel party members yet. `TRADE` can inspect the current trade object plus
visible nearby player targets and records `TRADE_STATUS`, `TRADE_TARGET_READY`,
`TRADE_PARTNER_READY`, `TRADE_INVITE_PENDING`, `TRADE_BUSY`, `TRADE_OPEN`, or
`NO_TRADE_TARGET`; it does not open trade windows, invite players, move items,
or move mesos yet.

When an agent has no active goal, script fallback advances through parsed script
lines using the current runtime session's previous `INTENT_PLAN` count as the
cursor. This keeps multi-line scripts deterministic, wraps at the end of the
script, and avoids adding extra state columns while the foundation is still
evolving.

If no active goal and no configured script are available, the planner now falls
back to the profile's behavior preset. Initial presets are `GRINDER`, `LOOTER`,
`COMPANION`, `TOWN_IDLER`, and `ROAMER`. These presets only choose the next
intent; normal policy and cooldown gates still decide whether combat, loot,
navigation, chat, or other gameplay-facing actions may execute.

Agent CMS includes a read-only script preview endpoint. The preview mirrors the
current script command vocabulary and reports line number, parsed intent,
capability, duration, and warnings for unknown or future-gated actions. It does
not execute scripts and does not replace the server-side parser; it exists so
staff can catch obvious mistakes before saving or assigning scripts.

Runtime safety diagnostics are intentionally observational in v1. Each pilot
tick now checks for slow ticks, missing perception, detached client/character
references, missing maps, and repeated identical blocked dispatches. When a
threshold is crossed the runtime writes a `SAFETY_CHECK` action log and memory
event so Agent CMS can show the warning without interrupting the agent or
mutating gameplay. This gives staff enough evidence to tune policies, cooldowns,
or goals before stricter automatic release/rollback behavior is enabled.

Agent CMS also exposes a smoke-test action for selected agents. It calls the
same bridge actions as the manual controls in order: `prepare`, `enter`, then
`tick`. The sequence stops at the first bridge failure and records each step in
the response. It does not auto-release the agent afterward, so staff can inspect
the entered character, run more ticks, or release manually.

Agent scripts also support lightweight control syntax for simple behavior
loops:

- `REPEAT 3 WAIT 1` expands into three one-second wait intents.
- `2x SAY hello` is shorthand for repeating one command twice.
- Inline comments can be added after a command with ` # comment`.

Repeats are capped at 50 expanded steps per line to protect the runtime from
accidental oversized scripts. The Agent CMS preview expands repeated commands
the same way as the server parser so staff can see the exact fallback sequence.

Intent dispatch also passes through a runtime cooldown gate after capability
policy succeeds. Cooldowns can be configured globally or per-agent through
`agent_policies` using `cooldown.<capability>.millis` or
`cooldown.<intent>.millis`; the intent-specific value wins. Cooldown blocks are
logged as dispatch rows, but only successful dispatches start the next cooldown
window.

Agent CMS exposes these cooldowns separately from capability allow/block
policies. This keeps safety policy and action pacing independent: staff can
allow navigation while still slowing portal/follow attempts, or allow chat while
rate-limiting SAY intents. Per-agent cooldown rows override global rows; unset
rows fall back to the server defaults.

The Agent CMS runtime page can manage the global defaults directly. Global
capability and cooldown rows are stored with `agent_profile_id = 0`; per-agent
rows still win when present. Resetting a global row removes the database
override and returns that policy to the built-in server default.

The Agent CMS runtime page also exposes a lightweight summary endpoint for
operational checks: open sessions, stale sessions, 24-hour action status counts,
cooldown blocks, safety-warning counts, the latest blocked or failed actions,
and the latest safety diagnostics. Session rows include heartbeat age and the
most recent safety warning for that agent. This is intentionally read-only and
uses existing runtime tables so it can be expanded without adding new server
coupling.

Agent CMS can mark stale open runtime sessions as stopped. The recovery action
only applies to sessions with no heartbeat for more than two minutes, so it is
for cleanup after interrupted experiments or crashes rather than controlling a
live agent that is still ticking in memory.

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
debugging, CMS viewing, and future personality tuning. Successful `SAY` actions
are recorded as `MAP_GENERAL` / `OUTBOUND` rows after the normal map chat packet
is broadcast.

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
override global values later. Agent CMS manages both scopes: the runtime page
edits global defaults, and the agent profile page edits per-agent overrides.

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
- `intent.skill.enabled`
- `intent.script.enabled`

All gameplay-facing capabilities default to disabled. Enabling a policy row only
passes the policy gate; runtime handlers still block the intent until that
capability is implemented.

## Migration Source

The schema is created from:

`src/main/resources/db/extensions/2026-06-16-agent-foundation.xml`

Liquibase runs files in `src/main/resources/db/extensions` during normal Cosmic
startup. If the tables already exist, the migration safely leaves them alone.
