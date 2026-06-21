# Agent Headless Client Architecture

## Decision

The broken in-server agent runtime has been removed from Cosmic. Future agents should run as an external headless client process that connects to Cosmic through the normal login and channel protocol.

This keeps the game server maintainable:

- Cosmic receives normal client packets.
- Movement, mob control, NPC dialogs, shop actions, item use, and chat flow through existing packet handlers.
- Other players see agents through the same spawn and movement broadcasts used by real clients.
- Agent CMS owns configuration, cards, schedules, and observability, but does not inject fake characters into maps.

## Components

### Cosmic Server

Cosmic should stay unaware of agent internals. Allowed integration points are narrow operations endpoints such as:

- server health
- cache reloads
- optional diagnostics

No agent tick loop, fake `Client`, fake channel session, or direct map movement simulation should live inside Cosmic.

### Agent CMS

Agent CMS owns persistent agent data in `cosmic_agent_cms`:

- agent profiles
- card loadouts
- task queues and schedules
- personality and behavior cards
- runtime audit/history
- operator controls

Agent CMS should call an external runtime service for deploy, undeploy, pause, and status. The current Server CMS bridge should not be used for agent actions.

Current implementation status:

- `AgentVirtualClientSystem` lives in Agent CMS API, not in Cosmic server.
- `prepare` opens a durable Agent CMS runtime-session record.
- `enter` connects to the real Cosmic login socket, reads the v83 hello packet, parses version/locale/client IVs, verifies the selected channel socket is reachable, and checks whether Agent CMS has a local runtime credential for the selected profile.
- Agent-created accounts can persist a local Agent-CMS-only runtime credential in `agent_runtime_credentials`; imported or older profiles report `CREDENTIAL_REQUIRED` until a credential path is added.
- `AgentLoginPacketFactory` can build the plain v83 `LOGIN_PASSWORD` packet body.
- `AgentMapleCrypto` can frame and encrypt the login body using the v83 Maple AES/OFB transport.
- When `AGENT_CMS_SEND_LOGIN_PACKETS=true`, the guarded live probe can submit `LOGIN_PASSWORD`, read `LOGIN_STATUS`, request `SERVERLIST`, drain login-screen follow-up packets, request `CHARLIST`, select the configured character, read `SERVER_IP`, connect to the selected channel socket, and send `PLAYER_LOGGEDIN`.
- Live credential submission is disabled by default behind `AGENT_CMS_SEND_LOGIN_PACKETS=false`.
- A channel packet pump keeps the socket alive, records recent server packets, responds to `PING` with `PONG`, and updates Agent CMS runtime-session heartbeat state.
- `AgentMovementPacketFactory` can build a minimal v83 `MOVE_PLAYER` body with the expected prelude and absolute movement fragment.
- `AgentChannelPacketObserver` parses the first useful channel observations: `SET_FIELD`, `MOVE_PLAYER`, and `PING`.
- The channel packet pump stores observed map, spawn point, hp, and position/stance/foothold when packets provide them.
- `AgentSpawnPositionResolver` seeds initial x/y from Agent CMS owned `agent_map_portals` data when a channel warp packet only provides map id and spawn index. Agent runtime data must stay in `cosmic_agent_cms`; if WZ/catalog data is needed, Agent CMS imports and maintains its own copy.
- `AgentMapCatalogImporter` reads `Map.wz/Map/**/*.img.xml` directly and imports portal/spawn, foothold, ladder, and rope rows into Agent CMS-owned map geometry tables. Runtime pages expose status and a manual import button so Agent CMS does not depend on Database CMS catalog tables.
- `AgentNavigationExecutionSystem` is the first movement execution layer for the headless client. It reads Agent CMS-owned map geometry, projects x/y onto footholds, chooses portal-chain routes for target maps, emits guarded `MOVE_PLAYER` steps, and fails closed when no route exists instead of wandering through unrelated portals.
- Local execution now distinguishes walk, jump, drop, ladder/rope approach, ladder/rope climb, route-blocked, and at-portal states. Stance/duration values are still calibration candidates and must be verified in the live v83 client.
- `AgentPortalPacketFactory` can build normal v83 `CHANGE_MAP` portal-entry packets and special-portal packet shapes for later scripted travel support. `AgentVirtualClientSystem` sends a normal portal packet when the navigator reaches `AT_PORTAL_TARGET`, with a short cooldown while waiting for the server's next `SET_FIELD`.
- `AgentPhysicsSystem` remains as a local fallback for idle patrol/probe movement when no usable map-catalog target exists. The long-term runtime still needs true physics arcs for jump/drop/climb movement rather than only packet-shape candidates.
- `tick` reports movement or portal packet previews for a live channel socket. Sending is guarded behind `AGENT_CMS_SEND_MOVEMENT_PACKETS=false` and will not send until real observed x/y is known.
- `tick` still does not emit chat, NPC, item, or combat actions; no fake map movement or direct server injection exists.
- The next step is position observation plus physics-driven movement packet generation from a real headless channel session.

### Headless Client Runtime

The runtime should be a separate process or service with these systems:

- **Protocol System**: login, world select, channel select, character select, encryption/session handling, opcode encode/decode.
- **Physics System**: v83-like foothold gravity, jump, fall, ladder/rope, walk speed, stance, and movement-fragment generation.
- **Navigation System**: WZ foothold graph for local movement plus portal graph for map travel.
- **Action System**: packet-level actions for movement, NPC click/dialog, quest choice, item use, equip, shop buy/sell/recharge, chat, party, trade, and combat.
- **Card Execution System**: converts task/behavior/personality cards into prioritized runtime intents.
- **Observation System**: reads server packets to maintain map objects, mobs, drops, NPCs, chat, HP/MP, inventory, quest state, and current position.
- **Safety System**: stuck detection, route retry, fallback task, resource sustain policy, and operator alerts.

## Reference Notes

SoloMapling proves that bots can look alive, but it does so by adding a server-side artificial player layer. That approach works for demos but risks long-term server coupling.

nutnnut's movement tooling is more useful for this direction: packet logging plus physics calibration can help make the headless runtime produce client-like movement packets.

## Migration Rule

Reusable code/data from the previous agent work should move into Agent CMS or the external runtime only when it does not depend on `server.agent` or fake server-side clients.

Server code should not regain:

- `server.agent.*`
- headless `Client` constructors
- `/internal/admin/agents/*` bridge endpoints
- agent runtime scheduler modules
- agent-specific map movement helpers
