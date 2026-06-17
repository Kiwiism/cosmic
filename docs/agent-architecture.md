# Cosmic Agent Architecture

This document captures the planned direction for persistent server-side agents.
Agents are real characters temporarily driven by server runtime code. They should
remain playable by a human account when not controlled by the agent runtime.

## Goals

- Keep `main` stable before agent work expands.
- Build agents behind a runtime/module boundary instead of scattering control
  logic directly through core gameplay classes.
- Start with scripted, deterministic behavior.
- Allow an optional local LLM plugin for dialogue/personality, not unrestricted
  gameplay control.
- Preserve the server as the source of truth for movement, combat, inventory,
  trading, shops, chat, and economy.
- Make every economic action observable even when agents are allowed to trade
  and affect the economy freely.

## Control Planes

### Agent CMS

For server-owned agents and admin operations:

- create/manage agent accounts and characters
- assign behavior profiles, scripts, personalities, and goals
- deploy/despawn agents by world/channel/map
- monitor current state, action, map, HP/MP, inventory pressure, and errors
- inspect chat, trade, loot, stall, and meso ledgers

### Player CMS

For player-owned characters:

- enable or disable offline autopilot
- choose behavior limits and target goals
- inspect live status while the character is agent-controlled
- chat through the character while it is controlled by the agent runtime
- take over safely by logging in with the normal client

### Server CMS

Server CMS remains the technical operations console:

- server configuration
- runtime limits
- diagnostics
- module enablement
- agent runtime safety limits when those settings are technical rather than
  gameplay/economy balance

## Runtime Layers

```text
Cosmic Server
  -> RuntimeModuleManager
      -> AgentRuntimeModule
          -> AgentRegistry
          -> AgentController
          -> AgentScheduler
          -> AgentPolicy
          -> AgentScriptRunner
          -> AgentGoalPlanner
          -> AgentCommunicationBus
          -> AgentEconomyLedger
          -> Optional LLM Adapter
```

The runtime module layer is intentionally small. Optional systems should start,
stop, and fail independently so the core server remains stable.

## Agent Types

- **Server Agent**: server-owned persistent character managed through Agent CMS.
- **Player Agent**: player-owned character running offline autopilot.
- **Companion Agent**: assigned to follow/help a player.
- **Simulation Agent**: controlled actor for load tests or event simulation.

Every persistent agent should map to a normal `characters.id` so it can be saved,
loaded, traded with, equipped, and manually played when not agent-controlled.

## Behavior Model

Default behavior should be script-first:

```text
perception -> script/goal planner -> policy check -> controller action
```

LLM can be plugged in later for:

- dialogue
- personality
- explanations
- lightweight social decisions

LLM output should become an intent that still passes through policy and
controller validation.

## First Milestones

1. **Runtime foundation**
   - module lifecycle
   - lifecycle events
   - safe startup/shutdown hooks

2. **Agent data model**
   - profiles
   - ownership
   - runtime sessions
   - action logs

3. **Dormant runtime service layer**
   - profile repository
   - enabled-profile registry
   - runtime module boundary
   - conservative control eligibility guard
   - read-only spawn planning
   - runtime session lifecycle service
   - action logging boundary
   - control shell reservation boundary
   - read-only map perception counts
   - non-executing script-to-intent parser
   - no gameplay control yet

4. **Server CMS foundation**
   - create and edit agent profiles from existing characters
   - inspect spawn preflight state
   - inspect runtime sessions and action logs
   - separate Server CMS database from game database access

5. **Minimal runtime**
   - prepare a character through the normal database load path
   - reserve one runtime session per agent profile
   - release prepared handles on module stop
   - provide a headless channel client that safely absorbs outbound packets
   - load a normal character through an internal client
   - spawn/despawn safely
   - prevent duplicate real-client login
   - prevent duplicate in-memory agent reservation for the same character
   - save state on shutdown/despawn
   - release an entered agent after repeated tick failures so one broken agent
     does not keep failing forever inside the scheduler

   The first implementation prepares characters without adding them to map or
   world player storage. The second slice adds a headless channel client so
   future server-side actors can receive normal server packet calls without a
   Netty socket. Visible spawn still remains separated until the map/world enter
   lifecycle is explicit and reversible.

6. **Dedicated Agent CMS MVP**
   - roster
   - profile detail
   - deploy/despawn controls
   - live runtime state

7. **Same-map movement**
   - idle
   - town hangout
   - follow
   - patrol
   - bounded local approach for mobs, drops, players, and portals
   - safe portal traversal for roam and loaded route steps

8. **Perception**
   - nearby players
   - nearby agents
   - mobs
   - drops
   - portals
   - NPCs

9. **Communication**
   - internal agent message bus
   - say/party/whisper channels
   - CMS chat bridge

10. **Basic grinding**
   - target nearby mobs
   - move into range
   - basic attack
   - potion use
   - loot
   - approach visible drops before pickup instead of failing while still far
     away
   - include HP/MP in planning snapshots
   - prefer recovery intents before grind/goal actions when HP or MP is low
   - consume one normal HP/MP recovery item only for explicit recovery aliases
     (`hp`, `health`, `mp`, `mana`, `potion`); other item/equip intents remain
     readiness-only until a stricter item policy is added

11. **Economy**
    - trading
    - stalls
    - shop interactions
    - economy ledger

12. **Goals**
    - primary goals
    - side goals
    - progress tracking
    - blocked goal diagnostics

13. **Optional LLM**
    - local Ollama-style adapter
    - personality profiles
    - dialogue only at first

14. **World travel**
    - map portal graph
    - scroll/taxi/NPC travel policies
    - safe stuck recovery

15. **Parties and expeditions**
    - role assignment
    - readiness checks
    - coordinator-controlled boss behavior

## Design Rules

- Agent runtime must be disabled by default until explicitly enabled.
- `server.USE_AGENT_RUNTIME` and the Server CMS setting
  `server.agent.runtimeEnabled` are the runtime gate.
- No agent should be controlled by both a real client and runtime code.
- Agent control eligibility must check live world storage and account login state.
- Runtime failures should stop the affected agent, not the whole server.
- Economy-changing actions must be logged.
- High-frequency movement/combat decisions must avoid blocking DB or LLM calls.
- LLM calls must be optional, timeout-bounded, and never required for core ticks.
- Scripts and goals should be inspectable from CMS.
