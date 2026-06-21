# Cosmic Agent Client

`agent-client` is the headless runtime for agents. It is intentionally separate from Agent CMS and from the Cosmic server.

- Agent CMS is the control/configuration UI.
- Agent Client connects to the Cosmic login/channel ports like a normal v83 client.
- Gameplay actions are sent as client packets over TCP, not through a server backdoor.
- Runtime state and logs are stored in `cosmic_agent_cms`.

## Run

```powershell
cd C:\Users\user\IdeaProjects\Cosmic\agent-client\api
..\start-agent-client.ps1
```

The API listens on `http://localhost:8094` by default.

## Manual Test Harness

Agent CMS can call Agent Client for local packet calibration:

- `Prepare` creates a virtual session and validates credentials.
- `Enter world` opens login/channel sockets and starts the packet pump.
- Held movement keys are tracked by Agent Client and emitted repeatedly while held.
- Movement packets emit mode-specific walk, jump, drop, and climb fragments instead of only single absolute position updates.
- Manual actions can send guarded packet bodies for chat, expressions, NPC open/continue, loot pickup, item/chair use, portal entry, and basic close-range attack calibration.

Basic attack packets are guarded by `AGENT_CLIENT_SEND_COMBAT_PACKETS=false` by default. Keep that off until live client calibration confirms the animation and damage body are accepted cleanly.
